<#
.SYNOPSIS
    Builds Windows x86 and x64 installers for the Dot Matrix Print Agent.
.DESCRIPTION
    1. Builds the (architecture-independent) jar with Maven.
    2. Downloads a portable JRE 8 and WinSW (pinned versions, verified by
       SHA-256) for both x86 and x64, and stages a self-contained
       "dist\<arch>" folder for each - jar + JRE + WinSW service wrapper
       + management scripts. Each staged folder is a ready-to-use portable
       install on its own: run "scripts\install-service.ps1" from an
       elevated PowerShell.
    3. If Inno Setup's compiler (ISCC.exe) is available, also compiles a
       polished installer for each architecture into ".\output".
.NOTES
    Run this on a real Windows machine (PowerShell 5.1+) with internet
    access and Maven on PATH. Inno Setup (https://jrsoftware.org/isinfo.php,
    free) is optional but recommended - without it you still get the
    portable "dist\<arch>" folders.
#>
[CmdletBinding()]
param(
    [switch]$SkipInnoSetup
)

$ErrorActionPreference = 'Stop'

# --- Pinned, checksum-verified third-party downloads --------------------
# BellSoft Liberica JRE 8 is used because it is, as of this writing, the
# only mainstream OpenJDK distribution still publishing a 32-bit (x86)
# Windows build for Java 8 - Adoptium/Temurin and Azul Zulu both dropped
# Windows x86 entirely. Bump these together (URL + sha256) when updating.
$JreX64Url = 'https://github.com/bell-sw/Liberica/releases/download/8u504%2B1/bellsoft-jre8u504%2B1-windows-amd64.zip'
$JreX64Sha256 = '429B3E79A68A326315306F854172933FD671698DA64A1CEC41D97A45324614D5'
$JreX86Url = 'https://github.com/bell-sw/Liberica/releases/download/8u504%2B1/bellsoft-jre8u504%2B1-windows-i586.zip'
$JreX86Sha256 = '0A3245B45CBDD42CCE258C38CA21222D4E446A2443B0C4F9915BA704AC879D11'
$WinSwX64Url = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
$WinSwX64Sha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$WinSwX86Url = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x86.exe'
$WinSwX86Sha256 = '0C21327463A43A61F2EFB227EC4AFD2467FDE91618CC725148C1099001CA91AE'

# --- Paths ----------------------------------------------------------------
$windowsDir = Split-Path -Parent $MyInvocation.MyCommand.Path      # packaging\windows
$repoRoot = Split-Path -Parent (Split-Path -Parent $windowsDir)     # project root (pom.xml)
$cacheDir = Join-Path $windowsDir '.cache'
$distDir = Join-Path $windowsDir 'dist'
$outputDir = Join-Path $windowsDir 'output'
$installerScript = Join-Path $windowsDir 'installer\installer.iss'

New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

function Get-CachedFile {
    param([string]$Url, [string]$Sha256, [string]$FileName)
    $dest = Join-Path $cacheDir $FileName
    if (Test-Path $dest) {
        $hash = (Get-FileHash -Path $dest -Algorithm SHA256).Hash
        if ($hash -eq $Sha256) {
            Write-Host "  using cached $FileName"
            return $dest
        }
        Write-Warning "  cached $FileName has an unexpected checksum, re-downloading"
        Remove-Item $dest -Force
    }
    Write-Host "  downloading $FileName ..."
    Invoke-WebRequest -Uri $Url -OutFile $dest -UseBasicParsing
    $hash = (Get-FileHash -Path $dest -Algorithm SHA256).Hash
    if ($hash -ne $Sha256) {
        Remove-Item $dest -Force
        throw "Checksum mismatch for $FileName`nExpected: $Sha256`nActual:   $hash"
    }
    return $dest
}

function Expand-JreZip {
    param([string]$ZipPath, [string]$TargetJreDir)
    $tmpExtract = Join-Path $cacheDir ("extract_" + [Guid]::NewGuid().ToString('N'))
    Expand-Archive -Path $ZipPath -DestinationPath $tmpExtract -Force
    $inner = Get-ChildItem -Path $tmpExtract -Directory | Select-Object -First 1
    if (-not $inner) { throw "Unexpected JRE zip layout in $ZipPath" }
    if (Test-Path $TargetJreDir) { Remove-Item $TargetJreDir -Recurse -Force }
    Move-Item -Path $inner.FullName -Destination $TargetJreDir
    Remove-Item $tmpExtract -Recurse -Force
}

