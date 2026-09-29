package com.dotmatrix.agent.print;

import com.dotmatrix.agent.Logger;
import com.dotmatrix.agent.config.AppConfig;
import com.dotmatrix.agent.json.Json;
import com.dotmatrix.agent.model.NetworkPrinter;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a printer id (local OS printer or configured network printer)
 * and sends raw bytes to it, bypassing any PDF/rendering pipeline. When no
 * printer id is given, falls back to the default printer configured in the
 * agent, so callers (e.g. the Odoo button) do not have to ask the user.
 */
public class PrintManager {

    public static final String LOCAL_PREFIX = "local:";
    public static final String NETWORK_PREFIX = "network:";
    private static final String DEFAULT_ENCODING = "ISO-8859-1";
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5000;
    private static final int AGENT_READ_TIMEOUT_MS = 30000;

    /**
     * Request header carrying how many agents a job has already been
     * forwarded through. Guards against a misconfiguration where two
     * agents have each other as default and would bounce a job forever.
     */
    public static final String HOPS_HEADER = "X-Print-Agent-Hops";
    public static final int MAX_HOPS = 3;

    /**
     * Name fragments (case-insensitive) of printers that only render a
     * document through the OS graphics pipeline (GDI/EMF) instead of
     * accepting a raw byte/text pass-through job. Sending raw text to one
     * of these silently produces an empty document instead of an error, so
     * the UI warns about them instead of letting the user pick one for real
     * dot matrix output. This is informational only: it does not block
     * anything, in case a given driver behaves differently.
     */
    private static final String[] VIRTUAL_PRINTER_HINTS = {
            "Microsoft Print to PDF",
            "Microsoft XPS",
            "OneNote",
            "Fax",
            "PDFCreator",
    };

    private final AppConfig config;
    private final Logger logger;

    public PrintManager(AppConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public List<PrinterInfo> listAll() {
        List<PrinterInfo> result = new ArrayList<PrinterInfo>();
        String defaultId = config.getDefaultPrinterId();
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            String id = LOCAL_PREFIX + service.getName();
            String detail = isLikelyVirtual(service.getName())
                    ? "System printer (not suitable for raw/dot matrix printing)"
                    : "System printer";
            result.add(new PrinterInfo(id, service.getName(), PrinterInfo.Type.LOCAL, detail, id.equals(defaultId)));
        }
        for (NetworkPrinter np : config.getNetworkPrinters()) {
            String id = NETWORK_PREFIX + np.getId();
            String detail = np.isAgent()
                    ? "Print Agent at " + agentBaseUrl(np)
                    : np.getHost() + ":" + np.getPort() + " (" + np.getEncoding() + ")";
            result.add(new PrinterInfo(id, np.getName(), PrinterInfo.Type.NETWORK, detail, id.equals(defaultId)));
        }
        return result;
    }

