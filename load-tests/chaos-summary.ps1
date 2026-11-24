# Summarises one chaos run (written by chaos-db.ps1 / chaos-app.ps1): per 5-second bucket the number of 201 / 409 /
# 5xx / timeouts-and-connection-errors and the p95 latency, the time to recover, and what the client probes received.
#
#   .\load-tests\chaos-summary.ps1 -Dir load-tests\results\chaos-db-20261124-120000
#
# Timestamps: k6 stamps a request when it FINISHES (whole seconds). A request that hangs for 30 s is therefore counted in
# the bucket where it ended, not where it started. t=0 is the first order request of the timed run.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Dir,
    [int]$BucketSec = 5
)
$ErrorActionPreference = 'Stop'
$meta = Get-Content (Join-Path $Dir 'meta.json') -Raw | ConvertFrom-Json
$t0 = [long]$meta.firstRequestMs

function Get-Percentile([double[]]$Values, [double]$P) {
    if ($Values.Count -eq 0) { return $null }
    $sorted = $Values | Sort-Object
    $idx = [math]::Ceiling($P * $sorted.Count) - 1
    $sorted[[math]::Max(0, [int]$idx)]
}
function Fmt($v) { if ($null -eq $v) { '-' } else { '{0:N0}' -f $v } }

# One row per request: the http_req_duration point carries the status and url tags
$rows = Import-Csv (Join-Path $Dir 'requests.csv') |
    Where-Object { $_.metric_name -eq 'http_req_duration' -and $_.url -match '/api/(orders|checkout)' } |
    ForEach-Object {
        [pscustomobject]@{
            ms     = [long]$_.timestamp * 1000
            status = [int]$_.status
            dur    = [double]$_.metric_value
            error  = $_.error
        }
    }
$rows = @($rows | Sort-Object ms)

$lastBucket = [int][math]::Floor((($rows | Select-Object -Last 1).ms - $t0) / (1000 * $BucketSec))
$table = for ($b = 0; $b -le $lastBucket; $b++) {
    $lo = $t0 + $b * $BucketSec * 1000
    $hi = $lo + $BucketSec * 1000
    $in = @($rows | Where-Object { $_.ms -ge $lo -and $_.ms -lt $hi })
    [pscustomobject]@{
        Bucket   = '{0,3}-{1,3}s' -f ($b * $BucketSec), (($b + 1) * $BucketSec)
        Total    = $in.Count
        'S201'   = @($in | Where-Object { $_.status -eq 201 }).Count
        'S409'   = @($in | Where-Object { $_.status -eq 409 }).Count
        'S5xx'   = @($in | Where-Object { $_.status -ge 500 }).Count
        'Timeout_or_conn_err' = @($in | Where-Object { $_.status -eq 0 }).Count
        'p95_ms' = Fmt (Get-Percentile @($in | ForEach-Object { $_.dur }) 0.95)
        'max_ms' = Fmt (($in | Measure-Object dur -Maximum).Maximum)
    }
}

$stopBegin = [long]$meta.postgresStopBeginMs
$startBegin = [long]$meta.postgresStartBeginMs
$healthy = [long]$meta.postgresHealthyMs
$firstOkAfter = $rows | Where-Object { $_.ms -ge $startBegin -and $_.status -eq 201 } | Select-Object -First 1
$fail = @($rows | Where-Object { $_.status -ge 500 -or $_.status -eq 0 })
$fail5xx = @($rows | Where-Object { $_.status -ge 500 })
$firstFail = $fail | Select-Object -First 1
$lastFail = $fail | Select-Object -Last 1
$okBefore = @($rows | Where-Object { $_.ms -lt $stopBegin -and $_.status -eq 201 }).Count
$dist = $rows | Group-Object status | Sort-Object { [int]$_.Name } | ForEach-Object { "$($_.Name): $($_.Count)" }
$slow = @($rows | Where-Object { $_.dur -ge 1000 })

