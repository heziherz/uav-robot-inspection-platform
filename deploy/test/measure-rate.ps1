# ============================================================
#  measure-rate.ps1  -- end-to-end message rate meter
#
#  Reads every topic's end offset twice, N seconds apart, and prints the
#  message rate per topic plus the system total. This is THE number you
#  compare across experiment steps:
#     "6 devices -> 5.3 msg/s"  ...  "500 devices -> 430 msg/s"
#
#  Usage (PowerShell, from deploy\test):
#     .\measure-rate.ps1                      # 20s window
#     .\measure-rate.ps1 -WindowSec 60        # longer window = steadier number
#
#  Run it while the simulator is running. If it reports 0 msg/s,
#  either the simulator is stopped or it cannot reach Kafka.
#
#  ASCII-only comments/strings on purpose (PS 5.1 ANSI/BOM issue).
# ============================================================

param(
    [int] $WindowSec = 20
)

$KAFKA_CONTAINER = "kafka"
$KAFKA_BIN       = "/opt/kafka/bin"

# the 6 upstream topics (topic_platform_command is downstream, not counted)
$TOPICS = @(
    "topic_device_heartbeat",
    "topic_device_gps",
    "topic_device_sensor",
    "topic_device_media_meta",
    "topic_device_alarm",
    "topic_device_task_ack"
)

function Get-TopicOffsets {
    $map = @{}
    foreach ($t in $TOPICS) {
        $out = @(docker exec $KAFKA_CONTAINER $KAFKA_BIN/kafka-get-offsets.sh `
                    --bootstrap-server localhost:9092 --topic $t 2>$null)
        $sum = 0
        foreach ($line in $out) {
            # output shape: topic:partition:offset
            $p = $line -split ':'
            if ($p.Count -ge 3 -and $p[2] -match '^\d+$') { $sum += [long]$p[2] }
        }
        $map[$t] = $sum
    }
    return $map
}

Write-Host ""
Write-Host "=== measure-rate : ${WindowSec}s window ===" -ForegroundColor Cyan
Write-Host "sampling T0 ..." -ForegroundColor DarkGray
$t0 = Get-TopicOffsets

Start-Sleep -Seconds $WindowSec

Write-Host "sampling T1 ..." -ForegroundColor DarkGray
$t1 = Get-TopicOffsets

Write-Host ""
$totalDelta = 0

"{0,-28} {1,10} {2,12}" -f "topic", "delta", "msg/s"
"-" * 54

foreach ($t in $TOPICS) {
    $delta = $t1[$t] - $t0[$t]
    $totalDelta += $delta
    "{0,-28} {1,10} {2,12}" -f $t, $delta, [math]::Round($delta / $WindowSec, 2)
}

"-" * 54
"{0,-28} {1,10} {2,12}" -f "TOTAL", $totalDelta, [math]::Round($totalDelta / $WindowSec, 2)

Write-Host ""
Write-Host ("System total: {0} msg/s  ({1} msg/min, {2} msg/hour)" -f `
    [math]::Round($totalDelta / $WindowSec, 2), `
    [math]::Round($totalDelta * 60 / $WindowSec, 0), `
    [math]::Round($totalDelta * 3600 / $WindowSec, 0))
Write-Host ""
Write-Host "Reminder: total msg/s / devices = per-device rate."
Write-Host "Theoretical per-device rate for this simulator is about 0.9 msg/s."
Write-Host ""
