package com.dotmatrix.agent.server;

import com.dotmatrix.agent.Logger;
import com.dotmatrix.agent.config.AppConfig;
import com.dotmatrix.agent.json.Json;
import com.dotmatrix.agent.print.PrintAgentException;
import com.dotmatrix.agent.print.PrintManager;
import com.dotmatrix.agent.print.PrinterInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP API that lets Odoo (or any HTTP client) list configured
 * printers and submit a raw print job, without going through a PDF
 * renderer. Bound to 127.0.0.1 by default so only this computer can use it.
 */
public class HttpApiServer {

    private final AppConfig config;
    private final PrintManager printManager;
    private final Logger logger;
    private HttpServer server;
    private ExecutorService executor;

    public HttpApiServer(AppConfig config, PrintManager printManager, Logger logger) {
        this.config = config;
        this.printManager = printManager;
        this.logger = logger;
    }

    public synchronized void start() throws IOException {
        stop();
        InetAddress bindAddress = config.isBindAllInterfaces()
                ? new InetSocketAddress(config.getServerPort()).getAddress()
                : InetAddress.getByName("127.0.0.1");
        server = HttpServer.create(new InetSocketAddress(bindAddress, config.getServerPort()), 0);
        server.createContext("/status", new StatusHandler());
        server.createContext("/printers", new PrintersHandler());
        server.createContext("/print", new PrintHandler());
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        log("HTTP server started on " + bindAddress.getHostAddress() + ":" + config.getServerPort());
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        addCors(exchange);
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        OutputStream os = exchange.getResponseBody();
        try {
            os.write(body);
        } finally {
            os.close();
        }
    }

    private static boolean handlePreflight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            addCors(exchange);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = is.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> errorPayload(String message) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("success", false);
        map.put("error", message);
        return map;
    }

    private final class StatusHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("status", "ok");
            payload.put("app", "dotmatrix-print-agent");
            payload.put("version", "1.0.0");
            sendJson(exchange, 200, payload);
        }
    }

    private final class PrintersHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            List<PrinterInfo> printers = printManager.listAll();
            List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
            for (PrinterInfo p : printers) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", p.getId());
                item.put("name", p.getName());
                item.put("type", p.getType().name());
                item.put("detail", p.getDetail());
                item.put("default", p.isDefault());
                payload.add(item);
            }
            sendJson(exchange, 200, payload);
        }
    }

    private final class PrintHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handlePreflight(exchange)) {
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorPayload("Method not allowed, use POST"));
                return;
            }

            String body = readBody(exchange);
            Object parsed;
            try {
                parsed = Json.parse(body);
            } catch (Exception e) {
                sendJson(exchange, 400, errorPayload("Invalid JSON: " + e.getMessage()));
                return;
            }
            if (!(parsed instanceof Map)) {
                sendJson(exchange, 400, errorPayload("Expected a JSON object"));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            Object printerObj = map.get("printer");
            Object contentObj = map.get("content");
            Object encodingObj = map.get("encoding");

            if (!(contentObj instanceof String)) {
                sendJson(exchange, 400, errorPayload("Field 'content' (string) is required"));
                return;
            }

            // 'printer' is optional: when omitted, PrintManager falls back to
            // whichever printer is configured as default in the agent.
            String printer = printerObj instanceof String ? (String) printerObj : null;
            String content = (String) contentObj;
            String encoding = encodingObj instanceof String ? (String) encodingObj : null;

            try {
                printManager.printRaw(printer, content, encoding);
                log("Printed " + content.length() + " chars to '"
                        + (printer != null ? printer : "(default printer)") + "'");
                Map<String, Object> ok = new LinkedHashMap<String, Object>();
                ok.put("success", true);
                sendJson(exchange, 200, ok);
            } catch (PrintAgentException e) {
                log("ERROR printing to '" + (printer != null ? printer : "(default printer)")
                        + "': " + e.getMessage());
                sendJson(exchange, 500, errorPayload(e.getMessage()));
            }
        }
    }
}
