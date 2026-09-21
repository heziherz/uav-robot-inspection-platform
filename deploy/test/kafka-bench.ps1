# ============================================================
#  kafka-bench.ps1  -- Kafka 性能测试工具
#
#  【设计目标：可执行 + 可移植】
#    · 全部操作通过 `docker exec` 在容器内执行
#      → 宿主机无需安装任何 Kafka 工具
#    · 容器名 / 地址 / 主题名 / 分区数全部可配
#      → 换一套环境只改参数
#    · 测试使用【专用 bench 主题】，不碰业务数据
#    · 结果可落盘 CSV（-OutFile），便于填表与画图
#
#  【用法】
#     .\kafka-bench.ps1 -Show                      只看布局与【所有消费组】的 LAG
#     .\kafka-bench.ps1 -Recreate                  跑吞吐测试
#     .\kafka-bench.ps1 -Recreate -OutFile bench.csv   同时落盘
#     .\kafka-bench.ps1 -Sweep                     分区数批量对照（1/3/6）
#     .\kafka-bench.ps1 -Sweep -SweepPartitions 1,3,6,12
#     .\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group
#     .\kafka-bench.ps1 -Recreate -LingerMs 20 -Compression lz4    参数对照
#
#  【注意】本文件含中文，必须以 UTF-8 **带 BOM** 保存，
#          否则 PowerShell 5.1 会按 ANSI 解析导致语法错误。
# ============================================================

param(
    [string] $Container   = "kafka",
    [string] $Bootstrap   = "localhost:9092",
    [string] $Topic       = "bench-topic",
    [int]    $Partitions  = 3,
    [int]    $Records     = 100000,
    [int]    $RecordSize  = 300,
    [int]    $Throughput  = -1,
    [string] $Group       = "bench-group",
    [string] $Acks        = "all",
    [string] $LingerMs    = "0",
    [string] $Compression = "none",
    [string] $OutFile     = "",
    [switch] $Recreate,
    [switch] $Show,
    [switch] $ShowAll,
    [switch] $WatchLag,
    [switch] $Sweep,
    [int[]]  $SweepPartitions = @(1, 3, 6)
)

$BIN = "/opt/kafka/bin"
$CSV_HEADER = "timestamp,partitions,records,record_size,acks,linger_ms,compression," +
              "produce_rec_per_sec,produce_mb_per_sec,produce_avg_ms,produce_p50_ms,produce_p95_ms,produce_p99_ms," +
              "consume_rec_per_sec,consume_mb_per_sec"

# ---------------------------------------------------------------- 基础封装

function Invoke-Kafka {
    # 注意：参数名不能用 $Args —— 那是 PowerShell 的自动变量，会冲突。
    param([string]$KScript, [string[]]$KArgs)

    # 注意：Kafka 脚本会往 stderr 打日志。在 PowerShell 里，`2>&1` 捕获到的
    # stderr 会变成 ErrorRecord **对象**混进结果数组，导致后续字符串操作
    # （.Trim()、-match）报 "不包含名为 Trim 的方法"。
    # 因此这里只保留字符串行。
    $raw = @(docker exec $Container "$BIN/$KScript" --bootstrap-server $Bootstrap @KArgs 2>&1)
    return @($raw | Where-Object { $_ -is [string] })
}

