<#
.SYNOPSIS
    Registers and starts the Dot Matrix Print Agent as a Windows service
    (set to start automatically on boot), then run this script.
.DESCRIPTION
    Expects to live in a "scripts" subfolder of the install root, next to:
      ..\DotMatrixPrintAgentService.exe   (WinSW, renamed)
      ..\DotMatrixPrintAgentService.xml
      ..\dotmatrix-print-agent.jar
      ..\jre\bin\javaw.exe
    Safe to re-run: WinSW's "install" is a no-op if the service already
    exists, and "start" is a no-op if it is already running.
.PARAMETER NoElevate
    Skip the self-elevation check. Used by the Inno Setup installer, which
    already runs this elevated.
#>
[CmdletBinding()]
param(
    [switch]$NoElevate
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir '_elevate.ps1')
Assert-Elevated -ScriptPath $MyInvocation.MyCommand.Path -NoElevate:$NoElevate

$installRoot = Split-Path -Parent $scriptDir
$serviceExe = Join-Path $installRoot 'DotMatrixPrintAgentService.exe'

if (-not (Test-Path $serviceExe)) {
    throw "Service executable not found at '$serviceExe'. Run this script from the installed 'scripts' folder, not a copy."
}

# Shared config (see ConfigStore.java) - create it up front with write
# access for standard (non-admin) users, so the interactive "Configure
# Printers" window can save printer settings even though the service
# itself runs as Local System. The Inno Setup installer does the same via
# its own [Dirs] entry; this covers the portable (no-installer) path.
$configDir = Join-Path $env:ProgramData 'DotMatrixPrintAgent'
New-Item -ItemType Directory -Force -Path $configDir | Out-Null
& icacls $configDir /grant '*S-1-5-32-545:(OI)(CI)M' /T | Out-Null

Write-Host "Installing the 'Dot Matrix Print Agent' Windows service..."
& $serviceExe install 2>&1 | ForEach-Object { Write-Host "  $_" }
# Rather than rely on WinSW's exact exit code for "already installed" vs.
# a real failure, confirm the end state directly: "status" reliably
# prints "Started" or "Stopped" once the service is registered.
$status = & $serviceExe status 2>&1
if ($status -notmatch 'Started|Stopped') {
    throw "Could not confirm the service was installed (status: '$status')."
}

Write-Host "Starting the service..."
& $serviceExe start 2>&1 | ForEach-Object { Write-Host "  $_" }
Start-Sleep -Seconds 2
$status = & $serviceExe status 2>&1
if ($status -notmatch 'Started') {
    throw "Service did not reach the 'Started' state (status: '$status'). Check '$installRoot\logs' for details."
}

Write-Host ""
Write-Host "Done. The Dot Matrix Print Agent is now running as a Windows service" -ForegroundColor Green
Write-Host "and will start automatically the next time this computer boots." -ForegroundColor Green
Write-Host "Use the 'Configure Printers' Start Menu shortcut to pick the default printer."
