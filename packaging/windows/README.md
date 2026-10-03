# Windows installer (x86 / x64)

Builds a Windows installer that:

- Bundles its own Java runtime (nothing to install separately).
- Starts the agent **at every user logon**, straight into the **system
  tray** (machine-wide `Run` registry key, so it applies to every user of
  the computer).
- Adds a **desktop icon** and a Start Menu shortcut that open the
  configuration window (Local/Network Printers, default printer, server
  settings). If the agent is already running in the tray, the icon just
  brings its window to the front.
- Opens the agent right after installing (and again after a self-update).

Two variants are produced: `DotMatrixPrintAgentSetup-x86.exe` (32-bit
Windows) and `DotMatrixPrintAgentSetup-x64.exe` (64-bit Windows, the
common case today). Both work on Windows 7, 8, 10 and 11.

Both are also built automatically by
`.github/workflows/release-windows-installer.yml` on a `windows-latest`
GitHub Actions runner - pushing a `v*` tag builds them and attaches them
to a new GitHub Release; the workflow can also be run on demand
(Actions tab &rarr; "Build and release Windows installer" &rarr; "Run
workflow") to sanity-check the build without cutting a release. Building
locally (below) is only needed if you don't want to use CI.

## Why a tray app at logon instead of a Windows service?

Up to v1.2.x the agent was installed as a Windows service (through the
WinSW wrapper). On some machines - typically slower ones, or with a heavy
antivirus - the service was set to Automatic but was found stopped after
a reboot and had to be started by hand: Windows gives a service 30
seconds to report that it started during boot, and if it misses that
window it is marked as failed without any retry (the restart-on-failure
settings only apply to a service that crashes *after* starting).

Other print bridges used with Odoo on the same machines (e.g. jIotBox)
never had this problem because they are plain desktop programs started
when the user logs in. The agent now works the same way:

- No service, no wrapper, no 30-second limit - Windows simply launches
  `jre\bin\javaw.exe -jar dotmatrix-print-agent.jar --minimized` at logon.
- `--minimized` starts with the window hidden and the tray icon showing;
  if the tray is not available, the window is shown instead.
- Only one copy runs at a time: launching it again (desktop icon, Start
  Menu) asks the running copy to show its window, and a second
  `--minimized` launch just exits.
- It runs as the logged-in user, so it also sees printers connected only
  for that user (e.g. shared printers added from another PC), which a
  service running as Local System could not.

The trade-off: the agent is available once a user has logged in, not
before - the same as jIotBox. Closing the window keeps it running in the
tray; only **Exit** in the tray menu stops it (until the next logon or
until the icon is opened again).

## Prerequisites (on the Windows machine used to build the installer)

- **Maven** (`mvn`) and a JDK on `PATH`, to build `dotmatrix-print-agent.jar`.
- **Internet access** - the build script downloads a portable JRE (see
  "Third-party components" below).
- **[Inno Setup 6](https://jrsoftware.org/isinfo.php)** (free), optional
  but recommended - without it you still get ready-to-use portable
  folders, just not a polished single `.exe` installer (see below).

You do **not** need Java or Inno Setup on the target machines where the
agent will run - the installer bundles everything.

## Building

From PowerShell, in this folder:

```powershell
.\build-installer.ps1
```

This produces:

- `output\DotMatrixPrintAgentSetup-x86.exe`
- `output\DotMatrixPrintAgentSetup-x64.exe`

(only if Inno Setup was found - see below), and, always:

- `dist\x86\` and `dist\x64\` - self-contained portable folders. If you
  don't have Inno Setup, zip one of these up, copy it to the target
  machine and run `jre\bin\javaw.exe -jar dotmatrix-print-agent.jar`.
  The portable copy does not start at logon by itself: put a shortcut
  with `--minimized` in the user's Startup folder (`shell:startup`) for
  that.

Re-running `build-installer.ps1` is safe and fast on subsequent runs -
downloaded JRE files are cached (by SHA-256) under `.cache\`.

### Which installer for which machine?

- **x64** - any 64-bit Windows (the vast majority of machines today,
  including old ones - 64-bit CPUs have shipped since ~2005). Prefer this
  one unless you know the target is a genuinely 32-bit install of
  Windows.
- **x86** - only needed for a 32-bit install of Windows (32-bit CPU, or a
  32-bit Windows image on 64-bit hardware). Run `DotMatrixPrintAgentSetup-x86.exe`.

## What the installer does

1. Closes any copy of the agent that is running (in every user's
   session), so its files can be replaced. When updating from v1.2.x it
   first stops and removes the old Windows service and its files
   (`DotMatrixPrintAgentService.exe/.xml`, service scripts, `logs\`,
   old Start Menu shortcuts).
2. Copies the jar and the bundled JRE into
   `%ProgramFiles%\DotMatrixPrintAgent` (or `%ProgramFiles(x86)%` for the
   x86 build).
3. Adds `DotMatrixPrintAgent` to
   `HKLM\Software\Microsoft\Windows\CurrentVersion\Run`, so the agent
   starts in the tray at every user's logon.
4. Adds a Windows Firewall rule allowing the bundled `javaw.exe`, so a
   standard user never gets the "allow access" prompt (which needs an
   admin) when the agent is set to accept connections from other
   computers. While it only listens on 127.0.0.1 (the default) the rule
   has no effect.
5. Adds a **Dot Matrix Print Agent** desktop icon and Start Menu
   shortcut, plus **Uninstall**.
6. Opens the agent.

Uninstalling (Control Panel &rarr; Apps, or the Start Menu shortcut)
closes the agent and removes the logon entry and the firewall rule before
removing files. It does **not** delete
`%ProgramData%\DotMatrixPrintAgent\config.json` (your printer setup
survives an uninstall/reinstall/upgrade, including the upgrade from the
service-based v1.2.x).

## After installing

1. The agent opens on its own (later, it starts in the tray at logon).
   Otherwise open the **Dot Matrix Print Agent** icon on the desktop.
2. Add your network printer (or pick a local one) and **Set as Default**
   - Odoo's print button never asks which printer to use, so this step
     is required. The change applies immediately.
3. Close the window - the agent keeps running in the tray.
4. In Odoo: Settings &rarr; General Settings &rarr; "Dot Matrix Print
   Agent" should already point to `http://127.0.0.1:8787`, matching this
   agent's default port.

## Troubleshooting

- **Is it running?** Look for the "P" icon in the tray (it may be inside
  the "^" overflow area), or open `http://127.0.0.1:8787/status`.
- **It did not start at logon**: check Task Manager &rarr; Startup (Windows
  8 and later) - "DotMatrixPrintAgent" must be *Enabled*. On Windows 7,
  run `msconfig` &rarr; Startup.
- **"Port already in use" in the window**: some other program is using
  port 8787. Pick another port in the Server tab and update the URL in
  Odoo to match.

## Third-party components bundled by the build script

Pinned by exact version + SHA-256 in `build-installer.ps1`, downloaded
fresh (or from `.cache\`) at build time - nothing is committed to this
repository:

- **[BellSoft Liberica JRE 8](https://github.com/bell-sw/Liberica)**
  (GPLv2+CE, same license family as Temurin/OpenJDK) - chosen because, as
  of this writing, it is the only mainstream OpenJDK distribution still
  publishing a **32-bit (x86) Windows** build for Java 8; Eclipse
  Adoptium/Temurin and Azul Zulu have both discontinued Windows x86
  entirely. The x64 build from the same vendor is used for consistency
  between both installer variants.

To update the pin, replace both the URL and the SHA-256 constant in
`build-installer.ps1` - the script refuses to proceed on a checksum
mismatch.
