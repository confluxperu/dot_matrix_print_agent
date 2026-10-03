# Dot Matrix Print Agent

Standalone Java desktop application (no external dependencies, Java 8+)
that runs on the computer physically connected to a dot matrix printer and
exposes a small local HTTP API so other applications (Odoo included) can
send raw text directly to it, without going through a PDF renderer.

It is the "local agent" side of Option B discussed for the
`dot_matrix_printing` Odoo module: Odoo cannot reach hardware attached to
a client PC by itself, so this agent bridges that gap.

## What it does

- Lists the printers already registered in the operating system (the
  ones you'd see in your OS's print dialog).
- Lets you add, edit and remove **network printers** by IP/host + port
  (the classic raw/JetDirect protocol most network dot matrix, thermal
  and label printers speak, usually on port 9100).
- Lets you add **another Print Agent** on the network as a target (Network
  Printers tab → Add... → Type "Another Print Agent"), so a PC without a
  printer forwards its jobs to the PC that has one — see "Several PCs
  sharing one printer" below.
- Lets you mark one printer (local or network) as the **default**. Once
  set, callers do not need to say which printer to use at all — see
  below.
- Runs a local HTTP server (default `http://127.0.0.1:8787`, loopback
  only) with:
  - `GET /status` — health check.
  - `GET /printers` — JSON list of all available printers (local +
    network), each with a `"default": true/false` flag.
  - `POST /print` — body `{"content": "<text>"}` sends the raw text to
    the **default printer**. Pass `{"printer": "<id>", "content": "..."}`
    instead to target a specific printer (a network printer opens a TCP
    socket and writes the bytes; a local printer is sent through
    `javax.print` as a raw byte stream).
- Keeps running in the system tray when the window is closed, so it can
  stay available in the background.

The `dot_matrix_print_agent_connector` Odoo module already wires the
single "Print Now - Dot Matrix" Print-menu entry on Sales Orders,
Purchase Orders and Inventory Transfers to `POST /print` on this agent
— it never asks which printer to use, so **you must set a default
printer here first** (Local Printers or Network Printers tab → select a
printer → "Set as Default").

The address the connector calls is **not hardcoded in Odoo** — it is set
in Odoo under Settings > General Settings > "Dot Matrix Print Agent"
(protocol / host / port), so it must match what is configured here, in
this agent's own "Server" tab. They both default to
`http://127.0.0.1:8787`, so as long as neither side has been changed
there is nothing to keep in sync; if you change the port here, update
the same value on the Odoo settings page (and vice versa).

## Several PCs sharing one printer (e.g. one printer per store)

A single Odoo database can serve several stores, each with its own
printer on its own LAN, so the Odoo setting has to stay the same for
everyone: leave it at `http://127.0.0.1:8787`. The browser then always
talks to the agent on its own PC, and each agent decides where the job
goes:

```
Store 1
  PC with the printer (e.g. 192.168.0.26) - agent - local printer (USB)
  Other PC ---- agent ---- forwards to http://192.168.0.26:8787 ---^
```

On the **PC that has the printer** attached:

1. Local Printers → select it → **Set as Default** → Send Test Print.
2. Server tab → enable **Accept connections from other computers** →
   Apply.
3. Allow the port through the Windows firewall (elevated prompt):
   `netsh advfirewall firewall add rule name="Dot Matrix Print Agent" dir=in action=allow protocol=TCP localport=8787`
4. Give the PC a fixed IP (static, or a DHCP reservation in the router).

On **every other PC** of the same store:

1. Network Printers → **Add...** → Type **Another Print Agent on the
   network**, Host = the IP of the PC with the printer, Port = `8787`.
2. **Test Connection** (checks that an agent answers there), then
   **Set as Default** and **Send Test Print**.

The forwarded job goes to whatever printer the receiving agent has as
default and is encoded with *that* printer's encoding, so the encoding is
not configured on the forwarding side. Chains are allowed (A → B → C) up
to three hops; a loop (two agents defaulting to each other) is rejected
with an error instead of bouncing forever. The PC with the printer must be
on for the other PCs to print.

This keeps each store self-contained (adding a store never touches Odoo or
the other stores) and keeps the browser talking only to `127.0.0.1`,
which avoids the mixed-content / private-network restrictions browsers
apply when a page calls a LAN IP directly.

## Build

With Maven:

```bash
mvn package
# -> target/dotmatrix-print-agent.jar
```

Without Maven (plain JDK):

```bash
find src/main/java -name "*.java" > sources.txt
javac -d out -encoding UTF-8 @sources.txt
printf "Main-Class: com.dotmatrix.agent.Main\n" > MANIFEST.MF
jar cfm dotmatrix-print-agent.jar MANIFEST.MF -C out .
```

## Run

