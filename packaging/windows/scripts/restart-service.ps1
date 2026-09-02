<#
.SYNOPSIS
    Stops and starts the Dot Matrix Print Agent Windows service, so it
    picks up any configuration change written to config.json since it
    last started (the running process does not hot-reload it).
.PARAMETER NoElevate
    Skip the self-elevation check. Used by the Inno Setup installer.
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
    throw "Service executable not found at '$serviceExe'."
}

Write-Host "Restarting the 'Dot Matrix Print Agent' service..."
& $serviceExe restart 2>&1 | ForEach-Object { Write-Host "  $_" }
Start-Sleep -Seconds 2
$status = & $serviceExe status 2>&1
if ($status -notmatch 'Started') {
    throw "Service did not reach the 'Started' state after restart (status: '$status'). Check '$installRoot\logs' for details."
}

Write-Host "Done." -ForegroundColor Green
