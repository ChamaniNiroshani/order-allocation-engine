# Smoke test for a deployed instance (docs/deploy.md). It creates a little data: one item, one order, one checkout,
# one warehouse, one batch and one fulfilment.
#   .\docs\smoke-live.ps1 -BaseUrl https://<service>.onrender.com
# The first line it prints is how long readiness took to answer UP. On a sleeping instance that is the cold start.
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [int]$WakeTimeoutSeconds = 900
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:failed = 0

function Invoke-Api($Method, $Path, $Body, $Key) {
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; UseBasicParsing = $true; TimeoutSec = 180 }
    if ($Body) { $request.Body = ($Body | ConvertTo-Json -Compress); $request.ContentType = 'application/json' }
    if ($Key) { $request.Headers = @{ 'Idempotency-Key' = $Key } }
    try {
        $response = Invoke-WebRequest @request
        return @{ Status = [int]$response.StatusCode; Text = [string]$response.Content }
    } catch {
        if (-not $_.Exception.Response) { return @{ Status = 0; Text = $_.Exception.Message } }
        return @{ Status = [int]$_.Exception.Response.StatusCode; Text = '' }
    }
}

function Test-Step($Name, $Result, $Expected) {
    if ($Result.Status -eq $Expected) { Write-Host "PASS  $Name ($($Result.Status))" }
    else { Write-Host "FAIL  $Name : expected $Expected, got $($Result.Status) $($Result.Text)"; $script:failed++ }
}

# ---- wake up -------------------------------------------------------------------------------------------------
$clock = [Diagnostics.Stopwatch]::StartNew()
do {
    $ready = Invoke-Api GET '/actuator/health/readiness'
    if ($ready.Status -eq 200 -and $ready.Text -match '"UP"') { break }
    Start-Sleep -Seconds 5
} while ($clock.Elapsed.TotalSeconds -lt $WakeTimeoutSeconds)
Write-Host ("Readiness answered after {0:N1} s" -f $clock.Elapsed.TotalSeconds)
Test-Step 'readiness UP' $ready 200

# ---- documentation -------------------------------------------------------------------------------------------
Test-Step 'Swagger UI' (Invoke-Api GET '/swagger-ui/index.html') 200
$docs = Invoke-Api GET '/v3/api-docs'
Test-Step 'API description' $docs 200
if ($docs.Status -eq 200) { Write-Host "      server URL in the description: $(($docs.Text | ConvertFrom-Json).servers[0].url)" }

# ---- one order, one checkout, one fulfilment -------------------------------------------------------------------
$run = [guid]::NewGuid().ToString()
$item = Invoke-Api POST '/api/inventory' @{ quantityAvailable = 10 }
Test-Step 'create item' $item 201
if ($item.Status -eq 201) {
    $itemId = ($item.Text | ConvertFrom-Json).itemId
    Test-Step 'order' (Invoke-Api POST '/api/orders' @{ itemId = $itemId; quantityRequested = 1 } "smoke-order-$run") 201
    Test-Step 'checkout' (Invoke-Api POST '/api/checkout' @{ itemId = $itemId; quantityRequested = 1 } "smoke-checkout-$run") 201
}
$warehouse = Invoke-Api POST '/api/warehouses' @{ name = 'Smoke test'; lat = 6.9271; lon = 79.8612 }
Test-Step 'create warehouse' $warehouse 201
if ($warehouse.Status -eq 201) {
    $sku = "SMOKE-$($run.Substring(0, 8))"
    Test-Step 'create batch' (Invoke-Api POST '/api/batches' @{ sku = $sku; warehouseId = ($warehouse.Text | ConvertFrom-Json).id; qtyAvailable = 5 }) 201
    Test-Step 'fulfilment' (Invoke-Api POST '/api/fulfilments' @{ sku = $sku; quantity = 1; deliveryLat = 6.9271; deliveryLon = 79.8612; customerTier = 'STANDARD' } "smoke-fulfilment-$run") 201
}

# ---- must not be public ----------------------------------------------------------------------------------------
foreach ($path in '/actuator/prometheus', '/actuator/metrics', '/actuator/info', '/actuator/env') {
    Test-Step "$path is not exposed" (Invoke-Api GET $path) 404
}

if ($script:failed) { Write-Host "$($script:failed) check(s) failed"; exit 1 }
Write-Host 'All checks passed'
