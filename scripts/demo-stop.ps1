# =====================================================================
#  demo-stop.ps1
#
#  Purpose : stop everything started by scripts\demo-start.ps1.
#            Matches java.exe processes whose command line contains
#            sales-analysis-server.jar, so it never touches unrelated
#            java processes (IDEs, other services, ...).
#
#  Usage (from the project root, in PowerShell):
#      powershell -ExecutionPolicy Bypass -File scripts\demo-stop.ps1
#
#  NOTE: kept in pure ASCII on purpose (see demo-start.ps1).
# =====================================================================
$ErrorActionPreference = 'Stop'

Write-Host "[demo-stop] looking for running demo services ..."

$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
         Where-Object { $_.CommandLine -like '*sales-analysis-server.jar*' }

if (-not $procs) {
    Write-Host "[demo-stop] nothing to stop."
    exit 0
}

$stopped = 0
foreach ($p in $procs) {
    $port = ''
    if ($p.CommandLine -match '--server\.port=(\d+)') { $port = " (port $($matches[1]))" }
    try {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
        Write-Host "[demo-stop] stopped pid $($p.ProcessId)$port"
        $stopped++
    } catch {
        Write-Warning "[demo-stop] could not stop pid $($p.ProcessId): $($_.Exception.Message)"
    }
}

Start-Sleep -Seconds 3

$left = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*sales-analysis-server.jar*' }
Write-Host "[demo-stop] stopped $stopped process(es); still running: $(@($left).Count)"
