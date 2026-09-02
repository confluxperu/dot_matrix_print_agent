# Shared helper, dot-sourced by the other scripts in this folder.
# Re-launches the current script elevated (UAC prompt) unless already
# running as Administrator or called with -NoElevate (used by the Inno
# Setup installer, which is already elevated during install/uninstall).

function Assert-Elevated {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$ScriptArgs = @(),
        [switch]$NoElevate
    )

    $principal = New-Object Security.Principal.WindowsPrincipal(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)

    if ($isAdmin -or $NoElevate) {
        return
    }

    Write-Host "Administrator rights are required - requesting elevation..."
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$ScriptPath`"") + $ScriptArgs
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList $argList -Verb RunAs -PassThru -Wait
    exit $proc.ExitCode
}
