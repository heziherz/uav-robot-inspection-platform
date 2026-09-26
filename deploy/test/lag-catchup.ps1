# ============================================================
#  lag-catchup.ps1  -- LAG 积压与追赶实验（全自动）
#
#  这个实验回答一个问题：
#      【消费端每秒最多能处理多少条消息？】
#
#  做法：
#     ① 停掉消费者      -> LAG 开始单调增长（增长速率 = 生产速率）
#     ② 攒到目标积压量   -> 记下峰值
#     ③ 重启消费者      -> LAG 开始回落
#     ④ 等 LAG 归零     -> 记下追平耗时
#     ⑤ 自动算出【追赶速率】
#
#  【为什么不能手工计时】
#     手工记时刻有两个误差源：① 反应时间 ② 时钟不统一。
#     本脚本用同一块时钟（Get-Date）记录全部时刻，误差在毫秒级。
#
#  【追赶速率的两种口径】
#     净追赶速率 = 积压峰值 / 追平耗时
#       —— 表面值：看起来消费者每秒消化了多少积压
#     真实处理速率 = 净追赶速率 + 生产速率
#       —— 追赶期间新消息仍在到达，消费者同时在处理两边
#         这才是"消费端的真实能力"，也是和吞吐压测可比的数字
#
#  用法（在 deploy\test 目录下）：
#     .\lag-catchup.ps1
#     .\lag-catchup.ps1 -Consumers platform-service-1     # 只停一个（观察能力减半）
#     .\lag-catchup.ps1 -TargetLag 1000                    # 攒更多积压
#     .\lag-catchup.ps1 -Topic topic_device_sensor
#
#  【注意】脚本会自动 stop/start 指定的容器，请确认容器名正确。
#
#  【重要】本文件含中文，须保存为 UTF-8【带 BOM】+ CRLF 换行。
# ============================================================

param(
    [string]   $Container  = "kafka",
    [string]   $Bootstrap  = "localhost:9092",
    [string]   $Topic      = "topic_device_gps",
    [string]   $Group      = "platform-service-group",
    [string[]] $Consumers  = @("platform-service-1", "platform-service-2"),
    [int]      $TargetLag  = 500,      # 积压达到这个数就重启消费者
    [int]      $MaxWaitSec = 300,      # 单阶段最长等待
    [int]      $PollSec    = 2         # 轮询间隔
)

$BIN = "/opt/kafka/bin"

# ---------------------------------------------------------------- LAG 读取

function Get-CurrentLag {
    $raw = @(docker exec $Container $BIN/kafka-consumer-groups.sh --bootstrap-server $Bootstrap --describe --group $Group 2>&1)
    $lines = @($raw | Where-Object { $_ -is [string] })
    $total = [long]0
    foreach ($line in $lines) {
        $f = ($line -split '\s+' | Where-Object { $_ -ne '' })
        if ($f.Count -ge 6 -and $f[2] -match '^\d+$' -and $f[5] -match '^-?\d+$') {
            $total += [long]$f[5]
        }
    }
    return $total
}

function Format-Elapsed {
    param([double]$Sec)
    if ($Sec -lt 60) { return ("{0:N1} 秒" -f $Sec) }
    return ("{0:N0} 分 {1:N0} 秒" -f [math]::Floor($Sec / 60), ($Sec % 60))
}

# ---------------------------------------------------------------- 主流程

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  LAG 积压与追赶实验（全自动计时）"
Write-Host ("  主题={0}  消费组={1}" -f $Topic, $Group)
Write-Host ("  消费者容器={0}" -f ($Consumers -join ' / '))
Write-Host "============================================================" -ForegroundColor Cyan

# ---------- 阶段 0：确认初始状态 ----------
$lag0 = Get-CurrentLag
Write-Host ("`n[阶段 0] 当前 LAG = {0}" -f $lag0) -ForegroundColor DarkGray
if ($lag0 -gt 0) {
    Write-Host "  提示：初始 LAG 不为 0，说明之前有积压未消化。本实验会从当前值开始计。" -ForegroundColor Yellow
}

