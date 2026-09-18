# =====================================================================
#  package-dist.ps1
#
#  Build a ready-to-hand-over package (zip) of this project.
#  The package contains a receiver-facing readme, so whoever gets it can
#  follow that file instead of asking you how to run things.
#
#  Usage (run in PowerShell, from the project root):
#      powershell -ExecutionPolicy Bypass -File scripts\package-dist.ps1
#      ... -SourceOnly      source only (~2 MB), no prebuilt jars / db dump
#      ... -NoDump          include prebuilt jars but skip the db snapshot
#      ... -OutDir dist     output folder, default "dist"
#
#  NOTE: this script is deliberately pure ASCII. Windows PowerShell 5.1
#        reads .ps1 as ANSI when the file has no BOM, so any Chinese text
#        here would turn into garbage and can even break parsing.
#        Chinese content lives in scripts/templates/ and is copied as a file.
# =====================================================================
param(
    [switch]$SourceOnly,
    [switch]$NoDump,
    [string]$OutDir = "dist"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root      = Split-Path -Parent $ScriptDir
$Stamp     = Get-Date -Format "yyyyMMdd_HHmm"
$PkgName   = "spark-sales-analysis-delivery_$Stamp"
$StageRoot = Join-Path $Root $OutDir
$Stage     = Join-Path $StageRoot $PkgName
$ZipPath   = Join-Path $StageRoot "$PkgName.zip"

Write-Host "[package-dist] project root : $Root"
Write-Host "[package-dist] output       : $ZipPath"

if (-not (Test-Path $Root)) { throw "project root not found: $Root" }

# ---------------------------------------------------------------------
# 0. staging area
#    the folder name carries a timestamp, so nothing needs to be deleted
# ---------------------------------------------------------------------
if (Test-Path $Stage) { throw "staging dir already exists, retry in a minute: $Stage" }
if (Test-Path $ZipPath) { throw "zip already exists, retry in a minute: $ZipPath" }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

# ---------------------------------------------------------------------
# 1. copy clean source with robocopy
#    excluded: build artifacts / IDE dirs / logs / the WHOLE data dir
#    .git is intentionally KEPT (it carries the commit history)
#
#    The whole data dir is excluded because it holds generated mock data,
#    the downloaded raw dataset and the converted ODS output (100+ MB),
#    all of which can be re-created by scripts. Only data/sample is copied
#    back, so the receiver can still see what the data looks like.
# ---------------------------------------------------------------------
$SrcDst = Join-Path $Stage "spark-sales-analysis"
Write-Host "[package-dist] copying source (excluding build artifacts) ..."

$roboArgs = @(
    $Root, $SrcDst,
    "/E",
    "/XD", "node_modules", "dist", "target", ".idea", ".vscode", "out", "build",
    (Join-Path $Root "data"),
    "/XF", "*.log", "*.tmp", "*.iml", ".DS_Store", "Thumbs.db",
    "/NFL", "/NDL", "/NJH", "/NJS", "/NP"
)
$null = robocopy @roboArgs
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with exit code $LASTEXITCODE" }

# put back the small sample data that ships with the repo
$sampleSrc = Join-Path $Root "data\sample"
if (Test-Path $sampleSrc) {
    $sampleDst = Join-Path $SrcDst "data\sample"
    New-Item -ItemType Directory -Force -Path $sampleDst | Out-Null
    Copy-Item (Join-Path $sampleSrc "*") $sampleDst -Recurse -Force
    Write-Host "[package-dist]   + data/sample (small samples only)"
}

$srcFiles = (Get-ChildItem $SrcDst -Recurse -File -Force | Measure-Object).Count
Write-Host "[package-dist]   source files copied: $srcFiles"

# ---------------------------------------------------------------------
# 2. prebuilt artifacts (skipped when -SourceOnly)
# ---------------------------------------------------------------------
if (-not $SourceOnly) {
    $Pre = Join-Path $Stage "prebuilt"
    New-Item -ItemType Directory -Force -Path $Pre | Out-Null

    $serverJar = Join-Path $Root "server\target\sales-analysis-server.jar"
    if (Test-Path $serverJar) {
        Copy-Item $serverJar $Pre -Force
        Write-Host "[package-dist]   + sales-analysis-server.jar"
    } else {
        Write-Warning "server jar not found: run 'mvn -pl server -am package -DskipTests' first"
    }

    $jobJar = Join-Path $Root "spark-job\target\spark-job-1.0.0-cluster.jar"
    if (Test-Path $jobJar) {
        Copy-Item $jobJar $Pre -Force
        Write-Host "[package-dist]   + spark-job-1.0.0-cluster.jar"
    } else {
        Write-Warning "cluster jar not found: run 'mvn -pl spark-job -am -Pcluster-package package -DskipTests' first"
    }

    if (-not $NoDump) {
        $dump = Join-Path $Root "dist\sales_analysis-dump.sql.gz"
        if (Test-Path $dump) {
            Copy-Item $dump $Pre -Force
            Write-Host "[package-dist]   + sales_analysis-dump.sql.gz"
        } else {
            Write-Warning "db dump not found: see docs/11 'section 6' to export it with mysqldump"
        }
    }
}

# ---------------------------------------------------------------------
# 3. receiver-facing readme (copied from template, avoids non-ASCII in .ps1)
# ---------------------------------------------------------------------
$cnReadme = [string]([char]0x4EA4 + [char]0x4ED8 + [char]0x8BF4 + [char]0x660E + ".md")
$tpl    = Join-Path $Root ("scripts\templates\README-" + $cnReadme)
$readme = Join-Path $Stage $cnReadme
if (Test-Path $tpl) {
    Copy-Item $tpl $readme -Force
    Write-Host "[package-dist]   + receiver readme ($cnReadme)"
} else {
    Write-Warning "delivery readme template not found: $tpl"
}

# ---------------------------------------------------------------------
# 4. zip it
#
#    Use Windows' built-in tar.exe (bsdtar) instead of Compress-Archive:
#    Compress-Archive silently SKIPS hidden items, and git marks .git as
#    hidden - so the commit history would be missing from the package.
#    Clearing the attribute is not enough (verified), tar just works.
#    Compress-Archive is kept as a fallback for very old Windows builds.
# ---------------------------------------------------------------------
$srcTree = Join-Path $Stage "spark-sales-analysis"
$tarExe = Join-Path $env:SystemRoot "System32\tar.exe"
$usedTar = $false

if (Test-Path $tarExe) {
    Write-Host "[package-dist] compressing with tar.exe (includes hidden dirs) ..."
    $parent = Split-Path $Stage -Parent
    $leaf = Split-Path $Stage -Leaf
    Push-Location $parent
    try {
        & $tarExe -a -c -f $ZipPath $leaf | Out-Null
        $usedTar = ($LASTEXITCODE -eq 0) -and (Test-Path $ZipPath)
        if (-not $usedTar) { Write-Warning "tar.exe failed (exit $LASTEXITCODE), falling back" }
    } finally {
        Pop-Location
    }
}

if (-not $usedTar) {
    Write-Warning "tar.exe not available - falling back to Compress-Archive (.git may be skipped)"
    if (Test-Path $srcTree) {
        & attrib.exe -H -S "$srcTree\*" /S /D | Out-Null
    }
    Write-Host "[package-dist] compressing ..."
    Compress-Archive -Path $Stage -DestinationPath $ZipPath -CompressionLevel Optimal
}

# ---------------------------------------------------------------------
# 5. self check: make sure .git history really made it into the zip
# ---------------------------------------------------------------------
$zipMB = [math]::Round((Get-Item $ZipPath).Length / 1MB, 2)
$stageMB = [math]::Round(((Get-ChildItem $Stage -Recurse -File -Force | Measure-Object Length -Sum).Sum) / 1MB, 2)

$hasGit = $false
try {
    # zip stores entry names uncompressed, so a byte-preserving text scan finds them.
    # (ISO-8859-1 maps every byte 1:1 to a char, so nothing is lost or mangled.)
    $raw = [System.IO.File]::ReadAllText($ZipPath, [System.Text.Encoding]::GetEncoding(28591))
    $hasGit = $raw.Contains(".git/HEAD")
} catch {
    Write-Warning "could not inspect the zip automatically: $($_.Exception.Message)"
}

if (-not $hasGit) {
    Write-Warning "the zip does not seem to contain .git - the receiver will not get the commit history."
    Write-Warning "check whether attrib.exe cleared the hidden attribute on the staged copy."
}

Write-Host ""
Write-Host "[package-dist] DONE"
Write-Host "[package-dist]   folder : $Stage  ($stageMB MB)"
Write-Host "[package-dist]   zip    : $ZipPath  ($zipMB MB)"
Write-Host "[package-dist]   .git history included : $hasGit"
Write-Host "[package-dist]   hand this zip (or the folder) to the receiver."
Write-Host ""
