<#
.SYNOPSIS
  Benchmark matrix: locking mode x scenario (hot, spread) x VUs x repeats.

.DESCRIPTION
  For each locking mode it starts the app from the built jar, runs one discarded warm-up, then for every
  scenario / VU count / repeat: truncates the data tables, runs k6 (in Docker), reads the retry counter from
  /actuator/metrics/allocation.lock.retries before and after, and runs the oversell / stock-invariant SQL.
  Everything for one run lands in load-tests/results/<RunId>/ (git-ignored):
    <script>-<mode>-<vus>vus-<timestamp>.json        raw k6 summary
    <same name>.meta.json                            retries, invariant check, exit code
    app-<mode>.log                                   application log

  The app runs on the host, Postgres and k6 in Docker. Run from anywhere:  .\load-tests\run-matrix.ps1
  Then:  .\load-tests\summarize.ps1

  STOCK is large on purpose: with a small stock a hot item runs dry and the run mostly measures the 409
  rejection path (which takes no write, so optimistic locking would look conflict-free).
#>
param(
    [string[]]$Modes = @('PESSIMISTIC', 'OPTIMISTIC', 'ATOMIC_UPDATE'),
    [string[]]$Scenarios = @('hot', 'spread'),
    [int[]]$VusList = @(10, 50, 100, 200),
    [int]$Repeats = 3,
    [string]$Duration = '30s',
    [int]$WarmupSeconds = 20,
    [int]$Stock = 100000000,
    [int]$Items = 100,
    [string]$RunId = ('matrix-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'   # native commands: check $LASTEXITCODE explicitly

$repo = Split-Path -Parent $PSScriptRoot
$loadDir = Join-Path $repo 'load-tests'
$resultsRel = "results/$RunId"
$resultsDir = Join-Path $loadDir ("results\" + $RunId)
$jar = Join-Path $repo 'target\order-allocation-engine-0.0.1-SNAPSHOT.jar'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$baseUrl = 'http://localhost:8080'
$scriptFor = @{ hot = 'hot-item.js'; spread = 'spread.js' }

if ($Duration -notmatch '^\d+s$') { throw "Duration must look like 30s, got '$Duration'" }
$durationSeconds = [int]($Duration -replace 's$', '')
foreach ($s in $Scenarios) { if (-not $scriptFor.ContainsKey($s)) { throw "Unknown scenario '$s' (hot | spread)" } }

function Invoke-Psql([string]$sql) {
    $out = docker exec oae-postgres psql -U postgres -d order_allocation_database -t -A -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $sql" }
    return ($out | Out-String).Trim()
}

function Get-Retries {
    $m = Invoke-RestMethod -Uri "$baseUrl/actuator/metrics/allocation.lock.retries"
    return [double](($m.measurements | Where-Object { $_.statistic -eq 'COUNT' }).value)
}

function Start-App([string]$mode) {
    $log = Join-Path $resultsDir "app-$mode.log"
    # show-sql is turned off here: logging every statement would dominate the numbers.
    $p = Start-Process -FilePath $java `
        -ArgumentList @('-jar', $jar, "--allocation.locking-mode=$mode", '--spring.jpa.show-sql=false') `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    for ($i = 0; $i -lt 90; $i++) {
        if ($p.HasExited) { throw "App exited during startup, see $log" }
        try {
            $h = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 2
            if ($h.status -eq 'UP') { return $p }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Stop-Process -Id $p.Id -Force
    throw "App did not become healthy in 180 s, see $log"
}

function Stop-App($p) {
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    $p.WaitForExit()
    for ($i = 0; $i -lt 30; $i++) {
        if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Seconds 1
    }
}

function Invoke-K6([string]$script, [int]$vus, [string]$dur, [string]$mode, [bool]$save) {
    $saveStr = $save.ToString().ToLower()
    docker run --rm -i -v "${loadDir}:/scripts" `
        -e "VUS=$vus" -e "DURATION=$dur" -e "MODE=$mode" -e "STOCK=$Stock" -e "ITEMS=$Items" `
        -e "RESULT_DIR=$resultsRel" -e "SAVE_RESULT=$saveStr" `
        grafana/k6 run --quiet "/scripts/$script" | Out-Host
    return $LASTEXITCODE
}

function Clear-Data {
    Invoke-Psql 'TRUNCATE allocation, orders, inventory_item' | Out-Null
}

# ---- pre-flight --------------------------------------------------------------------------------------------
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running. Start Docker Desktop first.' }

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is already in use. Stop the running app first.'
}

Push-Location $repo
try {
    docker compose up -d --wait | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
    docker image inspect grafana/k6 *> $null
    if ($LASTEXITCODE -ne 0) { docker pull grafana/k6 | Out-Host }

    if (-not $SkipBuild) {
        Write-Host '== Building the jar'
        & (Join-Path $repo 'mvnw.cmd') -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    if (-not (Test-Path $jar)) { throw "Jar not found: $jar" }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force $resultsDir | Out-Null
$total = $Modes.Count * $Scenarios.Count * $VusList.Count * $Repeats
$done = 0
$started = Get-Date
Write-Host "== Run $RunId : $total measured runs, $Duration each. Results: $resultsDir"

# ---- matrix ------------------------------------------------------------------------------------------------
foreach ($mode in $Modes) {
    Write-Host ''
    Write-Host "== [$mode] starting the app"
    $app = Start-App $mode
    try {
        Write-Host "== [$mode] warm-up: spread, 20 VUs, ${WarmupSeconds}s (discarded)"
        Clear-Data
        Invoke-K6 'spread.js' 20 "${WarmupSeconds}s" 'warmup' $false | Out-Null

        foreach ($scenario in $Scenarios) {
            foreach ($vus in $VusList) {
                for ($rep = 1; $rep -le $Repeats; $rep++) {
                    $done++
                    Write-Host ''
                    Write-Host ("== [{0}/{1}] {2} {3} {4} VUs repeat {5}" -f $done, $total, $mode, $scenario, $vus, $rep)

                    Clear-Data
                    Start-Sleep -Seconds 2
                    $before = @(Get-ChildItem $resultsDir -Filter '*.json' | Where-Object { $_.Name -notlike '*.meta.json' } | ForEach-Object { $_.Name })
                    $retriesBefore = Get-Retries
                    $runStart = Get-Date

                    $exit = Invoke-K6 $scriptFor[$scenario] $vus $Duration $mode $true

                    $runEnd = Get-Date
                    $retriesAfter = Get-Retries
                    $new = @(Get-ChildItem $resultsDir -Filter '*.json' | Where-Object { $_.Name -notlike '*.meta.json' -and $before -notcontains $_.Name })
                    $k6File = if ($new.Count -eq 1) { $new[0].Name } else { $null }

                    # Invariants straight from the database
                    $negative = [int](Invoke-Psql 'SELECT count(*) FROM inventory_item WHERE quantity_available < 0')
                    $violations = [int](Invoke-Psql ("SELECT count(*) FROM (SELECT i.item_id FROM inventory_item i LEFT JOIN allocation a ON a.item_id = i.item_id GROUP BY i.item_id, i.quantity_available HAVING i.quantity_available + COALESCE(SUM(a.quantity_allocated), 0) <> {0}) v" -f $Stock))
                    $allocRows = [int](Invoke-Psql 'SELECT count(*) FROM allocation')
                    $itemRows = [int](Invoke-Psql 'SELECT count(*) FROM inventory_item')

                    $meta = [pscustomobject]@{
                        runId               = $RunId
                        mode                = $mode
                        scenario            = $scenario
                        vus                 = $vus
                        repeat              = $rep
                        duration            = $Duration
                        durationSeconds     = $durationSeconds
                        stock               = $Stock
                        itemRows            = $itemRows
                        k6File              = $k6File
                        k6ExitCode          = $exit
                        retriesBefore       = $retriesBefore
                        retriesAfter        = $retriesAfter
                        retries             = ($retriesAfter - $retriesBefore)
                        negativeStockRows   = $negative
                        invariantViolations = $violations
                        allocationRows      = $allocRows
                        startedAt           = $runStart.ToString('o')
                        endedAt             = $runEnd.ToString('o')
                    }
                    $metaName = if ($k6File) { ($k6File -replace '\.json$', '') + '.meta.json' } else { "failed-$mode-$scenario-${vus}vus-r$rep.meta.json" }
                    $meta | ConvertTo-Json | Set-Content -Path (Join-Path $resultsDir $metaName) -Encoding UTF8

                    $flag = if ($negative -ne 0 -or $violations -ne 0) { '  <-- INVARIANT VIOLATED' } elseif ($exit -ne 0) { "  (k6 exit $exit)" } else { '' }
                    Write-Host ("   retries={0} allocations={1} negativeRows={2} invariantViolations={3}{4}" -f $meta.retries, $allocRows, $negative, $violations, $flag)
                }
            }
        }
    } finally {
        Write-Host "== [$mode] stopping the app"
        Stop-App $app
    }
}

$elapsed = (Get-Date) - $started
Write-Host ''
Write-Host ("== Done in {0:N0} min. Results: {1}" -f $elapsed.TotalMinutes, $resultsDir)
Write-Host '== Next: .\load-tests\summarize.ps1'
