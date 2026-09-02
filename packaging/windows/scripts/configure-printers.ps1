<#
.SYNOPSIS
    Opens the Dot Matrix Print Agent's configuration window to pick the
    default printer, then restarts the background service so the change
    takes effect immediately.
.DESCRIPTION
    The background service and this configuration window are two separate
    processes; they cannot both hold the local HTTP port at once, and a
    change made here would not be picked up by an already-running service
    until it restarts. This script stops the service, waits for the
    window to close, then starts the service again - so the same
    "Configure Printers" shortcut always does the right thing.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir '_elevate.ps1')
Assert-Elevated -ScriptPath $MyInvocation.MyCommand.Path

$installRoot = Split-Path -Parent $scriptDir
$serviceExe = Join-Path $installRoot 'DotMatrixPrintAgentService.exe'
$javaw = Join-Path $installRoot 'jre\bin\javaw.exe'
$jar = Join-Path $installRoot 'dotmatrix-print-agent.jar'

if (-not (Test-Path $javaw)) { throw "Bundled Java runtime not found at '$javaw'." }
if (-not (Test-Path $jar)) { throw "Application jar not found at '$jar'." }

$serviceWasRunning = $false
if (Test-Path $serviceExe) {
    $status = & $serviceExe status 2>&1
    if ($status -match 'Started') {
        $serviceWasRunning = $true
        Write-Host "Stopping the background service so the configuration window can open..."
        & $serviceExe stop 2>&1 | Out-Null
        Start-Sleep -Seconds 1
    }
}

Write-Host "Opening the Dot Matrix Print Agent configuration window..."
Write-Host "(closing that window will restart the background service automatically)"
Start-Process -FilePath $javaw -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $installRoot -Wait

if ($serviceWasRunning -and (Test-Path $serviceExe)) {
    Write-Host "Restarting the background service with the updated configuration..."
    & $serviceExe start 2>&1 | Out-Null
    Write-Host "Done." -ForegroundColor Green
}
