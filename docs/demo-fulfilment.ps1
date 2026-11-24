<#
.SYNOPSIS
  Shows NEAREST vs FEFO vs SPLIT choosing different batches for the same request.

.DESCRIPTION
  Needs the app running with the dev profile, which loads the demo warehouses (Colombo, Kandy, Galle) from db/seed:
      $env:SPRING_PROFILES_ACTIVE = "dev"; .\mvnw.cmd spring-boot:run
  The script then gives each strategy its own fresh SKU with identical stock (so it can be run again and again),
  orders 60 units delivered to Colombo with each strategy, and prints which batch each one used.

  Stock per SKU:  Colombo 15 (expires in 3 days) + 25 (20 days) | Kandy 100 (10 days) | Galle 30 (45 days)
  Expected:       NEAREST = 60 from Kandy (Colombo is nearest but holds only 40)
                  FEFO    = 15 Colombo + 45 Kandy (earliest expiry first, wherever it is)
                  SPLIT   = 15 + 25 Colombo, then 20 from Kandy (nearest first, topped up from the next nearest)

.PARAMETER BaseUrl
  Where the app listens (default http://localhost:8080).
#>
param(
    [string] $BaseUrl = "http://localhost:8080",
    [int] $Quantity = 60
)

$ErrorActionPreference = "Stop"

# Fixed ids of the dev seed (src/main/resources/db/seed/R__dev_seed_warehouses.sql)
$colombo = "00000000-0000-0000-0000-0000000000c1"
$kandy   = "00000000-0000-0000-0000-0000000000c2"
$galle   = "00000000-0000-0000-0000-0000000000c3"
$names   = @{ $colombo = "Colombo"; $kandy = "Kandy"; $galle = "Galle" }

function Invoke-Api([string] $Method, [string] $Path, $Body, [hashtable] $Headers = @{}) {
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; ContentType = "application/json" }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    try {
        return Invoke-RestMethod @params
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        # 409 = "not enough usable stock": a valid answer, its body is the stored order
        if ($status -eq 409 -and $_.ErrorDetails.Message) { return ($_.ErrorDetails.Message | ConvertFrom-Json) }
        throw
    }
}

# Is the dev seed there?
try {
    Invoke-Api POST "/api/batches" @{ sku = "DEMO-PROBE"; warehouseId = $colombo; qtyAvailable = 0 } | Out-Null
} catch {
    Write-Host "The seeded warehouses are missing. Start the app with SPRING_PROFILES_ACTIVE=dev (see the header of this script)." -ForegroundColor Red
    throw
}

function New-DemoStock([string] $Sku) {
    $batches = @(
        @{ wh = $colombo; qty = 15;  days = 3  },
        @{ wh = $colombo; qty = 25;  days = 20 },
        @{ wh = $kandy;   qty = 100; days = 10 },
        @{ wh = $galle;   qty = 30;  days = 45 }
    )
    $created = @{}
    foreach ($b in $batches) {
        $expiry = (Get-Date).AddDays($b.days).ToString("yyyy-MM-dd")
        $r = Invoke-Api POST "/api/batches" @{ sku = $Sku; warehouseId = $b.wh; qtyAvailable = $b.qty; expiryDate = $expiry }
        $created[$r.id] = "{0,-8} {1,3} units, expires {2}" -f $names[$b.wh], $b.qty, $expiry
    }
    return $created
}

$run = [guid]::NewGuid().ToString().Substring(0, 8)
Write-Host ""
Write-Host "Order: $Quantity units delivered to Colombo (6.9271, 79.8612)" -ForegroundColor Cyan

foreach ($strategy in "NEAREST", "FEFO", "SPLIT") {
    $sku = "DEMO-$strategy-$run"
    $stock = New-DemoStock $sku
    $order = Invoke-Api POST "/api/fulfilments" @{
        sku = $sku; quantity = $Quantity; deliveryLat = 6.9271; deliveryLon = 79.8612
        customerTier = "STANDARD"; strategy = $strategy
    } @{ "Idempotency-Key" = "demo-$strategy-$run" }

    Write-Host ""
    Write-Host ("{0}  ->  {1}" -f $strategy, $order.status) -ForegroundColor Yellow
    if ($order.status -ne "ALLOCATED") { Write-Host "   $($order.failureReason)"; continue }
    foreach ($line in $order.lines) {
        Write-Host ("   {0,3} from {1}" -f $line.quantity, $stock[$line.batchId])
    }
}

Write-Host ""
Write-Host "Tier defaults: no strategy in the request -> PRIORITY uses NEAREST, STANDARD uses FEFO (allocation.strategy.tier-defaults)." -ForegroundColor DarkGray
