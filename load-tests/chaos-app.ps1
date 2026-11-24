# Chaos experiment 2: kill the app in the middle of checkouts.  See docs/chaos.md.
#
#   .\load-tests\chaos-app.ps1
#
# The app container is recreated with three overrides for THIS experiment only (they are not defaults):
#   PAYMENT_FAKE_LATENCY_MS=5000     every payment takes 5 s, so each in-flight checkout holds a RESERVED order
#   CHECKOUT_RESERVATION_TTL=30s     a reservation is stale after 30 s
#   CHECKOUT_EXPIRY_INTERVAL=5s      the expiry job runs every 5 s
# 15 s after the first checkout has COMPLETED (k6 writes a row when a request finishes, and each takes 5 s, so this is about
# 20 s into the timed run, when every VU is holding one RESERVED order): docker compose kill app. The script records how many orders are RESERVED and how
# much stock is reserved, starts the app again, and polls the database every second until nothing is RESERVED / PAID.
# The overrides stay on the container until the next `docker compose up` (which sets it back to the defaults).
[CmdletBinding()]
param(
    [int]$Vus = 10,
    [int]$DurationSec = 30,
    [int]$KillAtSec = 15,
    [int]$Items = 20,
    [int]$Stock = 1000,
    [int]$RecoveryTimeoutSec = 900,
    [string]$Label = 'chaos-app',
    [string]$K6BaseUrl = 'http://host.docker.internal:8081',
    [string]$HostBaseUrl = 'http://localhost:8081'
)
$ErrorActionPreference = 'Continue'   # docker writes progress to stderr; failures are checked explicitly
. "$PSScriptRoot\lib\chaos-common.ps1"
Set-Location (Split-Path $PSScriptRoot -Parent)

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sub = "$Label-$stamp"
$dir = Join-Path $PSScriptRoot "results/$sub"
New-Item -ItemType Directory -Force $dir | Out-Null
$csv = Join-Path $dir 'requests.csv'

Write-Host "== Starting the stack; the app is (re)created when its overrides differ from the running container"
$env:PAYMENT_FAKE_LATENCY_MS = '5000'
$env:CHECKOUT_RESERVATION_TTL = '30s'
$env:CHECKOUT_EXPIRY_INTERVAL = '5s'
docker compose --profile app up -d 2>&1 | Out-Null
$ov = docker exec oae-app printenv PAYMENT_FAKE_LATENCY_MS CHECKOUT_RESERVATION_TTL CHECKOUT_EXPIRY_INTERVAL
Write-Host "   container env: $($ov -join ' / ')"
if (-not (Wait-Healthy 'oae-app' 900)) { throw 'oae-app is not healthy' }

$before = Get-ItemIds
Write-Host "== Starting k6 ($Vus VUs, checkout only)"
$k6 = Start-K6 'checkout.js' $sub @{ BASE_URL = $K6BaseUrl; VUS = $Vus; DURATION = "${DurationSec}s"; ITEMS = $Items; STOCK = $Stock; SAVE_RESULT = 'false' }
$firstRequestMs = Wait-FirstRequest $csv '/api/checkout'
$runIds = @(Get-ItemIds | Where-Object { $before -notcontains $_ })
$list = ConvertTo-SqlList $runIds
Write-Host ("   first checkout request at {0}; {1} items" -f (Format-Epoch $firstRequestMs), $runIds.Count)

Sleep-Until ($firstRequestMs + $KillAtSec * 1000)
$killMs = Get-EpochMs
docker compose kill app 2>&1 | Out-Null
Write-Host ("== t={0}s  docker compose kill app" -f [math]::Round(($killMs - $firstRequestMs) / 1000, 1))

