# ============================================================
#  collect-stats.ps1  -- resource sampler for capacity experiments
#
#  Samples `docker stats` + Kafka consumer LAG at a fixed interval and
#  appends the result to CSV files, so you never have to copy numbers
#  by hand. One run per experiment; pass a different -Tag each time.
#
#  Usage (PowerShell, run from the deploy\test folder):
#     .\collect-stats.ps1 -Tag "B1-500msg" -DurationSec 300
#     .\collect-stats.ps1 -Tag "C2-100dev" -IntervalSec 5 -DurationSec 180
#
#  Output (under deploy\test\results\):
#     stats-<Tag>.csv   per-container CPU / memory / IO
#     lag-<Tag>.csv     per-topic consumer LAG
#
#  NOTE: comments/strings are ASCII on purpose -- PowerShell 5.1 reads
#  scripts as ANSI unless they carry a BOM, which would garble Chinese.
# ============================================================

param(
    [Parameter(Mandatory = $true)]
    [string] $Tag,                          # experiment label, e.g. "B1-500msg"

    [int] $IntervalSec = 5,                 # sampling period
    [int] $DurationSec = 300,               # total sampling time

    [string] $OutDir = ""
)

# NOTE: do NOT set $ErrorActionPreference = "Stop" here.
# PowerShell 5.1 promotes ANY stderr output from a native command (docker)
# into a terminating error, which would abort the loop on the first sample.

# $PSScriptRoot is not reliably populated when the script is started via
# -File, so resolve the default output dir here in the body instead.
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
    $OutDir = Join-Path $scriptDir "results"
}

# ---------- resolve docker exec target for Kafka ----------
$KAFKA_CONTAINER = "kafka"
$KAFKA_BIN       = "/opt/kafka/bin"

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$statsFile = Join-Path $OutDir "stats-$Tag.csv"
$lagFile   = Join-Path $OutDir "lag-$Tag.csv"

# ---------- write CSV headers (overwrite: one file per experiment tag) ----------
"timestamp,container,cpu_percent,mem_used_mib,mem_limit_mib,mem_percent,net_io,block_io" |
    Set-Content -Path $statsFile -Encoding UTF8
"timestamp,topic,partition,current_offset,log_end_offset,lag" |
    Set-Content -Path $lagFile -Encoding UTF8

Write-Host ""
Write-Host "=== collect-stats : $Tag ===" -ForegroundColor Cyan
Write-Host "  interval : ${IntervalSec}s"
Write-Host "  duration : ${DurationSec}s  ($([math]::Ceiling($DurationSec / $IntervalSec)) samples)"
Write-Host "  stats    : $statsFile"
Write-Host "  lag      : $lagFile"
Write-Host ""
Write-Host "  Press Ctrl+C to stop early." -ForegroundColor DarkGray
Write-Host ""

$elapsed   = 0
$sampleNo  = 0

while ($elapsed -lt $DurationSec) {

    $ts = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $sampleNo++

    # ---------- 1) docker stats ----------
    # --no-stream = take one snapshot and exit (otherwise it streams forever)
    $rows = docker stats --no-stream --format "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}" 2>$null

    foreach ($row in $rows) {
        $p = $row -split '\|'
        if ($p.Count -lt 6) { continue }

        # MemUsage looks like "373.7MiB / 7.639GiB" -> split into used / limit
        $memParts = $p[2] -split '/'
        $memUsed  = ($memParts[0] -replace '[^0-9\.]', '')
        $memLimit = if ($memParts.Count -gt 1) { ($memParts[1] -replace '[^0-9\.]', '') } else { "" }

        # normalise GiB -> MiB so the column is always MiB
        if ($memParts[0] -match 'GiB') { $memUsed  = [math]::Round([double]$memUsed  * 1024, 1) }
        if ($memParts.Count -gt 1 -and $memParts[1] -match 'GiB') {
            $memLimit = [math]::Round([double]$memLimit * 1024, 1)
        }

        "$ts,$($p[0]),$($p[1] -replace '%',''),$memUsed,$memLimit,$($p[3] -replace '%',''),$($p[4]),$($p[5])" |
            Add-Content -Path $statsFile -Encoding UTF8
    }

    # ---------- 2) Kafka consumer LAG ----------
    # MSYS_NO_PATHCONV guards against Git Bash rewriting the container path
    # @() wrapper: a single-line result would otherwise come back as a bare
    # string and foreach would iterate its characters instead of lines.
    $lagOut = @(docker exec $KAFKA_CONTAINER $KAFKA_BIN/kafka-consumer-groups.sh `
                --bootstrap-server localhost:9092 --describe --all-groups 2>$null)

    foreach ($line in $lagOut) {
        # data rows look like:
        #   platform-service-group topic_device_gps 0 18788 18788 0 consumer-... /172.18.0.8 consumer-...
        $f = ($line -split '\s+' | Where-Object { $_ -ne '' })
        if ($f.Count -ge 6 -and $f[2] -match '^\d+$' -and $f[5] -match '^-?\d+$') {
            "$ts,$($f[1]),$($f[2]),$($f[3]),$($f[4]),$($f[5])" |
                Add-Content -Path $lagFile -Encoding UTF8
        }
    }

    Write-Host ("  [{0,3}] {1}  sample #{2}" -f $elapsed, $ts, $sampleNo) -ForegroundColor DarkGray

    Start-Sleep -Seconds $IntervalSec
    $elapsed += $IntervalSec
}

Write-Host ""
Write-Host "Done. $sampleNo samples written." -ForegroundColor Green
Write-Host "  stats : $statsFile"
Write-Host "  lag   : $lagFile"
Write-Host ""
Write-Host "Quick look (average CPU% and peak memory per container):"
Import-Csv $statsFile |
    Group-Object container |
    ForEach-Object {
        $cpu  = ($_.Group | Measure-Object -Property cpu_percent -Average).Average
        $mem  = ($_.Group | Measure-Object -Property mem_used_mib -Maximum).Maximum
        [PSCustomObject]@{
            container = $_.Name
            avg_cpu   = [math]::Round($cpu, 2)
            peak_mem  = $mem
        }
    } | Sort-Object -Property peak_mem -Descending | Format-Table -AutoSize

Write-Host "Peak LAG per topic:"
Import-Csv $lagFile |
    Group-Object topic |
    ForEach-Object {
        $max = ($_.Group | Measure-Object -Property lag -Maximum).Maximum
        [PSCustomObject]@{ topic = $_.Name; peak_lag = $max }
    } | Sort-Object -Property peak_lag -Descending | Format-Table -AutoSize
