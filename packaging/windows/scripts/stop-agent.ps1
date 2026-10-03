<#
.SYNOPSIS
    Closes every running copy of the Dot Matrix Print Agent started from
    the given install folder (in any user's session), so Setup can replace
    or remove the jar and the bundled JRE.
.DESCRIPTION
    Run elevated by the installer before copying files and by the
    uninstaller before deleting them. Only java/javaw processes whose
    executable lives under "<InstallDir>\jre\" are touched - other Java
    programs on the machine keep running. Written for PowerShell 2.0 so it
    also works on a stock Windows 7.
.PARAMETER InstallDir
    The agent's install folder, e.g. "C:\Program Files\DotMatrixPrintAgent".
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InstallDir
)

$prefix = (Join-Path $InstallDir 'jre\').ToLowerInvariant()

function Get-AgentProcesses {
    Get-WmiObject Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.ToLowerInvariant().StartsWith($prefix) }
}

foreach ($proc in @(Get-AgentProcesses)) {
    Write-Host "Closing Dot Matrix Print Agent (PID $($proc.ProcessId))..."
    [void]$proc.Terminate()
}

# Terminate() returns before the process is gone; wait (up to ~10 s) so its
# open files are released before Setup touches them.
for ($i = 0; $i -lt 20 -and @(Get-AgentProcesses).Count -gt 0; $i++) {
    Start-Sleep -Milliseconds 500
}