# State at the moment of the kill
$statusAtKill = @(Invoke-Sql "SELECT status || '=' || count(*) FROM orders WHERE item_id IN ($list) GROUP BY status ORDER BY status")
$stockAtKill = (Invoke-Sql "SELECT COALESCE(sum(quantity_reserved),0), COALESCE(sum(quantity_available),0) FROM inventory_item WHERE item_id IN ($list)") -split "`t"
$stuck = @(Invoke-Sql "SELECT order_id, status, quantity_requested FROM orders WHERE item_id IN ($list) AND status IN ('RESERVED','PAID') ORDER BY created_at")
$stuckIds = @($stuck | ForEach-Object { ($_ -split "`t")[0] })
$reservedAtKill = @($stuck | Where-Object { ($_ -split "`t")[1] -eq 'RESERVED' }).Count
$paidAtKill = @($stuck | Where-Object { ($_ -split "`t")[1] -eq 'PAID' }).Count
Write-Host ("   at the kill: RESERVED={0} PAID={1}, reserved stock={2}, available={3}" -f $reservedAtKill, $paidAtKill, $stockAtKill[0], $stockAtKill[1])
$stuck | Set-Content (Join-Path $dir 'orders-at-kill.tsv') -Encoding utf8

docker compose start app 2>&1 | Out-Null
$startMs = Get-EpochMs
Write-Host "== app started again; polling the database every second"

# Poll until no RESERVED / PAID order and no reserved stock is left
$samples = New-Object System.Collections.Generic.List[string]
$samples.Add('epoch_ms,t_since_kill_s,reserved_orders,paid_orders,reserved_stock,app_health')
$healthyMs = $null; $zeroMs = $null
$deadline = (Get-Date).AddSeconds($RecoveryTimeoutSec)
while ((Get-Date) -lt $deadline) {
    $now = Get-EpochMs
    $row = (Invoke-Sql "SELECT (SELECT count(*) FROM orders WHERE item_id IN ($list) AND status='RESERVED'), (SELECT count(*) FROM orders WHERE item_id IN ($list) AND status='PAID'), (SELECT COALESCE(sum(quantity_reserved),0) FROM inventory_item WHERE item_id IN ($list))") -split "`t"
    $health = (docker inspect -f '{{.State.Health.Status}}' oae-app 2>$null)
    if ($null -eq $healthyMs -and $health -eq 'healthy') { $healthyMs = $now }
    $samples.Add(('{0},{1:N1},{2},{3},{4},{5}' -f $now, (($now - $killMs) / 1000), $row[0], $row[1], $row[2], $health))
    if ([int]$row[0] -eq 0 -and [int]$row[1] -eq 0 -and [int]$row[2] -eq 0) { $zeroMs = $now; break }
    Start-Sleep -Milliseconds 700
}
$samples | Set-Content (Join-Path $dir 'recovery.csv') -Encoding utf8
$k6.WaitForExit()
$endMs = Get-EpochMs