function New-StagedDist {
    param([string]$Arch, [string]$JreZip, [string]$JreSha256, [string]$WinSwExe, [string]$WinSwSha256)

    Write-Host ""
    Write-Host "== Staging dist\$Arch ==" -ForegroundColor Cyan
    $archDist = Join-Path $distDir $Arch
    if (Test-Path $archDist) { Remove-Item $archDist -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $archDist | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $archDist 'logs') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $archDist 'scripts') | Out-Null

    Write-Host "Fetching JRE ($Arch)..."
    $jreZipPath = Get-CachedFile -Url $JreZip -Sha256 $JreSha256 -FileName "jre-$Arch.zip"
    Write-Host "Extracting JRE..."
    Expand-JreZip -ZipPath $jreZipPath -TargetJreDir (Join-Path $archDist 'jre')

    Write-Host "Fetching WinSW ($Arch)..."
    $winswPath = Get-CachedFile -Url $WinSwExe -Sha256 $WinSwSha256 -FileName "WinSW-$Arch.exe"
    Copy-Item $winswPath (Join-Path $archDist 'DotMatrixPrintAgentService.exe')

    Copy-Item (Join-Path $windowsDir 'service\DotMatrixPrintAgentService.xml') $archDist
    Copy-Item (Join-Path $windowsDir 'scripts\*.ps1') (Join-Path $archDist 'scripts')

    $jarPath = Join-Path $repoRoot 'target\dotmatrix-print-agent.jar'
    Copy-Item $jarPath (Join-Path $archDist 'dotmatrix-print-agent.jar')

    Write-Host "dist\$Arch ready." -ForegroundColor Green
    return $archDist
}

# --- 1. Build the jar -------------------------------------------------
Write-Host "== Building dotmatrix-print-agent.jar with Maven ==" -ForegroundColor Cyan
Push-Location $repoRoot
try {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
if (-not (Test-Path (Join-Path $repoRoot 'target\dotmatrix-print-agent.jar'))) {
    throw "Build did not produce target\dotmatrix-print-agent.jar"
}

# --- 2. Stage both architectures ---------------------------------------
$distX86 = New-StagedDist -Arch 'x86' -JreZip $JreX86Url -JreSha256 $JreX86Sha256 `
    -WinSwExe $WinSwX86Url -WinSwSha256 $WinSwX86Sha256
$distX64 = New-StagedDist -Arch 'x64' -JreZip $JreX64Url -JreSha256 $JreX64Sha256 `
    -WinSwExe $WinSwX64Url -WinSwSha256 $WinSwX64Sha256

# --- 3. Compile installers with Inno Setup, if available ----------------
if ($SkipInnoSetup) {
    Write-Host ""
    Write-Host "Skipping Inno Setup step (-SkipInnoSetup)." -ForegroundColor Yellow
} else {
    $isccPath = $null
    $isccCmd = Get-Command 'ISCC.exe' -ErrorAction SilentlyContinue
    if ($isccCmd) {
        $isccPath = $isccCmd.Source
    } else {
        foreach ($candidate in @(
            "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
            "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
        )) {
            if (Test-Path $candidate) { $isccPath = $candidate; break }
        }
    }

    if (-not $isccPath) {
        Write-Host ""
        Write-Warning "Inno Setup (ISCC.exe) was not found - skipping installer .exe generation."
        Write-Warning "Install it from https://jrsoftware.org/isinfo.php and re-run this script,"
        Write-Warning "or distribute the portable 'dist\x86' / 'dist\x64' folders as-is (see README.md)."
    } else {
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        foreach ($arch in @('x86', 'x64')) {
            Write-Host ""
            Write-Host "== Compiling the $arch installer with Inno Setup ==" -ForegroundColor Cyan
            & $isccPath "/DAppArch=$arch" "/O$outputDir" $installerScript
            if ($LASTEXITCODE -ne 0) { throw "ISCC failed for $arch with exit code $LASTEXITCODE." }
        }
        Write-Host ""
        Write-Host "Installers written to '$outputDir'." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Build finished." -ForegroundColor Green