function Show-AvailableScripts {
    Write-Host "`n=== 容器内可用的 Kafka 脚本 ===" -ForegroundColor DarkGray
    docker exec $Container ls $BIN 2>&1 |
        Select-String -Pattern "perf|offset|consumer-groups|topics" |
        ForEach-Object { Write-Host ("  " + $_.Line.Trim()) -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- 观测

function Show-AllTopics {
    Write-Host "`n=== 全部主题的分区数（★ 分区数 = 消费并行度上限）===" -ForegroundColor Cyan
    # 用 --describe 不带 --topic 时列全部；正则不再要求出现 TopicId
    # （不同 Kafka 版本的 --describe 输出字段略有差异）
    $out = Invoke-Kafka "kafka-topics.sh" @("--describe")
    $found = 0
    foreach ($line in $out) {
        if ($line -notmatch 'Topic:\s+(\S+)') { continue }
        $name = $Matches[1]
        if ($name -like "__*") { continue }
        if ($line -notmatch 'PartitionCount:\s+(\d+)') { continue }   # 分区详情行跳过
        $found++
        $pc = [int]$Matches[1]
        $color = if ($pc -le 1) { "Yellow" } else { "Green" }
        Write-Host ("  {0,-28} 分区 {1}" -f $name, $pc) -ForegroundColor $color
    }
    if ($found -eq 0) {
        Write-Host "  (未读到任何主题 —— topic 可能尚未创建，或 Kafka 容器不可达)" -ForegroundColor Yellow
    }
}

function Get-LagRows {
    param([string]$G = $Group)
    $rows = @()
    $out = Invoke-Kafka "kafka-consumer-groups.sh" @("--describe", "--group", $G)
    foreach ($line in $out) {
        $f = ($line -split '\s+' | Where-Object { $_ -ne '' })
        if ($f.Count -ge 6 -and $f[2] -match '^\d+$' -and $f[5] -match '^-?\d+$') {
            $rows += [PSCustomObject]@{
                Topic = $f[1]; Part = [int]$f[2]
                Current = [long]$f[3]; End = [long]$f[4]; Lag = [long]$f[5]
            }
        }
    }
    return $rows
}

function Show-Lag {
    param([string]$G = $Group)
    Write-Host "`n=== 消费组 LAG: $G ===" -ForegroundColor Cyan
    $rows = Get-LagRows -G $G
    if ($rows.Count -eq 0) {
        Write-Host "  (该消费组不存在或未激活)" -ForegroundColor Yellow
        Write-Host "  提示：bench 组要跑过一次吞吐测试才会出现；" -ForegroundColor DarkGray
        Write-Host "        看业务消费组请用 -ShowAll 或指定 -Group platform-service-group" -ForegroundColor DarkGray
        return
    }
    $rows | Format-Table -AutoSize
    $total = ($rows | Measure-Object -Property Lag -Sum).Sum
    $color = if ($total -eq 0) { "Green" } elseif ($total -lt 10000) { "Yellow" } else { "Red" }
    Write-Host ("  合计 LAG = {0}   （0 = 消费跟得上；持续增长 = 已到瓶颈）" -f $total) -ForegroundColor $color
}

# 列出所有消费组及其 LAG —— 首次使用最该看这个（业务组在这里）
function Show-AllGroupsLag {
    Write-Host "`n=== 全部消费组及其 LAG ===" -ForegroundColor Cyan
    $groups = @(Invoke-Kafka "kafka-consumer-groups.sh" @("--list")) |
              Where-Object { $_ -is [string] -and $_.Trim() -ne "" -and $_.Trim() -notmatch "^(Note:|WARN|SLF4J)" } |
              ForEach-Object { $_.Trim() } |
              Select-Object -Unique

    if ($groups.Count -eq 0) {
        Write-Host "  (没有读到任何消费组 —— 请确认后端服务已启动，且容器名正确)" -ForegroundColor Yellow
        return
    }

    foreach ($name in $groups) {
        $rows = Get-LagRows -G $name
        if ($rows.Count -eq 0) {
            Write-Host ("`n  [{0}]  该组当前无活跃分区" -f $name) -ForegroundColor DarkGray
            continue
        }
        $total = ($rows | Measure-Object -Property Lag -Sum).Sum
        $color = if ($total -eq 0) { "Green" } elseif ($total -lt 10000) { "Yellow" } else { "Red" }
        Write-Host ("`n  [{0}]   合计 LAG = {1}   主题数 {2}" -f $name, $total, $rows.Count) -ForegroundColor $color
        # 只列出有 LAG 的分区，避免刷屏
        $hot = $rows | Where-Object { $_.Lag -gt 0 }
        if ($hot) {
            $hot | Format-Table -AutoSize
        } else {
            Write-Host "    所有分区 LAG = 0（消费完全跟得上）" -ForegroundColor Green
        }
    }
}

function Watch-Lag {
    param([string]$G = $Group, [int]$IntervalSec = 5, [int]$Times = 24)
    Write-Host "`n=== 持续观察 LAG（每 ${IntervalSec}s 一次，共 $Times 次）===" -ForegroundColor Cyan
    Write-Host "  时间       合计LAG      变化" -ForegroundColor DarkGray
    $prev = -1
    for ($i = 0; $i -lt $Times; $i++) {
        $total = ((Get-LagRows -G $G) | Measure-Object -Property Lag -Sum).Sum
        if ($null -eq $total) { $total = 0 }
        $delta = if ($prev -lt 0) { "-" } else { "{0:+#;-#;0}" -f ($total - $prev) }
        $color = if ($total -eq 0) { "Green" } else { "Red" }
        Write-Host ("  {0}  {1,10}  {2,8}" -f (Get-Date -Format "HH:mm:ss"), $total, $delta) -ForegroundColor $color
        $prev = $total
        Start-Sleep -Seconds $IntervalSec
    }
}

# ---------------------------------------------------------------- 主题

function New-BenchTopic {
    param([int]$P)
    Write-Host "`n=== 重建测试主题: $Topic（$P 分区）===" -ForegroundColor Cyan
    Invoke-Kafka "kafka-topics.sh" @("--delete", "--topic", $Topic) | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Kafka "kafka-topics.sh" @("--create", "--topic", $Topic,
        "--partitions", "$P", "--replication-factor", "1") | Out-Null
    Write-Host "  已创建" -ForegroundColor Green
}

# ---------------------------------------------------------------- 吞吐测量

function Invoke-ProducerPerf {
    $props = "bootstrap.servers=$Bootstrap", "acks=$Acks",
             "linger.ms=$LingerMs", "compression.type=$Compression"
    $out = docker exec $Container "$BIN/kafka-producer-perf-test.sh" `
        --topic $Topic --num-records $Records --record-size $RecordSize `
        --throughput $Throughput --producer-props @props 2>&1
    $text = ($out | Out-String)

    $r = @{ rec_per_sec = ""; mb_per_sec = ""; avg_ms = ""; p50 = ""; p95 = ""; p99 = "" }
    if ($text -match '([\d.]+)\s+records/sec')          { $r.rec_per_sec = $Matches[1] }
    if ($text -match '\(([\d.]+)\s+MB/sec\)')           { $r.mb_per_sec  = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+avg\s+latency')   { $r.avg_ms     = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+50th')            { $r.p50        = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+95th')            { $r.p95        = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+99th')            { $r.p99        = $Matches[1] }

    Write-Host ("  生产吞吐 {0} 条/秒   带宽 {1} MB/s   p95 {2} ms   p99 {3} ms" -f `
        $r.rec_per_sec, $r.mb_per_sec, $r.p95, $r.p99) -ForegroundColor Yellow
    if (-not $r.rec_per_sec) {
        Write-Host "  [解析失败] 原始输出（格式可能因 Kafka 版本而异）:" -ForegroundColor Red
        $out | Select-Object -Last 3 | ForEach-Object { Write-Host "    $_" }
    }
    return $r
}

function Invoke-ConsumerPerf {
    $out = docker exec $Container "$BIN/kafka-consumer-perf-test.sh" `
        --bootstrap-server $Bootstrap --topic $Topic `
        --messages $Records --group $Group --timeout 120000 2>&1

    $r = @{ rec_per_sec = ""; mb_per_sec = "" }
    # 数据行形如：2026-09-21 10:00:00, ..., 57.22, 2.48, 200000, 8695.65, ...
    foreach ($line in $out) {
        if ($line -match '^\d{4}-\d{2}-\d{2}') {
            $f = $line -split ','
            if ($f.Count -ge 6) {
                $r.mb_per_sec  = $f[3].Trim()
                $r.rec_per_sec = $f[5].Trim()
            }
        }
    }
    Write-Host ("  消费吞吐 {0} 条/秒   带宽 {1} MB/s" -f $r.rec_per_sec, $r.mb_per_sec) -ForegroundColor Yellow
    if (-not $r.rec_per_sec) {
        Write-Host "  [解析失败] 原始输出（格式可能因 Kafka 版本而异）:" -ForegroundColor Red
        $out | Select-Object -Last 3 | ForEach-Object { Write-Host "    $_" }
    }
    return $r
}

# ---------------------------------------------------------------- CSV 落盘

function Write-ResultCsv {
    param([hashtable]$Produce, [hashtable]$Consume, [int]$P)
    if (-not $OutFile) { return }
    if (-not (Test-Path $OutFile)) {
        $CSV_HEADER | Set-Content -Path $OutFile -Encoding UTF8
    }
    $row = @(
        (Get-Date -Format "yyyy-MM-dd HH:mm:ss"),
        $P, $Records, $RecordSize, $Acks, $LingerMs, $Compression,
        $Produce.rec_per_sec, $Produce.mb_per_sec,
        $Produce.avg_ms, $Produce.p50, $Produce.p95, $Produce.p99,
        $Consume.rec_per_sec, $Consume.mb_per_sec
    ) -join ","
    $row | Add-Content -Path $OutFile -Encoding UTF8
    Write-Host "  已写入 $OutFile" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 单轮测试

function Invoke-OneRound {
    param([int]$P)
    New-BenchTopic -P $P
    Write-Host "`n  --- 生产端（$Records 条 x ${RecordSize}B, acks=$Acks, linger=$LingerMs, compress=$Compression）---" -ForegroundColor Cyan
    $prod = Invoke-ProducerPerf
    Write-Host "  --- 消费端 ---" -ForegroundColor Cyan
    $cons = Invoke-ConsumerPerf
    Write-ResultCsv -Produce $prod -Consume $cons -P $P
    return @{ P = $P; Prod = $prod; Cons = $cons }
}

# ---------------------------------------------------------------- 批量对照

function Invoke-Sweep {
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  分区数批量对照：$($SweepPartitions -join ' / ')" -ForegroundColor Cyan
    Write-Host "  ★ 预期：生产吞吐几乎不变，消费吞吐随分区数近似线性增长" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    $results = @()
    foreach ($p in $SweepPartitions) {
        Write-Host "`n[分区数 = $p]" -ForegroundColor Green
        $results += Invoke-OneRound -P $p
    }

    Write-Host "`n`n=== 对照结果 ===" -ForegroundColor Cyan
    Write-Host ("{0,8}  {1,14}  {2,12}  {3,10}  {4,14}  {5,12}" -f `
        "分区数", "生产(条/秒)", "生产(MB/s)", "p95(ms)", "消费(条/秒)", "消费(MB/s)")
    Write-Host ("-" * 78)
    foreach ($r in $results) {
        Write-Host ("{0,8}  {1,14}  {2,12}  {3,10}  {4,14}  {5,12}" -f `
            $r.P, $r.Prod.rec_per_sec, $r.Prod.mb_per_sec, $r.Prod.p95,
            $r.Cons.rec_per_sec, $r.Cons.mb_per_sec)
    }
    Write-Host ""
    Write-Host "  读法：" -ForegroundColor DarkGray
    Write-Host "    · 生产吞吐基本不随分区数变化 → Kafka 写入靠顺序追加" -ForegroundColor DarkGray
    Write-Host "    · 消费吞吐随分区数增长     → 分区数 = 消费并行度的硬上限" -ForegroundColor DarkGray
    Write-Host "    · 若消费吞吐不涨，检查消费端 concurrency 是否同步调大" -ForegroundColor DarkGray
    if ($OutFile) { Write-Host "`n  原始数据已写入 $OutFile（可直接填报告、画图）" -ForegroundColor Green }
}

# ---------------------------------------------------------------- 主流程

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Kafka 性能测试"
Write-Host "  容器=$Container  地址=$Bootstrap  主题=$Topic"
Write-Host "============================================================" -ForegroundColor Cyan

if ($WatchLag)          { Watch-Lag -G $Group; return }
if ($Show -or $ShowAll) { Show-AvailableScripts; Show-AllTopics; Show-AllGroupsLag; return }
if ($Sweep)             { Show-AvailableScripts; Invoke-Sweep; return }

# 单轮模式
Show-AvailableScripts
Show-AllTopics
if ($Recreate) { New-BenchTopic -P $Partitions }
Invoke-OneRound -P $Partitions | Out-Null
Show-Lag -G $Group

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  下一步：改变量做对照" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  ① 分区对照：   .\kafka-bench.ps1 -Sweep -OutFile sweep.csv"
Write-Host "  ② 生产者参数： .\kafka-bench.ps1 -Recreate -LingerMs 20 -Compression lz4"
Write-Host "  ③ 可靠性对照： .\kafka-bench.ps1 -Recreate -Acks 1"
Write-Host "  ④ 业务 LAG：   .\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group"
Write-Host ""