# After recovery
$idList = ($stuckIds | ForEach-Object { "'$_'" }) -join ','
$fate = if ($stuckIds.Count -gt 0) { @(Invoke-Sql "SELECT status || '=' || count(*) FROM orders WHERE order_id IN ($idList) GROUP BY status ORDER BY status") } else { @() }
$statusAfter = @(Invoke-Sql "SELECT status || '=' || count(*) FROM orders WHERE item_id IN ($list) GROUP BY status ORDER BY status")
$inv = (Invoke-Sql @"
WITH alloc AS (
  SELECT item_id, COALESCE(SUM(quantity_requested) FILTER (WHERE status IN ('ALLOCATED','CONFIRMED')), 0) AS units
  FROM orders WHERE item_id IN ($list) GROUP BY item_id)
SELECT count(*),
       count(*) FILTER (WHERE i.quantity_available < 0 OR i.quantity_reserved < 0),
       count(*) FILTER (WHERE i.quantity_available + i.quantity_reserved + COALESCE(a.units, 0) <> $Stock),
       COALESCE(sum(i.quantity_available), 0), COALESCE(sum(i.quantity_reserved), 0), COALESCE(sum(a.units), 0)
FROM inventory_item i LEFT JOIN alloc a USING (item_id) WHERE i.item_id IN ($list);
"@) -split "`t"

# What k6 saw
$k6Rows = @(Import-Csv $csv | Where-Object { $_.metric_name -eq 'http_req_duration' -and $_.url -match '/api/checkout' })
$k6Status = ($k6Rows | Group-Object status | Sort-Object { [int]$_.Name } | ForEach-Object { "$($_.Name): $($_.Count)" }) -join ', '

$meta = [ordered]@{
    label = $Label; vus = $Vus; items = $Items; stockPerItem = $Stock
    overrides = [ordered]@{ PAYMENT_FAKE_LATENCY_MS = 5000; CHECKOUT_RESERVATION_TTL = '30s'; CHECKOUT_EXPIRY_INTERVAL = '5s' }
    firstRequestMs = $firstRequestMs; killMs = $killMs; appStartMs = $startMs; appHealthyMs = $healthyMs; reservedZeroMs = $zeroMs; endMs = $endMs
    atKill = [ordered]@{ reservedOrders = $reservedAtKill; paidOrders = $paidAtKill; reservedStock = [int]$stockAtKill[0]; availableStock = [int]$stockAtKill[1]; ordersByStatus = $statusAtKill }
    fateOfOrdersStuckAtKill = $fate
    ordersByStatusAfter = $statusAfter
    invariant = [ordered]@{ items = [int]$inv[0]; negativeStockItems = [int]$inv[1]; itemsWhereSumNotEqualInitial = [int]$inv[2]
                            sumAvailable = [long]$inv[3]; sumReserved = [long]$inv[4]; sumAllocatedOrConfirmed = [long]$inv[5] }
    k6StatusCounts = $k6Status
    grafanaUrl = (Get-GrafanaUrl $firstRequestMs $endMs)
}
$meta | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir 'meta.json') -Encoding utf8

$out = New-Object System.Collections.Generic.List[string]
$out.Add("# Chaos run summary: $Label")
$out.Add('')
$out.Add("- Load: $Vus VUs for ${DurationSec}s, checkout only, $Items items x $Stock stock. Overrides: latency 5000 ms, TTL 30 s, expiry every 5 s")
$out.Add(('- First request {0}; kill at t={1:N1} s' -f (Format-Epoch $firstRequestMs), (($killMs - $firstRequestMs) / 1000)))
$out.Add("- k6 saw: $k6Status   (0 = connection error after the kill)")
$out.Add("- At the kill: RESERVED=$reservedAtKill, PAID=$paidAtKill, reserved stock=$($stockAtKill[0]) units")
$healthyText = if ($healthyMs) { '{0:N1}' -f (($healthyMs - $killMs) / 1000) } else { 'never' }
$out.Add(('- App start issued {0:N1} s after the kill; container healthy {1} s after the kill' -f (($startMs - $killMs) / 1000), $healthyText))
if ($zeroMs) {
    $extra = if ($healthyMs) { ', {0:N1} s after the container reported healthy' -f (($zeroMs - $healthyMs) / 1000) } else { '' }
    $out.Add(('- **RESERVED reached 0** {0:N1} s after the kill = {1:N1} s after the app start command{2}' -f (($zeroMs - $killMs) / 1000), (($zeroMs - $startMs) / 1000), $extra))
} else { $out.Add("- **RESERVED did NOT reach 0 within ${RecoveryTimeoutSec}s**") }
$out.Add("- Fate of the $($stuckIds.Count) orders that were RESERVED/PAID at the kill: $(@($fate) -join ', ')")
$out.Add("- Orders by status after recovery: $(@($statusAfter) -join ', ')")
$out.Add("- Invariants: $($inv[0]) items; negative stock: $($inv[1]); items where available + reserved + allocated/confirmed != $Stock : $($inv[2]); available $($inv[3]) + reserved $($inv[4]) + allocated/confirmed $($inv[5]) = $([long]$inv[3] + [long]$inv[4] + [long]$inv[5]) (initial $([int]$inv[0] * $Stock))")
$out | Set-Content (Join-Path $dir 'summary.md') -Encoding utf8
$out | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host "Results in $dir"
Write-Host "Grafana (whole run +/- 30 s): $($meta.grafanaUrl)"