```bash
java -jar dotmatrix-print-agent.jar
```

Add `--minimized` to start with the window hidden in the system tray
(what the Windows installer uses at logon). If the agent is already
running, launching it again brings the running copy's window to the
front instead of starting a second one.

Add `--headless` to run without any window or tray icon (e.g. on a Linux
print server):

```bash
java -jar dotmatrix-print-agent.jar --headless
```

### Installing on Windows

`packaging/windows/` builds x86 and x64 Windows installers that bundle
their own Java runtime, start the agent in the system tray at every user
logon, and add a desktop icon to open its window. See
`packaging/windows/README.md`.

### Self-updating

When a build is packaged into a jar (i.e. not run from `target/classes`
during development), the GUI checks this repository's [latest
release](https://github.com/confluxperu/dot_matrix_print_agent/releases/latest)
once on startup. If a newer version is published, a banner appears with
an "Update Now" button; clicking it downloads the installer matching the
running architecture, launches it elevated and silent (one Windows
security prompt), and closes the app so Setup can replace its files -
the installer closes any running copy and reopens the agent when done. See
`UpdateChecker`/`UpdateInstaller` in `com.dotmatrix.agent.update`.

This relies on the repository being **public** - the check uses GitHub's
plain REST API with no embedded credentials on purpose (an auth token
baked into a distributed jar is trivially extracted by decompiling it).
A build only ever detects versions *newer* than itself, so this has no
effect on machines still running a pre-1.1.0 build - those need one
manual reinstall to start seeing future updates automatically.

Configuration (server port, whether it accepts connections from other
computers, and the configured network printers) is stored in
`~/.dotmatrix-print-agent/config.json` and survives restarts.

## Trying it from the command line

```bash
curl http://127.0.0.1:8787/printers

# Uses the default printer configured in the agent's window:
curl -X POST http://127.0.0.1:8787/print \
  -H "Content-Type: application/json" \
  -d '{"content": "HELLO\n"}'

# Targets a specific printer instead:
curl -X POST http://127.0.0.1:8787/print \
  -H "Content-Type: application/json" \
  -d '{"printer": "network:<id-from-printers>", "content": "HELLO\n"}'
```

## Notes

- The server binds to `127.0.0.1` by default (only this computer can use
  it). Only enable "Accept connections from other computers" if this
  agent is meant to act as a shared print server for several PCs on the
  same network.
- The encoding used to send bytes (`ISO-8859-1` by default) is
  configurable per network printer — most dot matrix printers expect a
  single-byte codepage rather than UTF-8, so accented characters print
  correctly with `ISO-8859-1`/`CP437`/`Cp850` but may not with `UTF-8`,
  depending on the printer. Adjust it if accented characters print wrong.
- Local (OS-registered) printers are sent raw bytes through
  `javax.print`. Depending on the OS print driver, some drivers may still
  reformat/paginate plain text; a network printer configured by IP/port
  is the more reliable, driver-independent option for true raw printing.

### "Test Print says it was sent, but nothing comes out"

For a local printer the agent only hands the job to the Windows print
queue; it cannot tell whether the queue actually delivers it. If the queue
shows the printer as *Offline* / *Use Printer Offline* is ticked
(`wmic printer get Name,PortName,WorkOffline` shows `TRUE`), Windows keeps
the job forever. For a USB printer this usually means the printer is not
attached to *this* PC on that port anymore (Windows sets it offline by
itself when the USB device is gone). If the printer is attached to another
PC, install the agent there and add it here as "Another Print Agent" (see
above).

### "It printed but the file/output is empty"

This happens when the local printer selected is not a real raw/text
printer but a **virtual document-writer** — "Microsoft Print to PDF",
"Microsoft XPS Document Writer", "Send to OneNote", "Fax", etc. Those
only work by having something draw on a GDI/EMF graphics surface; they
do not understand a raw byte/text pass-through job. `javax.print`
happily accepts and "completes" the job, but the result is an empty
page — there is no error to catch on the Java side, because as far as
Windows is concerned the job was fine.

The agent now flags printers matching those names with a ⚠ in the Local
Printers list and warns before letting you set one as default, but it
cannot fully prevent it (the check is a name heuristic, not a real
capability probe).

To actually test raw/dot-matrix output on a Windows machine without a
physical printer at hand, add a printer using Windows' built-in
**"Generic / Text Only"** driver (Control Panel → Devices and Printers →
Add a printer → "The printer that I want isn't listed" → "Add a local
printer" → manufacturer **Generic**, printer **Generic / Text Only**).
That driver is specifically designed for raw text pass-through and is
the standard way to validate this kind of integration before a real dot
matrix printer is available. A real physical dot matrix printer (or any
network printer answering on its raw/JetDirect port, usually 9100) also
works correctly.
