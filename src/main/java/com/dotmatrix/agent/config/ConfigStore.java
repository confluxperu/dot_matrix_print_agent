package com.dotmatrix.agent.config;

import com.dotmatrix.agent.json.Json;
import com.dotmatrix.agent.model.NetworkPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists {@link AppConfig} to {@code ~/.dotmatrix-print-agent/config.json}.
 */
public class ConfigStore {

    private final Path configDir;
    private final Path configFile;

    public ConfigStore() {
        this.configDir = Paths.get(System.getProperty("user.home"), ".dotmatrix-print-agent");
        this.configFile = configDir.resolve("config.json");
    }

    @SuppressWarnings("unchecked")
    public AppConfig load() {
        AppConfig config = new AppConfig();
        if (!Files.exists(configFile)) {
            return config;
        }
        try {
            String text = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) {
                return config;
            }
            Map<String, Object> map = (Map<String, Object>) parsed;

            if (map.get("serverPort") instanceof Number) {
                config.setServerPort(((Number) map.get("serverPort")).intValue());
            }
            if (map.get("bindAllInterfaces") instanceof Boolean) {
                config.setBindAllInterfaces((Boolean) map.get("bindAllInterfaces"));
            }
            if (map.get("defaultPrinterId") != null) {
                config.setDefaultPrinterId(String.valueOf(map.get("defaultPrinterId")));
            }

            List<NetworkPrinter> printers = new ArrayList<NetworkPrinter>();
            Object printersObj = map.get("networkPrinters");
            if (printersObj instanceof List) {
                for (Object item : (List<Object>) printersObj) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> pm = (Map<String, Object>) item;
                    NetworkPrinter np = new NetworkPrinter();
                    if (pm.get("id") != null) {
                        np.setId(String.valueOf(pm.get("id")));
                    }
                    if (pm.get("name") != null) {
                        np.setName(String.valueOf(pm.get("name")));
                    }
                    if (pm.get("host") != null) {
                        np.setHost(String.valueOf(pm.get("host")));
                    }
                    if (pm.get("port") instanceof Number) {
                        np.setPort(((Number) pm.get("port")).intValue());
                    }
                    if (pm.get("encoding") != null) {
                        np.setEncoding(String.valueOf(pm.get("encoding")));
                    }
                    printers.add(np);
                }
            }
            config.setNetworkPrinters(printers);
        } catch (IOException e) {
            System.err.println("Failed to load config: " + e.getMessage());
        }
        return config;
    }

    public void save(AppConfig config) {
        try {
            Files.createDirectories(configDir);
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("serverPort", config.getServerPort());
            map.put("bindAllInterfaces", config.isBindAllInterfaces());
            map.put("defaultPrinterId", config.getDefaultPrinterId());

            List<Map<String, Object>> printers = new ArrayList<Map<String, Object>>();
            for (NetworkPrinter np : config.getNetworkPrinters()) {
                Map<String, Object> pm = new LinkedHashMap<String, Object>();
                pm.put("id", np.getId());
                pm.put("name", np.getName());
                pm.put("host", np.getHost());
                pm.put("port", np.getPort());
                pm.put("encoding", np.getEncoding());
                printers.add(pm);
            }
            map.put("networkPrinters", printers);

            String text = Json.stringify(map);
            Files.write(configFile, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }
}
