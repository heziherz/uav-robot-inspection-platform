# ============================================================
#  alter-topics.ps1  -- raise the partition count of business topics
#
#  WHY: 分区数 = 消费并行度的上限。
#       A topic with 1 partition can only be consumed by ONE consumer in the
#       group -- adding backend instances or raising listener concurrency has
#       no effect on that topic.
#
#  NOTE: Kafka partitions can only be INCREASED, never decreased.
#        (Decreasing would require re-routing data; Kafka does not support it.)
#        To go back to 1 partition you must delete and recreate the topic.
#
#  Usage (PowerShell, from the deploy folder):
#     .\init\alter-topics.ps1              # raise all topics to 3 partitions
#     .\init\alter-topics.ps1 -Partitions 6
#     .\init\alter-topics.ps1 -Show        # only print the current layout
#
#  The 7th topic (topic_platform_command) is downstream and already has 3.
#  ASCII-only strings on purpose (PowerShell 5.1 ANSI/BOM issue).
# ============================================================

param(
    [int]    $Partitions = 3,
    [switch] $Show
)

$KAFKA_CONTAINER = "kafka"
$KAFKA_BIN       = "/opt/kafka/bin"
$BOOTSTRAP       = "localhost:9092"

# the 6 upstream topics
$TOPICS = @(
    "topic_device_heartbeat",
    "topic_device_gps",
    "topic_device_sensor",
    "topic_device_media_meta",
    "topic_device_alarm",
    "topic_device_task_ack"
)

function Get-TopicLayout {
    docker exec $KAFKA_CONTAINER $KAFKA_BIN/kafka-topics.sh `
        --bootstrap-server $BOOTSTRAP --describe |
        Select-String -Pattern "^\s*Topic:\s+(\S+)\s+TopicId.*PartitionCount:\s+(\d+)" |
        ForEach-Object {
            $m = [regex]::Match($_.Line, "Topic:\s+(\S+)\s+TopicId.*PartitionCount:\s+(\d+)")
            if ($m.Success) {
                [PSCustomObject]@{ Topic = $m.Groups[1].Value; Partitions = [int]$m.Groups[2].Value }
            }
        } | Where-Object { $_.Topic -notlike "__*" } | Sort-Object Topic
}

Write-Host ""
Write-Host "=== Current topic layout ===" -ForegroundColor Cyan
Get-TopicLayout | Format-Table -AutoSize

if ($Show) { return }

Write-Host "=== Raising upstream topics to $Partitions partitions ===" -ForegroundColor Cyan
foreach ($t in $TOPICS) {
    Write-Host ("  altering {0} ..." -f $t) -NoNewline
    $out = docker exec $KAFKA_CONTAINER $KAFKA_BIN/kafka-topics.sh `
        --bootstrap-server $BOOTSTRAP --alter --topic $t --partitions $Partitions 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host " ok" -ForegroundColor Green
    } else {
        # Already at that count -> Kafka errors with "topic already has N partitions".
        Write-Host (" skipped ({0})" -f ($out -join ' ')) -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== New topic layout ===" -ForegroundColor Cyan
Get-TopicLayout | Format-Table -AutoSize

Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Make sure spring.kafka.listener.concurrency matches the partition count"
Write-Host "     (platform-service/src/main/resources/application.properties)"
Write-Host "  2. Recreate the backend containers:"
Write-Host "     docker compose up -d --force-recreate platform-service-1 platform-service-2"
Write-Host ""
