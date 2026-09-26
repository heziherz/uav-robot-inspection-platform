# ============================================================
#  kafka-bench.ps1  -- Kafka 性能测试工具
#
#  【设计目标：可执行 + 可移植】
#    · 全部操作通过 `docker exec` 在容器内执行
#      → 宿主机无需安装任何 Kafka 工具
#    · 容器名 / 地址 / 主题名 / 分区数全部可配
#    · 测试使用【专用 bench 主题】，不碰业务数据
#    · 结果可落盘 CSV（-OutFile），便于填表与画图
#
#  【用法】
#     .\kafka-bench.ps1 -Show                      看分区布局 + 全部消费组的 LAG
#     .\kafka-bench.ps1 -Recreate                  跑吞吐测试
#     .\kafka-bench.ps1 -Recreate -OutFile bench.csv
#     .\kafka-bench.ps1 -Sweep                     分区数批量对照（1/3/6）
#     .\kafka-bench.ps1 -Sweep -SweepPartitions 1,3,6,12
#     .\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group
#     .\kafka-bench.ps1 -Recreate -LingerMs 20 -Compression lz4
#
#  【-Records 该怎么填】★ 别再用默认的 100000 / 200000
#     消费端测试里有一段【与消息量无关的固定开销】：消费者入组 + 分区分配（rebalance），
#     实测本机约 3.2 秒。消息太少时，整段测试几乎全被这段开销占满，测出的都是噪声。
#     按 114 万条/秒的真实读取速度算，要让固定开销占比降到 10% 以下：
#         -Records 至少 3000000（三百万）
#     脚本已改为取 fetch.* 列（已排除 rebalance），但开销过半时仍会打 ⚠ 警告 —— 见到就当无效数据。
#
#  【重要】本文件含中文，必须保存为 UTF-8 【带 BOM】+ CRLF 换行。
#          PowerShell 5.1 会把无 BOM 的 UTF-8 当 ANSI 解析，
#          中文字节被曲解后可能吞掉代码行，导致莫名其妙的 null 错误。
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
    # 参数名不能用 $Args —— 那是 PowerShell 的自动变量
    param([string]$KScript, [string[]]$KArgs)
    # Kafka 脚本会往 stderr 打日志；2>&1 捕获到的 stderr 会变成 ErrorRecord 对象，
    # 混进数组后会让 .Trim() / -match 报错 —— 因此只保留字符串行
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
    Write-Host "`n=== 全部主题的分区数（分区数 = 消费并行度上限）===" -ForegroundColor Cyan
    $out = Invoke-Kafka "kafka-topics.sh" @("--describe")
    $found = 0
    foreach ($line in $out) {
        if ($line -notmatch 'Topic:\s+(\S+)') { continue }
        $name = $Matches[1]
        if ($name -like "__*") { continue }
        if ($line -notmatch 'PartitionCount:\s+(\d+)') { continue }
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

function Show-AllGroupsLag {
    Write-Host "`n=== 全部消费组及其 LAG ===" -ForegroundColor Cyan
    $groups = @(Invoke-Kafka "kafka-consumer-groups.sh" @("--list")) |
              Where-Object { $_ -is [string] -and $_.Trim() -ne "" -and $_.Trim() -notmatch "^(Note:|WARN|SLF4J)" } |
              ForEach-Object { $_.Trim() } |
              Select-Object -Unique

    if ($groups.Count -eq 0) {
        Write-Host "  (没有读到任何消费组 —— 请确认后端服务已启动)" -ForegroundColor Yellow
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
        Write-Host ("`n  [{0}]   合计 LAG = {1}   分区数 {2}" -f $name, $total, $rows.Count) -ForegroundColor $color
        $hot = $rows | Where-Object { $_.Lag -gt 0 }
        if ($hot) { $hot | Format-Table -AutoSize }
        else { Write-Host "    所有分区 LAG = 0（消费完全跟得上）" -ForegroundColor Green }
    }
}

function Watch-Lag {
    param([string]$G = $Group, [int]$IntervalSec = 5, [int]$Times = 24)
    Write-Host "`n=== 持续观察 LAG（每 ${IntervalSec}s 一次，共 $Times 次）===" -ForegroundColor Cyan
    Write-Host "  时间       合计LAG      变化" -ForegroundColor DarkGray
    $prev = -1
    for ($i = 0; $i -lt $Times; $i++) {
        $sum = ((Get-LagRows -G $G) | Measure-Object -Property Lag -Sum).Sum
        if ($null -eq $sum) { $sum = 0 }
        $delta = if ($prev -lt 0) { "-" } else { "{0:+#;-#;0}" -f ($sum - $prev) }
        $color = if ($sum -eq 0) { "Green" } else { "Red" }
        Write-Host ("  {0}  {1,10}  {2,8}" -f (Get-Date -Format "HH:mm:ss"), $sum, $delta) -ForegroundColor $color
        $prev = $sum
        Start-Sleep -Seconds $IntervalSec
    }
}

# ---------------------------------------------------------------- 主题

function New-BenchTopic {
    param([int]$P)
    Write-Host "`n=== 重建测试主题: $Topic（$P 分区）===" -ForegroundColor Cyan
    Invoke-Kafka "kafka-topics.sh" @("--delete", "--topic", $Topic) | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Kafka "kafka-topics.sh" @("--create", "--topic", $Topic, "--partitions", "$P", "--replication-factor", "1") | Out-Null
    Write-Host "  已创建" -ForegroundColor Green
}

# ---------------------------------------------------------------- 吞吐测量

function Invoke-ProducerPerf {
    $props = "bootstrap.servers=$Bootstrap", "acks=$Acks", "linger.ms=$LingerMs", "compression.type=$Compression"
    $out = docker exec $Container "$BIN/kafka-producer-perf-test.sh" --topic $Topic --num-records $Records --record-size $RecordSize --throughput $Throughput --producer-props @props 2>&1
    $text = ($out | Out-String)

    $r = @{ rec_per_sec = ""; mb_per_sec = ""; avg_ms = ""; p50 = ""; p95 = ""; p99 = "" }
    if ($text -match '([\d.]+)\s+records/sec')        { $r.rec_per_sec = $Matches[1] }
    if ($text -match '\(([\d.]+)\s+MB/sec\)')         { $r.mb_per_sec  = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+avg\s+latency') { $r.avg_ms     = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+50th')          { $r.p50        = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+95th')          { $r.p95        = $Matches[1] }
    if ($text -match '([\d.]+)\s+ms\s+99th')          { $r.p99        = $Matches[1] }

    Write-Host ("  生产吞吐 {0} 条/秒   带宽 {1} MB/s   p95 {2} ms   p99 {3} ms" -f $r.rec_per_sec, $r.mb_per_sec, $r.p95, $r.p99) -ForegroundColor Yellow
    if (-not $r.rec_per_sec) {
        Write-Host "  [解析失败] 原始输出（格式可能因 Kafka 版本而异）:" -ForegroundColor Red
        $out | Select-Object -Last 3 | ForEach-Object { Write-Host "    $_" }
    }
    return $r
}

function Invoke-ConsumerPerf {
    # 注意：Kafka 4.x 已把 --messages 更名为 --num-records（旧名只打 deprecated 警告）
    $out = docker exec $Container "$BIN/kafka-consumer-perf-test.sh" --bootstrap-server $Bootstrap --topic $Topic --num-records $Records --group $Group --timeout 120000 2>&1
    $r = @{ rec_per_sec = ""; mb_per_sec = ""; rebalance_ms = ""; fetch_ms = "" }
    foreach ($line in $out) {
        if ($line -match '^\d{4}-\d{2}-\d{2}') {
            $f = $line -split ','
            # 输出列（0-based）：
            #   0 start.time        1 end.time              2 data.consumed.in.MB   3 MB.sec
            #   4 data.consumed.nMsg 5 nMsg.sec          ★  6 rebalance.time.ms     7 fetch.time.ms
            #   8 fetch.MB.sec   ★  9 fetch.nMsg.sec     ★
            #
            # ★ 必须取 fetch.*（第 9 / 8 列），不能取第 5 / 3 列。
            #   第 5 列 nMsg.sec 的分母是【整个窗口】，其中包含消费者入组 + 分区分配的
            #   rebalance 时间。实测本机 rebalance 固定约 3.2 秒，而 20 万条的真实读取
            #   只需约 0.18 秒 —— 取第 5 列会把消费吞吐压低近 20 倍，且【消息越少低估
            #   越狠】，导致不同 -Records 值之间根本不可比（20 万 vs 200 万曾差出 7.6 倍，
            #   全部是这一个除法伪影）。
            if ($f.Count -ge 10) {
                $r.mb_per_sec   = $f[8].Trim()   # fetch.MB.sec
                $r.rec_per_sec  = $f[9].Trim()   # fetch.nMsg.sec
                $r.rebalance_ms = $f[6].Trim()   # rebalance.time.ms（固定开销）
                $r.fetch_ms     = $f[7].Trim()   # fetch.time.ms（真实读取）
            }
        }
    }
    Write-Host ("  消费吞吐 {0} 条/秒   带宽 {1} MB/s   （fetch 口径，已排除 rebalance）" -f $r.rec_per_sec, $r.mb_per_sec) -ForegroundColor Yellow
    if ($r.rebalance_ms -and $r.fetch_ms -and ([int]$r.rebalance_ms + [int]$r.fetch_ms) -gt 0) {
        $overhead = [math]::Round(100 * [int]$r.rebalance_ms / ([int]$r.rebalance_ms + [int]$r.fetch_ms))
        Write-Host ("  rebalance {0} ms / 真实读取 {1} ms —— 固定开销占窗口 {2}%" -f $r.rebalance_ms, $r.fetch_ms, $overhead) -ForegroundColor DarkGray
        if ($overhead -ge 50) {
            Write-Host "  ⚠ 固定开销过半，说明 -Records 偏小（建议 ≥ 3000000），本次消费数字仅供参考" -ForegroundColor DarkYellow
        }
    }
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
    if (-not (Test-Path $OutFile)) { $CSV_HEADER | Set-Content -Path $OutFile -Encoding UTF8 }
    $row = @((Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $P, $Records, $RecordSize, $Acks, $LingerMs, $Compression, $Produce.rec_per_sec, $Produce.mb_per_sec, $Produce.avg_ms, $Produce.p50, $Produce.p95, $Produce.p99, $Consume.rec_per_sec, $Consume.mb_per_sec) -join ","
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
    Write-Host ("  分区数批量对照：{0}" -f ($SweepPartitions -join ' / ')) -ForegroundColor Cyan
    Write-Host "  预期：生产吞吐几乎不变，消费吞吐随分区数近似线性增长" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    $results = @()
    foreach ($p in $SweepPartitions) {
        Write-Host "`n[分区数 = $p]" -ForegroundColor Green
        $results += Invoke-OneRound -P $p
    }

    Write-Host "`n`n=== 对照结果 ===" -ForegroundColor Cyan
    Write-Host ("{0,8}  {1,14}  {2,12}  {3,10}  {4,14}  {5,12}" -f "分区数", "生产(条/秒)", "生产(MB/s)", "p95(ms)", "消费(条/秒)", "消费(MB/s)")
    Write-Host ("-" * 78)
    foreach ($r in $results) {
        Write-Host ("{0,8}  {1,14}  {2,12}  {3,10}  {4,14}  {5,12}" -f $r.P, $r.Prod.rec_per_sec, $r.Prod.mb_per_sec, $r.Prod.p95, $r.Cons.rec_per_sec, $r.Cons.mb_per_sec)
    }
    Write-Host ""
    Write-Host "  读法：" -ForegroundColor DarkGray
    Write-Host "    · 生产吞吐基本不随分区数变化 => Kafka 写入靠顺序追加" -ForegroundColor DarkGray
    Write-Host "    · 消费吞吐随分区数增长      => 分区数 = 消费并行度的硬上限" -ForegroundColor DarkGray
    Write-Host "    · 若消费吞吐不涨，检查消费端 concurrency 是否同步调大" -ForegroundColor DarkGray
    if ($OutFile) { Write-Host "`n  原始数据已写入 $OutFile" -ForegroundColor Green }
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

Show-AvailableScripts
Show-AllTopics
# 注意：主题重建已收敛到 Invoke-OneRound 内部（每轮都必须重建，否则消费组位点会残留）。
# 这里原先还有一次 `if ($Recreate) { New-BenchTopic ... }`，与 Invoke-OneRound 的首行重复，
# 导致带 -Recreate 时主题被删了建、建了又删再建 —— 已删除。
# -Recreate 现为兼容保留参数（不再有额外作用），旧的命令行写法照常可用。
Invoke-OneRound -P $Partitions | Out-Null
Show-AllGroupsLag

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  下一步：改变量做对照" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  1) 分区对照：   .\kafka-bench.ps1 -Sweep -Records 3000000 -OutFile sweep.csv"
Write-Host "  2) 生产者参数： .\kafka-bench.ps1 -Records 3000000 -LingerMs 20 -Compression lz4"
Write-Host "  3) 可靠性对照： .\kafka-bench.ps1 -Records 3000000 -Acks 1"
Write-Host "  4) 业务 LAG：   .\kafka-bench.ps1 -WatchLag -Topic topic_device_gps -Group platform-service-group"
Write-Host "  （-Records 别低于 3000000，否则消费端固定开销会淹没结果）" -ForegroundColor DarkGray
Write-Host ""
