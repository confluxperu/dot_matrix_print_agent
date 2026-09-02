; Inno Setup script for the Dot Matrix Print Agent.
;
; Built separately for x86 and x64 (each bundles a matching-architecture
; portable JRE 8 and WinSW binary - see build-installer.ps1, which stages
; the input files this script packages under ..\dist\<arch>).
;
; Compile with:
;   ISCC.exe /DAppArch=x86 installer.iss
;   ISCC.exe /DAppArch=x64 installer.iss
; (build-installer.ps1 does this for you.)

#ifndef AppArch
  #define AppArch "x64"
#endif

#define AppName "Dot Matrix Print Agent"
#define AppVersion "1.0.0"
#define AppPublisher "Conflux"
#define AppURL "https://github.com/confluxperu"
#define DistDir "..\dist\" + AppArch

[Setup]
AppId={{48E59B0A-8CCF-42DA-892F-DE75D50910D4}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
DefaultDirName={autopf}\DotMatrixPrintAgent
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
OutputDir=..\output
OutputBaseFilename=DotMatrixPrintAgentSetup-{#AppArch}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\dotmatrix-print-agent.jar
#if AppArch == "x64"
ArchitecturesInstallIn64BitMode=x64
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
; To add Spanish wizard text, install Inno Setup's official translation
; files (Languages\Spanish.isl) and uncomment:
; Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Files]
Source: "{#DistDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Dirs]
; Shared config (ConfigStore.java resolves here on Windows) - created here,
; before the service ever runs, so a standard (non-admin) user can still
; save printer settings by double-clicking the jar directly instead of
; using the "Configure Printers" shortcut.
Name: "{commonappdata}\DotMatrixPrintAgent"; Permissions: users-modify

[Icons]
Name: "{group}\Configure Printers"; Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\configure-printers.ps1"""; \
    WorkingDir: "{app}"; IconFilename: "{app}\jre\bin\javaw.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\configure-printers.ps1"""; \
    WorkingDir: "{app}"; IconFilename: "{app}\jre\bin\javaw.exe"; \
    Comment: "Pick the default printer for the Dot Matrix Print Agent"
Name: "{group}\Restart Service"; Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\restart-service.ps1"""; \
    WorkingDir: "{app}"
Name: "{group}\View Service Logs"; Filename: "{app}\logs"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\install-service.ps1"" -NoElevate"; \
    WorkingDir: "{app}"; StatusMsg: "Registering the background service..."; Flags: runhidden waituntilterminated

[UninstallRun]
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\uninstall-service.ps1"" -NoElevate"; \
    WorkingDir: "{app}"; RunOnceId: "StopService"; Flags: runhidden waituntilterminated

[UninstallDelete]
Type: filesandordirs; Name: "{app}\logs"

[Code]
// Runs after the user confirms install but before [Files] copies anything.
// On a fresh install there is nothing here yet; on an update over an
// existing install, the old service is still running and holds the old
// jar/JRE files open - without stopping it first, the Files step below
// would fail with a sharing violation. [Run]'s install-service.ps1
// re-registers and starts the (possibly updated) service afterwards.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ServiceExe: String;
  ResultCode: Integer;
begin
  Result := '';
  ServiceExe := ExpandConstant('{app}\DotMatrixPrintAgentService.exe');
  if FileExists(ServiceExe) then
  begin
    Exec(ServiceExe, 'stop', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec(ServiceExe, 'uninstall', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;
