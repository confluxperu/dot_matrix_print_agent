; Inno Setup script for the Dot Matrix Print Agent.
;
; Built separately for x86 and x64 (each bundles a matching-architecture
; portable JRE 8 - see build-installer.ps1, which stages the input files
; this script packages under ..\dist\<arch>).
;
; The agent runs as a normal desktop app in the system tray of whoever is
; logged in, started at every logon through the machine-wide "Run" key -
; not as a Windows service (see ..\README.md for why).
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
#define JavaW "{app}\jre\bin\javaw.exe"
#define Jar "{app}\dotmatrix-print-agent.jar"

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
; PrepareToInstall already closes the agent; this only catches anything
; else still holding a file in {app}.
CloseApplications=force
RestartApplications=no
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
; Also kept inside Setup itself, so PrepareToInstall can run it before
; anything is copied (an older install may not have it yet).
Source: "..\scripts\stop-agent.ps1"; Flags: dontcopy

[InstallDelete]
; Leftovers of the Windows service used up to v1.2.x (PrepareToInstall has
; already stopped and unregistered it by the time this runs).
Type: files; Name: "{app}\DotMatrixPrintAgentService.exe"
Type: files; Name: "{app}\DotMatrixPrintAgentService.xml"
Type: files; Name: "{app}\scripts\_elevate.ps1"
Type: files; Name: "{app}\scripts\configure-printers.ps1"
Type: files; Name: "{app}\scripts\install-service.ps1"
Type: files; Name: "{app}\scripts\restart-service.ps1"
Type: files; Name: "{app}\scripts\uninstall-service.ps1"
Type: filesandordirs; Name: "{app}\logs"
Type: files; Name: "{group}\Configure Printers.lnk"
Type: files; Name: "{group}\Restart Service.lnk"
Type: files; Name: "{group}\View Service Logs.lnk"

[Dirs]
; Shared config (ConfigStore.java resolves here on Windows): every user who
; logs in on this computer runs the agent unelevated and must be able to
; save printer settings.
Name: "{commonappdata}\DotMatrixPrintAgent"; Permissions: users-modify

[Registry]
; Starts the agent, straight into the tray, at every user's logon.
Root: HKLM; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "DotMatrixPrintAgent"; \
    ValueData: """{#JavaW}"" -jar ""{#Jar}"" --minimized"; Flags: uninsdeletevalue

[Icons]
Name: "{group}\{#AppName}"; Filename: "{#JavaW}"; Parameters: "-jar ""{#Jar}"""; \
    WorkingDir: "{app}"; Comment: "Pick the default printer for the Dot Matrix Print Agent"
Name: "{autodesktop}\{#AppName}"; Filename: "{#JavaW}"; Parameters: "-jar ""{#Jar}"""; \
    WorkingDir: "{app}"; Comment: "Pick the default printer for the Dot Matrix Print Agent"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"

[Run]
; Pre-approves the bundled Java in Windows Firewall, so a standard user is
; never shown the "allow access" prompt (which needs an admin) when the
; agent is set to accept connections from other computers. Deleted first so
; reinstalling does not pile up duplicate rules.
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#AppName}"""; \
    Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall add rule name=""{#AppName}"" dir=in action=allow program=""{#JavaW}"" enable=yes profile=any"; \
    StatusMsg: "Allowing the agent through Windows Firewall..."; Flags: runhidden waituntilterminated
; Starts the agent right away, as the logged-in user rather than the admin
; account Setup runs under. Also runs after a silent self-update, so the
; agent comes back on its own.
Filename: "{#JavaW}"; Parameters: "-jar ""{#Jar}"""; WorkingDir: "{app}"; \
    Description: "Open {#AppName}"; Flags: postinstall nowait runasoriginaluser

[UninstallRun]
Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\stop-agent.ps1"" -InstallDir ""{app}"""; \
    WorkingDir: "{app}"; RunOnceId: "StopAgent"; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; \
    Parameters: "advfirewall firewall delete rule name=""{#AppName}"""; \
    RunOnceId: "DeleteFirewallRule"; Flags: runhidden waituntilterminated

[UninstallDelete]
Type: filesandordirs; Name: "{app}\logs"

[Code]
// Runs after the user confirms install but before [Files] copies anything.
// On an update, the running agent (in every logged-in user's tray) holds
// the old jar/JRE files open, so close it first. Installs up to v1.2.x ran
// it as a Windows service instead: unregister that before closing anything,
// or the service's restart-on-failure would bring it straight back.
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

  if DirExists(ExpandConstant('{app}')) then
  begin
    ExtractTemporaryFile('stop-agent.ps1');
    Exec('powershell.exe',
      '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{tmp}\stop-agent.ps1') +
      '" -InstallDir "' + ExpandConstant('{app}') + '"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;
