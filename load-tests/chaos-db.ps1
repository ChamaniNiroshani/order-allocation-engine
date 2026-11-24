# Chaos experiment 1: kill PostgreSQL during load.  See docs/chaos.md.
#
#   .\load-tests\chaos-db.ps1                      # 60 s of spread.js with 20 VUs; Postgres is stopped at t=30 s for 20 s
#
# Needs the compose stack:  docker compose --profile app up -d --build
# Writes everything to load-tests/results/chaos-db-<timestamp>/ (git-ignored): requests.csv (one row per k6 metric
# point, with timestamp and status), probes.jsonl (what a client received), app.log, meta.json, summary.md.
[CmdletBinding()]
param(
    [int]$Vus = 20,
    [int]$DurationSec = 60,
    [int]$KillAtSec = 30,        # seconds after the first request
    [int]$DownForSec = 20,       # how long Postgres stays stopped
    [int]$Items = 50,
    [int]$Stock = 1000,
    [int]$WarmupSec = 20,
    [string]$Label = 'chaos-db',
    [string]$K6BaseUrl = 'http://host.docker.internal:8081',   # the app as seen from the k6 container
    [string]$HostBaseUrl = 'http://localhost:8081'              # the app as seen from this machine
)
$ErrorActionPreference = 'Continue'   # docker writes progress to stderr; failures are checked explicitly
. "$PSScriptRoot\lib\chaos-common.ps1"
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sub = "$Label-$stamp"
$dir = Join-Path $PSScriptRoot "results/$sub"
New-Item -ItemType Directory -Force $dir | Out-Null
$csv = Join-Path $dir 'requests.csv'
$stopFile = Join-Path $dir 'stop-probes'

Write-Host "== Preflight: stack up and ready"
docker compose --profile app up -d 2>&1 | Out-Null
if (-not (Wait-Healthy 'oae-app' 600)) { throw 'oae-app is not healthy' }
if (-not (Wait-Healthy 'oae-postgres' 60)) { throw 'oae-postgres is not healthy' }

Write-Host "== Warm-up (${WarmupSec}s, not recorded)"
Invoke-Warmup 'spread.js' $K6BaseUrl $WarmupSec $Vus

# Items that exist BEFORE the run are not part of the invariant check
$before = Get-ItemIds
$probeItem = New-InventoryItem $HostBaseUrl $Stock       # the probe's own item (initial stock = $Stock like the k6 items)
$appStart = (Get-Date).ToUniversalTime().ToString('o')

# The probe job is started BEFORE k6: starting a PowerShell job takes seconds on a loaded machine, and it must not delay the timeline
Write-Host "== Starting the client probe and k6 ($Vus VUs, ${DurationSec}s)"
$probeJobs = Start-AllProbes $HostBaseUrl $probeItem '/api/orders' $dir $stopFile
$k6 = Start-K6 'spread.js' $sub @{ BASE_URL = $K6BaseUrl; VUS = $Vus; DURATION = "${DurationSec}s"; ITEMS = $Items; STOCK = $Stock; MODE = 'chaos'; SAVE_RESULT = 'false' }
$firstRequestMs = Wait-FirstRequest $csv '/api/orders'
Write-Host ("   first order request at {0}" -f (Format-Epoch $firstRequestMs))

$killAt = $firstRequestMs + $KillAtSec * 1000
Sleep-Until $killAt
$stopBegin = Get-EpochMs
Write-Host ("== t={0}s  docker compose stop postgres" -f [math]::Round(($stopBegin - $firstRequestMs) / 1000, 1))
docker compose stop postgres 2>&1 | Out-Null
$stopEnd = Get-EpochMs

Sleep-Until ($stopEnd + $DownForSec * 1000)
$startBegin = Get-EpochMs
Write-Host ("== t={0}s  docker compose start postgres" -f [math]::Round(($startBegin - $firstRequestMs) / 1000, 1))
docker compose start postgres 2>&1 | Out-Null
$startEnd = Get-EpochMs
$pgHealthy = Wait-Healthy 'oae-postgres' 60
$healthyMs = Get-EpochMs
Write-Host ("   postgres healthy={0} at t={1}s" -f $pgHealthy, [math]::Round(($healthyMs - $firstRequestMs) / 1000, 1))

