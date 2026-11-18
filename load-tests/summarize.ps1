<#
.SYNOPSIS
  Summarizes a matrix run: per mode x scenario x VUs, the MEDIAN over the repeats.

.DESCRIPTION
  Reads <RunDir>\*.meta.json (written by run-matrix.ps1) and the k6 JSON each one points to.
  Default RunDir: the newest folder under load-tests/results. Writes summary.csv and summary.json next to the
  results and prints the table.

  throughput = order requests (k6 iterations) / configured duration, so it is not skewed by setup() time.
               It counts every request, including fast 503s; allocPerSec counts only 201 responses.
               throughputMin/Max show the spread over the repeats.
  Latency is k6's http_req_duration (it also contains the few setup requests).
  oversell   = SUM over the repeats of (items with negative stock + items where
               quantity_available + allocated != initial stock); must be 0.
#>
param(
    [string]$RunDir
)

$loadDir = $PSScriptRoot
if (-not $RunDir) {
    $latest = Get-ChildItem (Join-Path $loadDir 'results') -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { throw 'No results found under load-tests/results' }
    $RunDir = $latest.FullName
}
if (-not (Test-Path $RunDir)) { throw "Run folder not found: $RunDir" }

function Get-Median([double[]]$values) {
    if (-not $values -or $values.Count -eq 0) { return $null }
    $s = $values | Sort-Object
    $n = $s.Count
    if ($n % 2 -eq 1) { return [double]$s[[int](($n - 1) / 2)] }
    return ([double]$s[$n / 2 - 1] + [double]$s[$n / 2]) / 2
}

function Get-Count($metrics, [string]$name) {
    $m = $metrics.PSObject.Properties[$name]
    if ($m) { return [double]$m.Value.values.count }
    return 0
}

$rows = @()
foreach ($metaFile in Get-ChildItem $RunDir -Filter '*.meta.json') {
    $meta = Get-Content $metaFile.FullName -Raw | ConvertFrom-Json
    if (-not $meta.k6File) {
        Write-Warning "No k6 result for $($metaFile.Name) (k6 exit $($meta.k6ExitCode)), skipped"
        continue
    }
    $k6 = Get-Content (Join-Path $RunDir $meta.k6File) -Raw | ConvertFrom-Json
    $m = $k6.metrics
    $dur = $m.http_req_duration.values
    $rows += [pscustomobject]@{
        mode        = $meta.mode
        scenario    = $meta.scenario
        vus         = [int]$meta.vus
        throughput  = (Get-Count $m 'iterations') / [double]$meta.durationSeconds
        allocPerSec = (Get-Count $m 'orders_allocated') / [double]$meta.durationSeconds
        p50         = [double]$dur.med
        p95         = [double]$dur.'p(95)'
        p99         = [double]$dur.'p(99)'
        errorPct    = [double]$m.http_req_failed.values.rate * 100
        rejected409 = Get-Count $m 'orders_rejected'
        contention503 = Get-Count $m 'orders_contention'
        allocated   = Get-Count $m 'orders_allocated'
        retries     = [double]$meta.retries
        oversell    = [int]$meta.negativeStockRows + [int]$meta.invariantViolations
        allocDbDiff = [math]::Abs((Get-Count $m 'orders_allocated') - [double]$meta.allocationRows)
    }
}
if ($rows.Count -eq 0) { throw "No usable results in $RunDir" }

$summary = $rows | Group-Object mode, scenario, vus | ForEach-Object {
    $g = $_.Group
    [pscustomobject]@{
        mode          = $g[0].mode
        scenario      = $g[0].scenario
        vus           = $g[0].vus
        repeats       = $g.Count
        throughput    = [math]::Round((Get-Median ($g | ForEach-Object { $_.throughput })), 1)
        throughputMin = [math]::Round((($g | Measure-Object -Property throughput -Minimum).Minimum), 1)
        throughputMax = [math]::Round((($g | Measure-Object -Property throughput -Maximum).Maximum), 1)
        allocPerSec   = [math]::Round((Get-Median ($g | ForEach-Object { $_.allocPerSec })), 1)
        p50           = [math]::Round((Get-Median ($g | ForEach-Object { $_.p50 })), 1)
        p95           = [math]::Round((Get-Median ($g | ForEach-Object { $_.p95 })), 1)
        p99           = [math]::Round((Get-Median ($g | ForEach-Object { $_.p99 })), 1)
        errorPct      = [math]::Round((Get-Median ($g | ForEach-Object { $_.errorPct })), 2)
        rejected409   = [math]::Round((Get-Median ($g | ForEach-Object { $_.rejected409 })), 0)
        contention503 = [math]::Round((Get-Median ($g | ForEach-Object { $_.contention503 })), 0)
        retries       = [math]::Round((Get-Median ($g | ForEach-Object { $_.retries })), 0)
        oversell      = ($g | Measure-Object -Property oversell -Sum).Sum
        allocDbDiff   = ($g | Measure-Object -Property allocDbDiff -Sum).Sum
    }
} | Sort-Object scenario, mode, vus

$summary | Export-Csv -Path (Join-Path $RunDir 'summary.csv') -NoTypeInformation -Encoding UTF8
$summary | ConvertTo-Json | Set-Content -Path (Join-Path $RunDir 'summary.json') -Encoding UTF8

foreach ($scenario in ($summary | Select-Object -ExpandProperty scenario -Unique)) {
    Write-Host ''
    Write-Host "== $scenario (median of repeats; latency in ms)"
    $summary | Where-Object { $_.scenario -eq $scenario } |
        Format-Table mode, vus, repeats, throughput, throughputMin, throughputMax, allocPerSec, p50, p95, p99, errorPct, rejected409, contention503, retries, oversell, allocDbDiff -AutoSize |
        Out-Host
}
if (($summary | Measure-Object -Property oversell -Sum).Sum -ne 0) {
    Write-Warning 'INVARIANT VIOLATIONS found (oversell column). Do not publish these numbers.'
}
Write-Host "Wrote $(Join-Path $RunDir 'summary.csv') and summary.json"
