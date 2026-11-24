# Shared helpers for the chaos scripts (chaos-db.ps1, chaos-app.ps1). Dot-source it:  . "$PSScriptRoot\lib\chaos-common.ps1"
# Requires: Docker Desktop, the compose stack (docker compose --profile app up -d), PowerShell 5.1+.

$script:K6_IMAGE = 'grafana/k6:2.4.0'          # pinned: the version these results were measured with
$script:PG_CONTAINER = 'oae-postgres'

function Get-EpochMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

function Format-Epoch([long]$ms) { [DateTimeOffset]::FromUnixTimeMilliseconds($ms).ToLocalTime().ToString('HH:mm:ss.fff') }

# Reads DB_NAME / DB_USERNAME from .env the way compose does (same defaults)
function Get-DbSettings {
    $db = @{ Name = 'order_allocation_database'; User = 'postgres' }
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) '.env'
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile) {
            if ($line -match '^\s*DB_NAME=(.+)$') { $db.Name = $Matches[1].Trim() }
            if ($line -match '^\s*DB_USERNAME=(.+)$') { $db.User = $Matches[1].Trim() }
        }
    }
    $db
}

# Runs one SQL statement inside the postgres container and returns the rows (tab separated, no headers)
function Invoke-Sql([string]$Sql) {
    $db = Get-DbSettings
    $out = $Sql | docker exec -i $script:PG_CONTAINER psql -U $db.User -d $db.Name -At -F "`t" -v ON_ERROR_STOP=1 2>&1
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $out" }
    $out
}