Write-Host "== Waiting for k6 to finish"
$k6.WaitForExit()
$endMs = Get-EpochMs
Start-Sleep -Seconds 3                          # one more probe round after recovery
New-Item -ItemType File -Path $stopFile | Out-Null
Wait-Job $probeJobs -Timeout 130 | Out-Null
Remove-Job $probeJobs -Force

# What the app logged during the window
$sinceIso = [DateTimeOffset]::FromUnixTimeMilliseconds($firstRequestMs - 5000).UtcDateTime.ToString('o')
docker logs oae-app --since $sinceIso 2>&1 | Set-Content (Join-Path $dir 'app.log') -Encoding utf8
$log = Get-Content (Join-Path $dir 'app.log')
$logStats = [ordered]@{
    totalLines                = $log.Count
    errorLines                = @($log | Where-Object { $_ -match '\bERROR\b' }).Count
    warnLines                 = @($log | Where-Object { $_ -match '\bWARN\b' }).Count
    hikariTimeoutMessages     = @($log | Where-Object { $_ -match 'Connection is not available|request timed out' }).Count
    unhandledExceptionLines   = @($log | Where-Object { $_ -match 'Unhandled exception' }).Count
    stackTraceLines           = @($log | Where-Object { $_ -match '^\s+at ' }).Count
}

# Invariants and orphans (items created by this run only)
Write-Host "== Invariant check"
$runIds = @(Get-ItemIds | Where-Object { $before -notcontains $_ })
$list = ConvertTo-SqlList $runIds
$inv = Invoke-Sql @"
WITH alloc AS (
  SELECT item_id, COALESCE(SUM(quantity_requested) FILTER (WHERE status IN ('ALLOCATED','CONFIRMED')), 0) AS units
  FROM orders WHERE item_id IN ($list) GROUP BY item_id)
SELECT count(*),
       count(*) FILTER (WHERE i.quantity_available < 0 OR i.quantity_reserved < 0),
       count(*) FILTER (WHERE i.quantity_available + i.quantity_reserved + COALESCE(a.units, 0) <> $Stock),
       COALESCE(sum(i.quantity_available), 0), COALESCE(sum(i.quantity_reserved), 0), COALESCE(sum(a.units), 0)
FROM inventory_item i LEFT JOIN alloc a USING (item_id) WHERE i.item_id IN ($list);
"@
$f = ($inv -split "`t")
$orphans = @(Invoke-Sql "SELECT status || '=' || count(*) FROM orders WHERE item_id IN ($list) AND status IN ('PENDING','RESERVED','PAID') GROUP BY status")
$byStatus = @(Invoke-Sql "SELECT status || '=' || count(*) FROM orders WHERE item_id IN ($list) GROUP BY status ORDER BY status")

$meta = [ordered]@{
    label = $Label; vus = $Vus; durationSec = $DurationSec; items = $Items; stockPerItem = $Stock
    appContainerStartedBefore = $appStart
    firstRequestMs = $firstRequestMs
    postgresStopBeginMs = $stopBegin; postgresStopEndMs = $stopEnd
    postgresStartBeginMs = $startBegin; postgresStartEndMs = $startEnd; postgresHealthyMs = $healthyMs
    k6EndMs = $endMs
    grafanaUrl = (Get-GrafanaUrl $firstRequestMs $endMs)
    log = $logStats
    invariant = [ordered]@{ items = [int]$f[0]; negativeStockItems = [int]$f[1]; itemsWhereSumNotEqualInitial = [int]$f[2]
                            sumAvailable = [long]$f[3]; sumReserved = [long]$f[4]; sumAllocated = [long]$f[5] }
    orphanOrders = $orphans
    ordersByStatus = $byStatus
}
$meta | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir 'meta.json') -Encoding utf8

& "$PSScriptRoot\chaos-summary.ps1" -Dir $dir
Write-Host ""
Write-Host "Results in $dir"
Write-Host "Grafana (whole run +/- 30 s): $($meta.grafanaUrl)"