    public boolean isLikelyVirtual(String printerName) {
        if (printerName == null) {
            return false;
        }
        String lower = printerName.toLowerCase(Locale.ROOT);
        for (String hint : VIRTUAL_PRINTER_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prints to {@code printerId}, or, when it is {@code null}/blank, to the
     * default printer configured in the agent.
     */
    public void printRaw(String printerId, String content, String encodingOverride) throws PrintAgentException {
        printRaw(printerId, content, encodingOverride, 0);
    }

    /**
     * Same as {@link #printRaw(String, String, String)}, for a job that has
     * already been forwarded by {@code hops} other agents.
     */
    public void printRaw(String printerId, String content, String encodingOverride, int hops)
            throws PrintAgentException {
        String resolvedId = (printerId == null || printerId.trim().isEmpty())
                ? config.getDefaultPrinterId()
                : printerId;
        if (resolvedId == null || resolvedId.trim().isEmpty()) {
            throw new PrintAgentException(
                    "No printer was specified and no default printer is configured in the agent. "
                            + "Open the Dot Matrix Print Agent and mark a printer as default first.");
        }

        if (resolvedId.startsWith(LOCAL_PREFIX)) {
            String name = resolvedId.substring(LOCAL_PREFIX.length());
            PrintService service = findLocalByName(name);
            if (service == null) {
                throw new PrintAgentException("Local printer not found: " + name);
            }
            printToLocalService(service, content, encodingOverride);
            return;
        }
        if (resolvedId.startsWith(NETWORK_PREFIX)) {
            String id = resolvedId.substring(NETWORK_PREFIX.length());
            NetworkPrinter np = findNetworkPrinterById(id);
            if (np == null) {
                throw new PrintAgentException("Unknown network printer id: " + id);
            }
            sendToNetworkTarget(np, content, encodingOverride, hops);
            return;
        }

        // Fallback: accept a bare printer/queue name (matched against either list)
        // so callers do not need to fetch /printers first if they already know the name.
        PrintService service = findLocalByName(resolvedId);
        if (service != null) {
            printToLocalService(service, content, encodingOverride);
            return;
        }
        NetworkPrinter np = findNetworkPrinterByName(resolvedId);
        if (np != null) {
            sendToNetworkTarget(np, content, encodingOverride, hops);
            return;
        }
        throw new PrintAgentException("Printer not found: " + resolvedId);
    }

    public void testConnection(NetworkPrinter np) throws PrintAgentException {
        if (np.isAgent()) {
            testAgentConnection(np);
            return;
        }
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(np.getHost(), np.getPort()), SOCKET_CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            throw new PrintAgentException("Connection failed: " + e.getMessage(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    private NetworkPrinter findNetworkPrinterById(String id) {
        for (NetworkPrinter np : config.getNetworkPrinters()) {
            if (np.getId().equals(id)) {
                return np;
            }
        }
        return null;
    }

    private NetworkPrinter findNetworkPrinterByName(String name) {
        for (NetworkPrinter np : config.getNetworkPrinters()) {
            if (np.getName() != null && np.getName().equalsIgnoreCase(name)) {
                return np;
            }
        }
        return null;
    }

    private PrintService findLocalByName(String name) {
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (service.getName().equalsIgnoreCase(name)) {
                return service;
            }
        }
        return null;
    }

    /**
     * Maps a charset name to the most specific raw-text {@link DocFlavor}
     * available, instead of always using AUTOSENSE. Several Windows print
     * drivers (especially virtual/class drivers) handle an explicit
     * "text/plain" flavor far more reliably than a raw byte stream they have
     * to sniff themselves.
     */
    private static final Map<String, DocFlavor> TEXT_FLAVORS_BY_ENCODING = buildTextFlavors();

    private static Map<String, DocFlavor> buildTextFlavors() {
        Map<String, DocFlavor> map = new HashMap<String, DocFlavor>();
        // DocFlavor only predefines a handful of charsets; the others are
        // built as "text/plain; charset=<name>" flavors, which the JDK's
        // BYTE_ARRAY constructor accepts directly.
        map.put("US-ASCII", DocFlavor.BYTE_ARRAY.TEXT_PLAIN_US_ASCII);
        map.put("ASCII", DocFlavor.BYTE_ARRAY.TEXT_PLAIN_US_ASCII);
        map.put("UTF-8", DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_8);
        map.put("UTF-16", DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16);
        map.put("ISO-8859-1", new DocFlavor.BYTE_ARRAY("text/plain; charset=iso-8859-1"));
        map.put("CP437", new DocFlavor.BYTE_ARRAY("text/plain; charset=cp437"));
        map.put("CP850", new DocFlavor.BYTE_ARRAY("text/plain; charset=cp850"));
        return map;
    }

    /**
     * Picks a {@link DocFlavor} the given {@link PrintService} actually
     * declares support for. A flavor built from an arbitrary charset (e.g.
     * "text/plain; charset=iso-8859-1") is often rejected outright by real
     * drivers (including Windows' own "Generic / Text Only") with a
     * "invalid flavor" PrintException, even though the driver is perfectly
     * capable of raw text printing — it just does not advertise that exact
     * MIME/charset combination. So: prefer the encoding-specific flavor
     * only if the service supports it, otherwise fall back to whatever
     * byte-array flavor it does support (AUTOSENSE first, since that is
     * the closest thing to "raw bytes" nearly every driver accepts).
     */
    DocFlavor resolveTextFlavor(PrintService service, String encoding) {
        DocFlavor preferred = TEXT_FLAVORS_BY_ENCODING.get(encoding.toUpperCase(Locale.ROOT));
        if (preferred != null && service.isDocFlavorSupported(preferred)) {
            return preferred;
        }
        if (service.isDocFlavorSupported(DocFlavor.BYTE_ARRAY.AUTOSENSE)) {
            return DocFlavor.BYTE_ARRAY.AUTOSENSE;
        }
        if (service.isDocFlavorSupported(DocFlavor.BYTE_ARRAY.TEXT_PLAIN_HOST)) {
            return DocFlavor.BYTE_ARRAY.TEXT_PLAIN_HOST;
        }
        for (DocFlavor flavor : service.getSupportedDocFlavors()) {
            if (byte[].class.getName().equals(flavor.getRepresentationClassName())
                    && flavor.getMimeType().startsWith("text/plain")) {
                return flavor;
            }
        }
        return null;
    }

    private void printToLocalService(PrintService service, String content, String encodingOverride)
            throws PrintAgentException {
        String encoding = encodingOverride != null ? encodingOverride : DEFAULT_ENCODING;
        if (isLikelyVirtual(service.getName())) {
            log("WARNING: '" + service.getName() + "' looks like a virtual/document-writer printer. "
                    + "It may accept the job but produce an empty document instead of real raw output. "
                    + "For dot matrix output use a physical printer, or Windows' 'Generic / Text Only' driver.");
        }
        DocFlavor flavor = resolveTextFlavor(service, encoding);
        if (flavor == null) {
            throw new PrintAgentException("Printer '" + service.getName()
                    + "' does not advertise support for any raw text doc flavor this agent can use.");
        }
        log("Printing to '" + service.getName() + "' using flavor " + flavor.getMimeType());
        try {
            byte[] data = content.getBytes(Charset.forName(encoding));
            Doc doc = new SimpleDoc(data, flavor, null);
            PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
            DocPrintJob job = service.createPrintJob();
            job.addPrintJobListener(new PrintJobAdapter() {
                @Override
                public void printJobFailed(PrintJobEvent event) {
                    log("Print job to '" + service.getName() + "' failed.");
                }
                @Override
                public void printJobCanceled(PrintJobEvent event) {
                    log("Print job to '" + service.getName() + "' was canceled.");
                }
                @Override
                public void printJobRequiresAttention(PrintJobEvent event) {
                    log("Print job to '" + service.getName() + "' requires attention "
                            + "(check paper, connection, etc).");
                }
            });
            job.print(doc, attributes);
        } catch (PrintException e) {
            throw new PrintAgentException("Failed to print to local printer '" + service.getName()
                    + "': " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PrintAgentException("Failed to print to local printer '" + service.getName()
                    + "': " + e.getMessage(), e);
        }
    }

    private void sendToNetworkTarget(NetworkPrinter np, String content, String encodingOverride, int hops)
            throws PrintAgentException {
        if (np.isAgent()) {
            forwardToAgent(np, content, encodingOverride, hops);
        } else {
            printToNetwork(np, content, encodingOverride);
        }
    }

    private static String agentBaseUrl(NetworkPrinter np) {
        return "http://" + np.getHost() + ":" + np.getPort();
    }

    /**
     * Forwards the job to another agent's {@code POST /print} without a
     * 'printer' field, so it lands on whichever printer that agent has as
     * default. The content goes as text (UTF-8 JSON) and is only turned into
     * bytes by the agent that owns the printer, using that printer's
     * encoding - unless the caller explicitly asked for one.
     */
    private void forwardToAgent(NetworkPrinter np, String content, String encodingOverride, int hops)
            throws PrintAgentException {
        if (hops >= MAX_HOPS) {
            throw new PrintAgentException("Print job was forwarded through " + hops
                    + " agents without reaching a printer. Check that the agents' default printers "
                    + "do not point back at each other.");
        }
        String url = agentBaseUrl(np) + "/print";
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("content", content);
        if (encodingOverride != null) {
            payload.put("encoding", encodingOverride);
        }
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            conn = openAgentConnection(url);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty(HOPS_HEADER, String.valueOf(hops + 1));
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int status = conn.getResponseCode();
            if (status >= 400) {
                throw new PrintAgentException("Print Agent '" + np.getName() + "' (" + agentBaseUrl(np)
                        + ") could not print: " + readAgentError(conn, status));
            }
            log("Forwarded job to Print Agent '" + np.getName() + "' (" + agentBaseUrl(np) + ").");
        } catch (IOException e) {
            throw new PrintAgentException("Could not reach Print Agent '" + np.getName() + "' ("
                    + agentBaseUrl(np) + "): " + e.getMessage()
                    + ". Check that the other computer is on, its agent is running with "
                    + "'Accept connections from other computers' enabled, and its firewall allows port "
                    + np.getPort() + ".", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void testAgentConnection(NetworkPrinter np) throws PrintAgentException {
        HttpURLConnection conn = null;
        try {
            conn = openAgentConnection(agentBaseUrl(np) + "/status");
            int status = conn.getResponseCode();
            String text = status < 400 ? readFully(conn.getInputStream()) : "";
            Object parsed = null;
            try {
                parsed = Json.parse(text);
            } catch (Exception ignored) {
                // handled below: anything that is not our /status payload
            }
            if (status >= 400 || !(parsed instanceof Map)
                    || !"dotmatrix-print-agent".equals(((Map<?, ?>) parsed).get("app"))) {
                throw new PrintAgentException(agentBaseUrl(np)
                        + " answered, but it does not look like a Dot Matrix Print Agent (HTTP " + status + ").");
            }
        } catch (IOException e) {
            throw new PrintAgentException("Connection failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection openAgentConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(SOCKET_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(AGENT_READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        return conn;
    }

    private static String readAgentError(HttpURLConnection conn, int status) {
        try {
            InputStream err = conn.getErrorStream();
            if (err != null) {
                Object parsed = Json.parse(readFully(err));
                if (parsed instanceof Map && ((Map<?, ?>) parsed).get("error") != null) {
                    return String.valueOf(((Map<?, ?>) parsed).get("error"));
                }
            }
        } catch (Exception ignored) {
            // fall through to the generic message
        }
        return "HTTP " + status;
    }

    private static String readFully(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private void printToNetwork(NetworkPrinter np, String content, String encodingOverride)
            throws PrintAgentException {
        String encoding = encodingOverride != null ? encodingOverride : np.getEncoding();
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(np.getHost(), np.getPort()), SOCKET_CONNECT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(content.getBytes(Charset.forName(encoding)));
            out.flush();
        } catch (IOException e) {
            throw new PrintAgentException("Failed to print to network printer '" + np.getName()
                    + "' (" + np.getHost() + ":" + np.getPort() + "): " + e.getMessage(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing else to do
            }
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }
}
