# =====================================================================
#  download-real-data.ps1
#
#  Download the real e-commerce dataset (Olist Brazilian E-Commerce
#  Public Dataset) used by this project into data/real-raw/.
#
#  Source: jsDelivr CDN mirror of the public GitHub dataset
#          (works from mainland China without a proxy).
#
#  Usage (from project root):
#      powershell -ExecutionPolicy Bypass -File scripts\download-real-data.ps1
#      powershell -ExecutionPolicy Bypass -File scripts\download-real-data.ps1 -Force
#
#  After downloading, convert it to this project's ODS format:
#      java -cp "spark-job/target/classes;<deps>" com.sales.SalesAnalysisApplication --import-real
#      scripts\run-etl.cmd --import-real      (shortcut, same thing)
#
#  NOTE: this script intentionally contains NO non-ASCII characters,
#        because Windows PowerShell 5.1 reads .ps1 as ANSI when there is
#        no BOM and would turn Chinese text into garbage.
# =====================================================================
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root      = Split-Path -Parent $ScriptDir
$DestDir   = Join-Path $Root "data\real-raw"

# jsDelivr CDN keeps a mirror of the public dataset repo.
# jsDelivr is reachable from mainland China without a proxy.
$Base = "https://cdn.jsdelivr.net/gh/spdrio/Brazilian-E-Commerce-Public-Dataset-by-Olist@master/files/"

# file name -> expected minimum size in MB (sanity check after download)
$Files = [ordered]@{
    "olist_orders_dataset.csv"                  = 1.0
    "olist_order_items_dataset.csv"             = 5.0
    "olist_customers_dataset.csv"               = 5.0
    "olist_products_dataset.csv"                = 1.0
    "olist_order_payments_dataset.csv"          = 1.0
    "product_category_name_translation.csv"     = 0.001
}

Write-Host "[download-real-data] target dir : $DestDir"
New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

$failed = @()
foreach ($name in $Files.Keys) {
    $out = Join-Path $DestDir $name
    $minBytes = [int64]($Files[$name] * 1MB)

    if ((Test-Path $out) -and -not $Force) {
        $len = (Get-Item $out).Length
        if ($len -ge $minBytes) {
            Write-Host ("[download-real-data]   skip (exists, {0:N2} MB) : {1}" -f ($len / 1MB), $name)
            continue
        }
        Write-Host "[download-real-data]   incomplete file, re-downloading : $name"
    }

    $ok = $false
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            Invoke-WebRequest -Uri ($Base + $name) `
                -Headers @{ "User-Agent" = "Mozilla/5.0 (compatible; spark-sales-analysis)" } `
                -OutFile $out -TimeoutSec 300 -UseBasicParsing
            $len = (Get-Item $out).Length
            if ($len -ge $minBytes) {
                Write-Host ("[download-real-data]   OK ({0:N2} MB){1} : {2}" -f ($len / 1MB),
                    $(if ($attempt -gt 1) { " attempt $attempt" } else { "" }), $name)
                $ok = $true
                break
            }
            Write-Host "[download-real-data]   size too small ($len bytes), retrying : $name"
        } catch {
            Write-Host ("[download-real-data]   attempt {0} failed : {1}" -f $attempt, $_.Exception.Message)
        }
        Start-Sleep -Seconds 4
    }
    if (-not $ok) { $failed += $name }
}

$totalMB = [math]::Round(((Get-ChildItem $DestDir -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host ""
Write-Host "[download-real-data] total downloaded : $totalMB MB"

if ($failed.Count -gt 0) {
    Write-Warning ("failed files: " + ($failed -join ", "))
    Write-Warning "check your network, or download them manually into $DestDir"
    exit 1
}

Write-Host "[download-real-data] DONE"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1) convert to ODS format :  scripts\run-etl.cmd --import-real"
Write-Host "  2) point data.raw.dir to data/real in spark-job.properties"
Write-Host "  3) run the pipeline      :  scripts\run-etl.cmd --mode=both"
Write-Host ""
