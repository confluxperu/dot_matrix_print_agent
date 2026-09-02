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

Add `--headless` to run without the window (useful for running it as a
background service on a print server / kiosk machine):

```bash
java -jar dotmatrix-print-agent.jar --headless
```

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
