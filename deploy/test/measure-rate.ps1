# ============================================================
#  measure-rate.ps1  -- 消息速率计（端到端）
#
#  原理：Kafka 的 offset 是单调递增的计数器，取两次 offset 求差 ÷ 时间 = 速率。
#        这比数日志行准确得多，也不受日志开关影响。
#
#  【两次采样，一次拿全】
#    kafka-get-offsets.sh 不带 --topic 时会一次列出【全部 topic 的 offset】。
#    若逐个 topic 调用（6 次 docker exec），每次约 1~2 秒，
#    会导致 T0 与 T1 对每个 topic 的实际间隔各不相同 —— 算出的速率必然失真。
#
#  【时间基准】
#    以「T0 采完的时刻」为起点，「T1 采集的时刻」为终点，
#    两者之差即真实间隔 —— 这样采样耗时不会污染分母。
#
#  用法（在 deploy\test 目录下）：
#     .\measure-rate.ps1                  # 20 秒窗口
#     .\measure-rate.ps1 -WindowSec 60    # 窗口越长数字越稳
#
#  仿真端没在跑时会报 0，这是正常的（说明脚本工作正常，只是没数据）。
#
#  【重要】本文件含中文，必须保存为 UTF-8【带 BOM】+ CRLF 换行。
# ============================================================

param(
    [int] $WindowSec = 20
)

$KAFKA_CONTAINER = "kafka"
$KAFKA_BIN       = "/opt/kafka/bin"
$BOOTSTRAP       = "localhost:9092"

# 6 个上行 topic（下行 topic_platform_command 不计入"设备产出"）
$UPSTREAM_TOPICS = @(
    "topic_device_heartbeat",
    "topic_device_gps",
    "topic_device_sensor",
    "topic_device_media_meta",
    "topic_device_alarm",
    "topic_device_task_ack"
)

# 一次调用取回全部 topic 的 offset 合计
function Get-AllOffsets {
    $map = @{}
    foreach ($t in $UPSTREAM_TOPICS) { $map[$t] = [long]0 }

    # 不带 --topic => 一次返回所有 topic
    $raw = @(docker exec $KAFKA_CONTAINER $KAFKA_BIN/kafka-get-offsets.sh --bootstrap-server $BOOTSTRAP 2>&1)
    # stderr 在 PowerShell 里会变成 ErrorRecord 对象，须过滤掉
    $lines = @($raw | Where-Object { $_ -is [string] })

    foreach ($line in $lines) {
        # 输出格式：topic:partition:offset
        $p = $line.Trim() -split ':'
        if ($p.Count -ge 3 -and $p[2] -match '^\d+$') {
            $topic = $p[0]
            if ($map.ContainsKey($topic)) {
                $map[$topic] = $map[$topic] + [long]$p[2]
            }
        }
    }
    return $map
}

Write-Host ""
Write-Host "=== measure-rate : ${WindowSec}s window ===" -ForegroundColor Cyan

# ---------- T0 ----------
Write-Host "sampling T0 ..." -ForegroundColor DarkGray
$t0    = Get-AllOffsets
$start = Get-Date                      # 时间基准：T0 采完的瞬间

Start-Sleep -Seconds $WindowSec

# ---------- T1 ----------
$elapsed = ((Get-Date) - $start).TotalSeconds
Write-Host "sampling T1 ..." -ForegroundColor DarkGray
$t1 = Get-AllOffsets

Write-Host ("  (实际窗口 {0:N1}s)" -f $elapsed) -ForegroundColor DarkGray
Write-Host ""

$totalDelta = 0
"{0,-28} {1,10} {2,12}" -f "topic", "delta", "msg/s"
"-" * 54

foreach ($t in $UPSTREAM_TOPICS) {
    $delta = $t1[$t] - $t0[$t]
    $totalDelta += $delta
    "{0,-28} {1,10} {2,12}" -f $t, $delta, [math]::Round($delta / $elapsed, 2)
}

"-" * 54
"{0,-28} {1,10} {2,12}" -f "TOTAL", $totalDelta, [math]::Round($totalDelta / $elapsed, 2)

Write-Host ""
Write-Host ("System total: {0} msg/s  ({1} msg/min, {2} msg/hour)" -f `
    [math]::Round($totalDelta / $elapsed, 2), `
    [math]::Round($totalDelta * 60 / $elapsed, 0), `
    [math]::Round($totalDelta * 3600 / $elapsed, 0))
Write-Host ""

Write-Host "理论参考（3 无人机 + 3 机器狗）:" -ForegroundColor DarkGray
Write-Host "  heartbeat 1.20 | gps 2.50 | sensor 1.00 | media_meta 0.55 | 合计约 5.25 msg/s" -ForegroundColor DarkGray
Write-Host "  若实测明显低于理论：检查仿真端是否满速、媒体上传是否失败（失败会跳过上报）" -ForegroundColor DarkGray
Write-Host ""
