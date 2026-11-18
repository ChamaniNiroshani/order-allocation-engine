<#
.SYNOPSIS
  Turns a summarized matrix run into docs/benchmarks.md building blocks: markdown tables and an SVG chart.

.DESCRIPTION
  Reads <RunDir>\summary.csv (from summarize.ps1) and writes:
    docs/img/hot-throughput.svg      successful allocations/s vs VUs, one line per mode (hot scenario)
    <RunDir>\tables.md               one results table per scenario (not committed; pasted into docs/benchmarks.md)
  No dependencies: the SVG is written by hand (Mermaid xychart-beta has no legend, so 3 lines would be ambiguous).
#>
param(
    [string]$RunDir
)

$repo = Split-Path -Parent $PSScriptRoot
if (-not $RunDir) {
    $RunDir = (Get-ChildItem (Join-Path $PSScriptRoot 'results') -Directory | Where-Object { $_.Name -like 'matrix-*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
$rows = Import-Csv (Join-Path $RunDir 'summary.csv')
$inv = [System.Globalization.CultureInfo]::InvariantCulture
function N([string]$s) { return [double]::Parse($s, $inv) }

# ---- tables ------------------------------------------------------------------------------------------------
$modeOrder = @('PESSIMISTIC', 'OPTIMISTIC', 'ATOMIC_UPDATE')
$md = New-Object System.Text.StringBuilder
foreach ($scenario in @('hot', 'spread')) {
    [void]$md.AppendLine("### Scenario: $scenario")
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| Mode | VUs | req/s (min-max of 3) | ok/s | p50 ms | p95 ms | p99 ms | error % | 409s | 503s | retries | oversell |')
    [void]$md.AppendLine('|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|')
    foreach ($mode in $modeOrder) {
        foreach ($r in ($rows | Where-Object { $_.scenario -eq $scenario -and $_.mode -eq $mode } | Sort-Object { [int]$_.vus })) {
            $line = '| {0} | {1} | {2} ({12}-{13}) | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} |' -f `
                $r.mode, $r.vus, $r.throughput, $r.allocPerSec, $r.p50, $r.p95, $r.p99, $r.errorPct, `
                $r.rejected409, $r.contention503, $r.retries, $r.oversell, $r.throughputMin, $r.throughputMax
            [void]$md.AppendLine($line)
        }
    }
    [void]$md.AppendLine('')
}
Set-Content -Path (Join-Path $RunDir 'tables.md') -Value $md.ToString() -Encoding UTF8

# ---- chart -------------------------------------------------------------------------------------------------
$vusList = @(10, 50, 100, 200)
$colors = @{ PESSIMISTIC = '#1f77b4'; OPTIMISTIC = '#d62728'; ATOMIC_UPDATE = '#2ca02c' }
$W = 720; $H = 420; $left = 70; $right = 40; $top = 50; $bottom = 70
$plotW = $W - $left - $right; $plotH = $H - $top - $bottom
$yMax = 140.0
function X([int]$i) { return $left + ($plotW * $i / ($vusList.Count - 1)) }
function Y([double]$v) { return $top + $plotH - ($plotH * $v / $yMax) }
function F([double]$v) { return $v.ToString('0.#', $inv) }

$svg = New-Object System.Text.StringBuilder
[void]$svg.AppendLine("<svg xmlns=`"http://www.w3.org/2000/svg`" width=`"$W`" height=`"$H`" viewBox=`"0 0 $W $H`" font-family=`"Segoe UI, Arial, sans-serif`" font-size=`"13`">")
[void]$svg.AppendLine("<rect width=`"$W`" height=`"$H`" fill=`"#ffffff`"/>")
[void]$svg.AppendLine("<text x=`"$($W / 2)`" y=`"26`" text-anchor=`"middle`" font-size=`"16`" font-weight=`"bold`" fill=`"#222`">Hot item: successful allocations per second vs concurrent users</text>")
foreach ($t in 0, 20, 40, 60, 80, 100, 120, 140) {
    $y = F (Y $t)
    [void]$svg.AppendLine("<line x1=`"$left`" y1=`"$y`" x2=`"$($W - $right)`" y2=`"$y`" stroke=`"#e3e3e3`"/>")
    [void]$svg.AppendLine("<text x=`"$($left - 8)`" y=`"$([double]$y + 4)`" text-anchor=`"end`" fill=`"#555`">$t</text>")
}
for ($i = 0; $i -lt $vusList.Count; $i++) {
    $x = F (X $i)
    [void]$svg.AppendLine("<text x=`"$x`" y=`"$($H - $bottom + 22)`" text-anchor=`"middle`" fill=`"#555`">$($vusList[$i])</text>")
}
[void]$svg.AppendLine("<text x=`"$($left + $plotW / 2)`" y=`"$($H - $bottom + 46)`" text-anchor=`"middle`" fill=`"#222`">VUs (concurrent users)</text>")
[void]$svg.AppendLine("<text transform=`"translate(18 $($top + $plotH / 2)) rotate(-90)`" text-anchor=`"middle`" fill=`"#222`">201 responses per second (median of 3 runs)</text>")
[void]$svg.AppendLine("<line x1=`"$left`" y1=`"$($top + $plotH)`" x2=`"$($W - $right)`" y2=`"$($top + $plotH)`" stroke=`"#888`"/>")

$legendX = $left + 10
foreach ($mode in $modeOrder) {
    $pts = @(); $vals = @()
    for ($i = 0; $i -lt $vusList.Count; $i++) {
        $r = $rows | Where-Object { $_.scenario -eq 'hot' -and $_.mode -eq $mode -and [int]$_.vus -eq $vusList[$i] }
        $v = N $r.allocPerSec
        $vals += $v
        $pts += ('{0},{1}' -f (F (X $i)), (F (Y $v)))
    }
    $c = $colors[$mode]
    [void]$svg.AppendLine("<polyline points=`"$($pts -join ' ')`" fill=`"none`" stroke=`"$c`" stroke-width=`"2.5`"/>")
    for ($i = 0; $i -lt $vusList.Count; $i++) {
        [void]$svg.AppendLine("<circle cx=`"$(F (X $i))`" cy=`"$(F (Y $vals[$i]))`" r=`"4`" fill=`"$c`"/>")
    }
    [void]$svg.AppendLine("<rect x=`"$legendX`" y=`"$($top + 6)`" width=`"14`" height=`"4`" fill=`"$c`"/>")
    [void]$svg.AppendLine("<text x=`"$($legendX + 20)`" y=`"$($top + 12)`" fill=`"#222`">$mode</text>")
    $legendX += 40 + ($mode.Length * 8)
}
[void]$svg.AppendLine('</svg>')

$imgDir = Join-Path $repo 'docs\img'
New-Item -ItemType Directory -Force $imgDir | Out-Null
Set-Content -Path (Join-Path $imgDir 'hot-throughput.svg') -Value $svg.ToString() -Encoding UTF8
Write-Host "Wrote $(Join-Path $RunDir 'tables.md') and docs\img\hot-throughput.svg"
