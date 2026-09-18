# =====================================================================
#  demo-start.ps1
#
#  Purpose : start the backend for a live demo / defense presentation.
#            Starts up to TWO instances so you can switch data sources
#            by switching browser tabs (no restart, no waiting):
#
#              port 8080 -> sales_analysis   (Olist real dataset, BRL)
#              port 8081 -> sales_demo_mock  (simulated dataset, CNY)
#
#  Usage (from the project root, in PowerShell):
#      powershell -ExecutionPolicy Bypass -File scripts\demo-start.ps1
#      powershell -ExecutionPolicy Bypass -File scripts\demo-start.ps1 -RealOnly
#      powershell -ExecutionPolicy Bypass -File scripts\demo-start.ps1 -MockOnly
#
#  Logs go to logs\demo-<port>.log
#  Stop everything with: scripts\demo-stop.ps1
#
#  NOTE: kept in pure ASCII on purpose. PowerShell 5.1 reads .ps1 as ANSI
#        when there is no BOM, so non-ASCII comments can corrupt the script.
# =====================================================================
param(
    [int]$RealPort = 8080,
    [int]$MockPort = 8081,
    [switch]$RealOnly,
    [switch]$MockOnly
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root      = Split-Path -Parent $ScriptDir
$Jar       = Join-Path $Root 'server\target\sales-analysis-server.jar'
$LogDir    = Join-Path $Root 'logs'

function Write-Step($msg) { Write-Host "[demo-start] $msg" }

# ---------------------------------------------------------------- java
$JavaExe = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $JavaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif (Test-Path 'D:\Java\jdk1.8.0_212\bin\java.exe') {
    $JavaExe = 'D:\Java\jdk1.8.0_212\bin\java.exe'
} else {
    $JavaExe = 'java.exe'
}
Write-Step "java      : $JavaExe"

if (-not (Test-Path $Jar)) {
    throw "backend jar not found: $Jar`nRun this first:  mvn -f pom.xml -pl server -am clean package -DskipTests"
}
Write-Step "jar       : $Jar ($([math]::Round((Get-Item $Jar).Length/1MB,2)) MB)"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }

# ------------------------------------------------------------- mysql
$svc = Get-Service -Name '*mysql*' -ErrorAction SilentlyContinue
if ($svc) {
    $state = ($svc | Where-Object { $_.Name -like '*SQL*' } | Select-Object -First 1).Status
    Write-Step "mysql     : $state"
    if ($state -ne 'Running') {
        Write-Warning "MySQL does not look like it is running. Start it before the demo."
    }
}

function Test-Port($port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return [bool]$c
}

function Start-One {
    param(
        [int]$Port,
        [string]$Title,
        [string[]]$ExtraArgs
    )

    if (Test-Port $Port) {
        Write-Warning "port $Port is already in use - skipping $Title. Run scripts\demo-stop.ps1 first."
        return $null
    }

    $stdout = Join-Path $LogDir "demo-$Port.log"
    $stderr = Join-Path $LogDir "demo-$Port.err.log"

    # NOTE: do not name this $args - that is a PowerShell automatic variable
    $argList = @('-Xmx512m', '-Dfile.encoding=UTF-8', '-jar', $Jar, "--server.port=$Port") + $ExtraArgs

    $p = Start-Process -FilePath $JavaExe -ArgumentList $argList `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
            -WindowStyle Hidden -PassThru

    Write-Step "starting $Title on port $Port (pid $($p.Id)) ..."
    return $p
}

# ------------------------------------------------------------- start
$started = @()

if (-not $MockOnly) {
    $p = Start-One -Port $RealPort -Title 'REAL dataset (Olist)' -ExtraArgs @()
    if ($p) { $started += [pscustomobject]@{ Name='REAL (Olist)  '; Port=$RealPort; Proc=$p } }
}

if (-not $RealOnly) {
    $jdbc = "jdbc:mysql://127.0.0.1:3306/sales_demo_mock?useUnicode=true&characterEncoding=utf8&useSSL=false&allowMultiQueries=true"
    $p = Start-One -Port $MockPort -Title 'SIMULATED dataset' -ExtraArgs @("--spring.datasource.url=$jdbc")
    if ($p) { $started += [pscustomobject]@{ Name='SIM (simulated)'; Port=$MockPort; Proc=$p } }
}

if (-not $started) {
    Write-Step "nothing started."
    exit 0
}

# -------------------------------------------------------- wait ready
Write-Step "waiting for the service(s) to become ready ..."
foreach ($s in $started) {
    $url = "http://127.0.0.1:$($s.Port)/api/overview"
    $ready = $false
    foreach ($i in 1..40) {
        Start-Sleep -Milliseconds 1000
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        if ($s.Proc.HasExited) { break }
    }
    if ($ready) {
        Write-Step "  $($s.Name)  ready  -> http://localhost:$($s.Port)/"
        $s | Add-Member -NotePropertyName Ready -NotePropertyValue $true
    } else {
        Write-Warning "  $($s.Name)  NOT ready. Check logs\demo-$($s.Port).log"
        $s | Add-Member -NotePropertyName Ready -NotePropertyValue $false
    }
}

Write-Host ""
Write-Host "================= DEMO ENDPOINTS ================="
foreach ($s in $started) {
    $mark = if ($s.Ready) { 'OK ' } else { 'ERR' }
    Write-Host ("  [{0}] {1}  http://localhost:{2}/" -f $mark, $s.Name, $s.Port)
}
Write-Host ""
Write-Host "  dashboard : http://localhost:$RealPort/"
Write-Host "  monitor   : http://localhost:$RealPort/#/monitor"
Write-Host "  api docs  : http://localhost:$RealPort/swagger-ui/index.html"
if (-not $RealOnly) {
    Write-Host "  (same paths on port $MockPort for the simulated dataset)"
}
Write-Host ""
Write-Host "  stop all  : scripts\demo-stop.ps1"
Write-Host "=================================================="
