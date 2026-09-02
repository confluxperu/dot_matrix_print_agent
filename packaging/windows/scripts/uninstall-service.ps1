<#
.SYNOPSIS
    Stops and unregisters the Dot Matrix Print Agent Windows service.
    Does not delete any files or the printer configuration.
.PARAMETER NoElevate
    Skip the self-elevation check. Used by the Inno Setup uninstaller,
    which already runs this elevated.
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
    Write-Warning "Service executable not found at '$serviceExe' - nothing to uninstall."
    exit 0
}

Write-Host "Stopping the 'Dot Matrix Print Agent' service (if running)..."
& $serviceExe stop 2>&1 | Out-Null

Write-Host "Removing the service registration..."
& $serviceExe uninstall 2>&1 | Out-Null

Write-Host "Done. The service has been removed." -ForegroundColor Green
Write-Host "Printer configuration in '$env:ProgramData\DotMatrixPrintAgent' was left untouched."