# ---------- 阶段 1：停消费者，等积压 ----------
Write-Host "`n[阶段 1] 停止消费者，观察积压增长" -ForegroundColor Cyan
docker stop @Consumers 2>&1 | Out-Null
$tStop = Get-Date
Write-Host ("  {0}  已停掉 {1}" -f $tStop.ToString("HH:mm:ss"), ($Consumers -join ' / ')) -ForegroundColor DarkGray

$lagPeak = $lag0
$tPeak   = $tStop
$waited  = 0
while ($waited -lt $MaxWaitSec) {
    Start-Sleep -Seconds $PollSec
    $waited += $PollSec
    $lag = Get-CurrentLag
    if ($lag -gt $lagPeak) { $lagPeak = $lag; $tPeak = Get-Date }
    Write-Host ("  {0}  LAG = {1,6}" -f (Get-Date).ToString("HH:mm:ss"), $lag) -ForegroundColor DarkGray
    if ($lag -ge $TargetLag) { break }
}

$riseSec  = ($tPeak - $tStop).TotalSeconds
$riseRate = if ($riseSec -gt 0) { ($lagPeak - $lag0) / $riseSec } else { 0 }

Write-Host ("  -> 积压峰值 {0} 条，用时 {1}" -f $lagPeak, (Format-Elapsed $riseSec)) -ForegroundColor Yellow

# ---------- 阶段 2：重启消费者，等追平 ----------
Write-Host "`n[阶段 2] 重启消费者，观察追赶" -ForegroundColor Cyan
docker start @Consumers 2>&1 | Out-Null
$tRestart = Get-Date
Write-Host ("  {0}  已启动 {1}" -f $tRestart.ToString("HH:mm:ss"), ($Consumers -join ' / ')) -ForegroundColor DarkGray

$tZero  = $null
$lagNow = $lagPeak
$waited = 0
while ($waited -lt $MaxWaitSec) {
    Start-Sleep -Seconds $PollSec
    $waited += $PollSec
    $lagNow = Get-CurrentLag
    Write-Host ("  {0}  LAG = {1,6}" -f (Get-Date).ToString("HH:mm:ss"), $lagNow) -ForegroundColor DarkGray
    if ($lagNow -eq 0) { $tZero = Get-Date; break }
}

# ---------- 结果 ----------
Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  实验结果" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

if ($null -eq $tZero) {
    Write-Host ("  未在 {0} 秒内追平（当前 LAG = {1}）" -f $MaxWaitSec, $lagNow) -ForegroundColor Red
    Write-Host "  说明消费端处理能力明显不足，可加大 -MaxWaitSec 再试。" -ForegroundColor Red
    return
}

$catchSec      = ($tZero - $tRestart).TotalSeconds
$netCatchRate  = ($lagPeak - $lag0) / $catchSec          # 净追赶速率
$realRate      = $netCatchRate + $riseRate               # 真实处理速率（+ 期间的产出）

Write-Host ("  停止时刻            {0}" -f $tStop.ToString("HH:mm:ss"))
Write-Host ("  积压峰值            {0} 条    （{1}）" -f $lagPeak, $tPeak.ToString("HH:mm:ss"))
Write-Host ("  重启时刻            {0}" -f $tRestart.ToString("HH:mm:ss"))
Write-Host ("  追平时刻            {0}" -f $tZero.ToString("HH:mm:ss"))
Write-Host ""
Write-Host ("  积压速率            {0,8:N2} 条/秒   <- 等于生产速率" -f $riseRate) -ForegroundColor Yellow
Write-Host ("  追平耗时            {0,8}" -f (Format-Elapsed $catchSec))
Write-Host ("  净追赶速率          {0,8:N2} 条/秒   <- 表面值" -f $netCatchRate) -ForegroundColor Yellow
Write-Host ("  真实处理速率        {0,8:N2} 条/秒   <- 净追赶 + 期间新产出" -f $realRate) -ForegroundColor Green
Write-Host ""
Write-Host "  读法：" -ForegroundColor DarkGray
Write-Host "    · 真实处理速率 = 消费端每秒最多能消化多少条" -ForegroundColor DarkGray
Write-Host "    · 换 concurrency 再做一次，比值即并行度带来的收益" -ForegroundColor DarkGray
Write-Host "    · 若追平耗时明显变长，说明消费者没起来或分区分配异常" -ForegroundColor DarkGray
Write-Host ""
