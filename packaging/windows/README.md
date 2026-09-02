# Windows installer (x86 / x64)

Builds a Windows installer that:

- Bundles its own Java runtime (nothing to install separately).
- Registers the agent as a **Windows service** ("Dot Matrix Print Agent"),
  set to **start automatically on boot** - before any user logs in.
- Runs the agent in `--headless` mode (no window), since Windows services
  run in a non-interactive session and cannot show a GUI/tray icon.
- Adds a **desktop icon** (and a matching "Configure Printers" Start Menu
  shortcut) that opens the normal configuration window (Local/Network
  Printers, default printer) and restarts the service afterwards so the
  change takes effect. Without this, there would be no way to reach that
  window at all - the service itself has no window (see below).

Two variants are produced: `DotMatrixPrintAgentSetup-x86.exe` (32-bit
Windows) and `DotMatrixPrintAgentSetup-x64.exe` (64-bit Windows, the
common case today).

Both are also built automatically by
`.github/workflows/release-windows-installer.yml` on a `windows-latest`
GitHub Actions runner - pushing a `v*` tag builds them and attaches them
to a new GitHub Release; the workflow can also be run on demand
(Actions tab &rarr; "Build and release Windows installer" &rarr; "Run
workflow") to sanity-check the build without cutting a release. Building
locally (below) is only needed if you don't want to use CI.

## Why a headless service + a separate configuration window?

The agent has always had two modes (see `Main.java`): a GUI (system tray,
"Local/Network Printers" tabs) and `--headless` (background only, no
window - added for exactly this kind of deployment). A Windows service
runs in **Session 0**, which cannot display windows to any logged-in
user, so the service always runs `--headless`; the GUI is only ever
launched on demand, as a normal foreground app, via the "Configure
Printers" shortcut.

Both the service and the GUI read/write the **same** configuration file
(`%ProgramData%\DotMatrixPrintAgent\config.json` - see the `ConfigStore`
change below), but they are still two separate processes that cannot both
hold the local HTTP port at the same time, and the service does not
hot-reload the file while running. So "Configure Printers":

1. stops the service,
2. opens the GUI in the foreground and waits for you to close it,
3. starts the service again, now with the printer you just picked.

You do not need to remember to do this yourself - it is exactly what the
desktop icon and Start Menu shortcut both run
(`scripts\configure-printers.ps1`).

## Code changes that made this possible

- `ConfigStore` now resolves its config directory to
  `%ProgramData%\DotMatrixPrintAgent` on Windows instead of the invoking
  user's home folder. This matters specifically because the Windows
  service runs under the **Local System** account, whose `user.home`
  points to a system profile the interactive user never sees - without
  this fix the service would silently look for a *different, empty*
  config file and never find the printer you configured. The installer
  grants the "Users" group write access to this folder so the GUI does
  not need to run elevated just to save a config change.
- `Main.java`'s headless path now registers a JVM shutdown hook that
  calls `server.stop()`, so WinSW stopping the service releases the HTTP
  port immediately and logs a clean shutdown line instead of relying on
  the OS to reclaim the port after a hard kill.
- Nothing else changed: `--headless` and the HTTP API were already there.

## Prerequisites (on the Windows machine used to build the installer)

- **Maven** (`mvn`) and a JDK on `PATH`, to build `dotmatrix-print-agent.jar`.
- **Internet access** - the build script downloads a portable JRE and the
  WinSW service wrapper (see "Third-party components" below).
- **[Inno Setup 6](https://jrsoftware.org/isinfo.php)** (free), optional
  but recommended - without it you still get ready-to-use portable
  folders, just not a polished single `.exe` installer (see below).

You do **not** need Java or Inno Setup on the target machines where the
agent will run - the installer bundles everything.

## Building

From an elevated PowerShell, in this folder:

```powershell
.\build-installer.ps1
```

This produces:

- `output\DotMatrixPrintAgentSetup-x86.exe`
- `output\DotMatrixPrintAgentSetup-x64.exe`

(only if Inno Setup was found - see below), and, always:

- `dist\x86\` and `dist\x64\` - self-contained portable folders. If you
  don't have Inno Setup, zip one of these up, copy it to the target
  machine, and from an elevated PowerShell run:

  ```powershell
  cd DotMatrixPrintAgent\scripts
  .\install-service.ps1
  ```

  That registers and starts the service exactly like the `.exe`
  installer's silent post-install step does.

Re-running `build-installer.ps1` is safe and fast on subsequent runs -
downloaded JRE/WinSW files are cached (by SHA-256) under `.cache\`.

### Which installer for which machine?

- **x64** - any 64-bit Windows (the vast majority of machines today,
  including old ones - 64-bit CPUs have shipped since ~2005). Prefer this
  one unless you know the target is a genuinely 32-bit install of
  Windows.
- **x86** - only needed for a 32-bit install of Windows (32-bit CPU, or a
  32-bit Windows image on 64-bit hardware). Run `DotMatrixPrintAgentSetup-x86.exe`.

## What the installer does

1. Copies the jar, bundled JRE, WinSW (renamed
   `DotMatrixPrintAgentService.exe`) and its config into
   `%ProgramFiles%\DotMatrixPrintAgent` (or `%ProgramFiles(x86)%` for the
   x86 build).
2. Runs `scripts\install-service.ps1`, which registers the Windows
   service (`sc.exe`-level, via WinSW) with **Startup type: Automatic**
   and starts it immediately.
3. Adds a **Dot Matrix Print Agent** desktop icon (opens the same
   configuration window as "Configure Printers" below), and Start Menu
   shortcuts: **Configure Printers**, **Restart Service**, **View
   Service Logs**, **Uninstall**.

Uninstalling (Control Panel &rarr; Apps, or the Start Menu shortcut) stops
and unregisters the service before removing files. It does **not** delete
`%ProgramData%\DotMatrixPrintAgent\config.json` (your printer setup
survives an uninstall/reinstall/upgrade).

## After installing

1. Open the **Dot Matrix Print Agent** icon on the desktop (or
   **Configure Printers** from the Start Menu - same thing).
2. Add your network printer (or pick a local one) and **Set as Default**
   - Odoo's print button never asks which printer to use, so this step
     is required.
3. Close the window - the service restarts automatically with the new
   default.
4. In Odoo: Settings &rarr; General Settings &rarr; "Dot Matrix Print
   Agent" should already point to `http://127.0.0.1:8787`, matching this
   agent's default port.

## Troubleshooting

- **Service status / logs**: `services.msc` &rarr; "Dot Matrix Print
  Agent", or the **View Service Logs** shortcut
  (`%ProgramFiles%\DotMatrixPrintAgent\logs`, rotated by WinSW).
- **Manually control the service** (elevated PowerShell, from the install
  folder): `.\DotMatrixPrintAgentService.exe status|start|stop|restart`.
- **"Port already in use" opening Configure Printers manually** (e.g. by
  double-clicking the jar instead of using the shortcut): the service is
  still holding port 8787. Use the **Configure Printers** shortcut, which
  stops the service first, or run `.\DotMatrixPrintAgentService.exe stop`
  yourself first.

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
- **[WinSW](https://github.com/winsw/winsw)** v2.12.0 (MIT) - a small,
  well-established wrapper that runs an arbitrary executable as a Windows
  service; used here to run `javaw -jar dotmatrix-print-agent.jar
  --headless` as "Dot Matrix Print Agent", with auto-restart on failure
  and rolling log files.

To update either pin, replace both the URL and the SHA-256 constant in
`build-installer.ps1` - the script refuses to proceed on a checksum
mismatch.