$out = New-Object System.Collections.Generic.List[string]
$out.Add("# Chaos run summary: $($meta.label)")
$out.Add('')
$out.Add("- Load: $($meta.vus) VUs, $($meta.durationSec) s timed run, $($meta.items) items x $($meta.stockPerItem) stock")
$out.Add(('- t=0 (first order request): {0}' -f [DateTimeOffset]::FromUnixTimeMilliseconds($t0).ToLocalTime().ToString('HH:mm:ss')))
$out.Add(('- Postgres stop issued at t={0:N1} s, stop command returned at t={1:N1} s' -f (($stopBegin - $t0) / 1000), (([long]$meta.postgresStopEndMs - $t0) / 1000)))
$out.Add(('- Postgres start issued at t={0:N1} s, container healthy at t={1:N1} s' -f (($startBegin - $t0) / 1000), (($healthy - $t0) / 1000)))
$out.Add("- Requests: $($rows.Count) total; by status: $($dist -join ', ')   (0 = timeout / connection error)")
$out.Add("- 5xx: $($fail5xx.Count); requests slower than 1 s: $($slow.Count); slowest: $(Fmt (($rows | Measure-Object dur -Maximum).Maximum)) ms")
if ($firstFail) { $out.Add(('- First failure finished at t={0:N1} s, last at t={1:N1} s' -f (($firstFail.ms - $t0) / 1000), (($lastFail.ms - $t0) / 1000))) }
if ($firstOkAfter) {
    $out.Add(('- **Time to recover** (first 201 after `start postgres` was issued): t={0:N1} s = {1:N1} s after the start command, {2:N1} s after the container reported healthy' -f `
        (($firstOkAfter.ms - $t0) / 1000), (($firstOkAfter.ms - $startBegin) / 1000), (($firstOkAfter.ms - $healthy) / 1000)))
} else { $out.Add('- **Time to recover**: NO 201 after Postgres came back') }
$out.Add('')
$out.Add('| Bucket | Requests | 201 | 409 | 5xx | timeout / conn. error | p95 ms | max ms |')
$out.Add('|---|---:|---:|---:|---:|---:|---:|---:|')
foreach ($r in $table) {
    $out.Add("| $($r.Bucket.Trim()) | $($r.Total) | $($r.S201) | $($r.S409) | $($r.S5xx) | $($r.Timeout_or_conn_err) | $($r.p95_ms) | $($r.max_ms) |")
}

# Invariants
if ($meta.invariant) {
    $i = $meta.invariant
    $out.Add('')
    $out.Add('Invariants (items created by this run):')
    $out.Add("- items: $($i.items); items with negative stock: $($i.negativeStockItems); items where available + reserved + allocated/confirmed != initial: $($i.itemsWhereSumNotEqualInitial)")
    $out.Add("- sum available $($i.sumAvailable) + reserved $($i.sumReserved) + allocated/confirmed $($i.sumAllocated) = $($i.sumAvailable + $i.sumReserved + $i.sumAllocated) (initial $($i.items * $meta.stockPerItem))")
    $out.Add("- orphan orders (PENDING / RESERVED / PAID): $(if (@($meta.orphanOrders).Count -eq 0) { 'none' } else { @($meta.orphanOrders) -join ', ' })")
    $out.Add("- orders by status: $(@($meta.ordersByStatus) -join ', ')")
}
if ($meta.log) {
    $l = $meta.log
    $out.Add('')
    $out.Add("App log during the run: $($l.totalLines) lines, $($l.errorLines) ERROR, $($l.warnLines) WARN, $($l.hikariTimeoutMessages) Hikari 'Connection is not available' / timeout messages, $($l.unhandledExceptionLines) 'Unhandled exception' ERRORs, $($l.stackTraceLines) stack-trace lines")
}

# What the probes received
$probeFiles = @(Get-ChildItem $Dir -Filter 'probes-*.jsonl')
if ($probeFiles.Count -gt 0) {
    $probes = @($probeFiles | ForEach-Object { Get-Content $_.FullName } | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
    $out.Add('')
    $out.Add('Client probes (3 order POST probes and 1 readiness GET probe, each about once a second, from the host). t is when the probe started:')
    $out.Add('')
    $out.Add('| t (s) | request | status | took ms | Retry-After | body |')
    $out.Add('|---:|---|---:|---:|---|---|')
    foreach ($p in ($probes | Sort-Object startMs)) {
        $rel = ($p.startMs - $t0) / 1000
        $inWindow = ($p.startMs -ge $stopBegin - 1000) -and ($p.startMs -le $healthy + 3000)
        $interesting = ($p.status -ne 201 -and $p.status -ne 200) -or $p.ms -ge 1000 -or ($inWindow -and $p.method -eq 'GET')
        if (-not $interesting) { continue }
        $body = ($p.body -replace '\s+', ' ')
        if ($body.Length -gt 140) { $body = $body.Substring(0, 140) + '...' }
        $what = if ($p.method -eq 'POST') { 'POST /api/orders' } else { 'GET readiness' }
        $out.Add(('| {0:N1} | {1} | {2} | {3} | {4} | `{5}` |' -f $rel, $what, $p.status, $p.ms, $p.retryAfter, ($body -replace '\|', '/')))
    }
}

$out | Set-Content (Join-Path $Dir 'summary.md') -Encoding utf8
$out | ForEach-Object { Write-Host $_ }