function Wait-Healthy([string]$Container, [int]$TimeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $s = docker inspect -f '{{.State.Health.Status}}' $Container 2>$null
        if ($s -eq 'healthy') { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-ItemIds { @(Invoke-Sql 'SELECT item_id FROM inventory_item ORDER BY item_id') | Where-Object { $_ } }

function ConvertTo-SqlList($ids) { ($ids | ForEach-Object { "'$_'" }) -join ',' }

# POST /api/inventory from the host (used to make the probe item)
function New-InventoryItem([string]$BaseUrl, [int]$Stock) {
    $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/inventory" -ContentType 'application/json' `
        -Body (@{ quantityAvailable = $Stock } | ConvertTo-Json)
    $r.itemId
}

# Background probes: "what a client gets" during the experiment (k6 does not keep response bodies).
#   -Kind order : POST $Path (an order or a checkout) with a unique Idempotency-Key, then sleep $IntervalMs
#   -Kind ready : GET /actuator/health/readiness, then sleep $IntervalMs
# They are independent jobs on purpose: a POST that hangs for 25 s must not stop readiness from being sampled.
# Each writes one JSON object per line to its own file $Out. Stops when the file $StopFile exists.
function Start-Probe([string]$Kind, [string]$BaseUrl, [string]$ItemId, [string]$Path, [string]$Out, [string]$StopFile, [int]$IntervalMs = 1000) {
    Start-Job -ArgumentList $Kind, $BaseUrl, $ItemId, $Path, $Out, $StopFile, $IntervalMs -ScriptBlock {
        param($Kind, $BaseUrl, $ItemId, $Path, $Out, $StopFile, $IntervalMs)
        function Send([string]$Method, [string]$Uri, $Headers, $Body, [int]$Timeout) {
            $sw = [Diagnostics.Stopwatch]::StartNew()
            $startMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $status = 0; $content = ''; $retry = $null
            try {
                $p = @{ UseBasicParsing = $true; Method = $Method; Uri = $Uri; TimeoutSec = $Timeout }
                if ($Headers) { $p.Headers = $Headers }
                if ($Body) { $p.Body = $Body; $p.ContentType = 'application/json' }
                $r = Invoke-WebRequest @p
                $status = [int]$r.StatusCode; $content = $r.Content
                if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
            } catch {
                $resp = $_.Exception.Response
                if ($resp) {
                    $status = [int]$resp.StatusCode
                    try { $retry = $resp.Headers['Retry-After'] } catch {}
                    try { $content = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch {}
                } else { $content = $_.Exception.Message }
            }
            [pscustomobject]@{ startMs = $startMs; ms = $sw.ElapsedMilliseconds; method = $Method; uri = $Uri; status = $status; retryAfter = $retry; body = $content }
        }
        while (-not (Test-Path $StopFile)) {
            if ($Kind -eq 'order') {
                $body = (@{ itemId = $ItemId; quantityRequested = 1 } | ConvertTo-Json -Compress)
                $r = Send 'POST' "$BaseUrl$Path" @{ 'Idempotency-Key' = "probe-$([guid]::NewGuid())" } $body 120
            } else {
                $r = Send 'GET' "$BaseUrl/actuator/health/readiness" $null $null 10
            }
            ($r | ConvertTo-Json -Compress) | Add-Content -Path $Out -Encoding utf8
            Start-Sleep -Milliseconds $IntervalMs
        }
    }
}

# Starts the three order probes and the readiness probe. Returns the jobs. Files: <dir>/probes-order1..3.jsonl, probes-ready.jsonl
function Start-AllProbes([string]$BaseUrl, [string]$ItemId, [string]$Path, [string]$Dir, [string]$StopFile) {
    $jobs = @()
    foreach ($n in 1..3) { $jobs += Start-Probe 'order' $BaseUrl $ItemId $Path (Join-Path $Dir "probes-order$n.jsonl") $StopFile 1000 }
    $jobs += Start-Probe 'ready' $BaseUrl $ItemId $Path (Join-Path $Dir 'probes-ready.jsonl') $StopFile 1000
    $jobs
}

# Starts k6 in Docker with a per-request CSV. Returns the Process.
function Start-K6([string]$Script, [string]$ResultSubDir, [hashtable]$EnvVars) {
    $loadTests = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path -replace '\\', '/'
    $k6Args = @('run', '--rm', '-i', '-v', "${loadTests}:/scripts")
    foreach ($k in $EnvVars.Keys) { $k6Args += @('-e', "$k=$($EnvVars[$k])") }
    $k6Args += @($script:K6_IMAGE, 'run', '--quiet', '--out', "csv=/scripts/results/$ResultSubDir/requests.csv", "/scripts/$Script")
    Start-Process -FilePath docker -ArgumentList $k6Args -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $loadTests "results/$ResultSubDir/k6-stdout.txt") `
        -RedirectStandardError (Join-Path $loadTests "results/$ResultSubDir/k6-stderr.txt")
}

# Waits until k6 has sent its first request to $UrlPart (setup() is over, the timed run has begun). Returns epoch ms.
function Wait-FirstRequest([string]$Csv, [string]$UrlPart, [int]$TimeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $Csv) -and (Select-String -Path $Csv -Pattern $UrlPart -SimpleMatch -Quiet)) { return Get-EpochMs }
        Start-Sleep -Milliseconds 250
    }
    throw "k6 never sent a request to $UrlPart"
}

function Sleep-Until([long]$EpochMs) {
    $left = $EpochMs - (Get-EpochMs)
    if ($left -gt 0) { Start-Sleep -Milliseconds $left }
}

function Get-GrafanaUrl([long]$FromMs, [long]$ToMs) {
    "http://localhost:3000/d/allocation-engine/allocation-engine?orgId=1&from=$($FromMs - 30000)&to=$($ToMs + 30000)&refresh="
}

# A short unrecorded k6 run so the JVM is warm (JIT, connection pool) before the experiment starts
function Invoke-Warmup([string]$Script, [string]$K6BaseUrl, [int]$Seconds, [int]$Vus) {
    if ($Seconds -le 0) { return }
    $loadTests = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path.Replace('\', '/')
    docker run --rm -i -v "${loadTests}:/scripts" -e "BASE_URL=$K6BaseUrl" -e "VUS=$Vus" -e "ITEMS=10" -e "DURATION=${Seconds}s" -e SAVE_RESULT=false `
        $script:K6_IMAGE run --quiet "/scripts/$Script" 2>&1 | Out-Null
}
