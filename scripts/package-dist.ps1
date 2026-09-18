# =====================================================================
#  package-dist.ps1
#
#  作用：生成"可以直接交给别人"的交付包（zip）。
#        自带 交付说明.md，接收方照着走就能跑起来。
#
#  用法（在项目根目录执行）：
#      powershell -ExecutionPolicy Bypass -File scripts\package-dist.ps1
#      powershell -ExecutionPolicy Bypass -File scripts\package-dist.ps1 -SourceOnly
#      powershell -ExecutionPolicy Bypass -File scripts\package-dist.ps1 -NoDump
#
#  参数：
#      -SourceOnly   只打包源码（约 2MB），不带预构建 jar 与数据库快照
#      -NoDump       不包含数据库快照
#      -OutDir       输出目录，默认 dist
#
#  说明：本脚本刻意保持纯 ASCII（不写中文），避免 PowerShell 5.1
#        按 ANSI 读取 .ps1 时把中文读成乱码。中文内容放在模板文件里由脚本复制。
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
#    名字带时间戳，所以不需要删除已存在的目录/文件（也更安全）
# ---------------------------------------------------------------------
if (Test-Path $Stage) { throw "staging dir already exists, retry in a minute: $Stage" }
if (Test-Path $ZipPath) { throw "zip already exists, retry in a minute: $ZipPath" }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

# ---------------------------------------------------------------------
# 1. copy clean source with robocopy
#    excluded: node_modules / dist / target / IDE dirs / logs / temp files
#    .git is intentionally KEPT (shows the commit history)
# ---------------------------------------------------------------------
$SrcDst = Join-Path $Stage "spark-sales-analysis"
Write-Host "[package-dist] copying source (excluding build artifacts) ..."

$roboArgs = @(
    $Root, $SrcDst,
    "/E",
    "/XD", "node_modules", "dist", "target", ".idea", ".vscode", "out", "build",
    (Join-Path $Root "data\raw"),
    "/XF", "*.log", "*.tmp", "*.iml", ".DS_Store", "Thumbs.db",
    "/NFL", "/NDL", "/NJH", "/NJS", "/NP"
)
$null = robocopy @roboArgs
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with exit code $LASTEXITCODE" }

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
# ---------------------------------------------------------------------
Write-Host "[package-dist] compressing ..."
Compress-Archive -Path $Stage -DestinationPath $ZipPath -CompressionLevel Optimal

$zipMB = [math]::Round((Get-Item $ZipPath).Length / 1MB, 2)
$dstMB = [math]::Round(((Get-ChildItem $Stage -Recurse -File -Force | Measure-Object Length -Sum).Sum) / 1MB, 2)

Write-Host ""
Write-Host "[package-dist] DONE"
Write-Host "[package-dist]   folder : $Stage  ($dstMB MB)"
Write-Host "[package-dist]   zip    : $ZipPath  ($zipMB MB)"
Write-Host "[package-dist]   hand this zip (or the folder) to the receiver."
Write-Host ""
