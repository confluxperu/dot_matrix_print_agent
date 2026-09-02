package com.dotmatrix.agent.model;

import java.util.UUID;

/**
 * A user-configured network (IP/port) printer, printed to via a raw TCP
 * socket (the classic "JetDirect/RAW" protocol most dot matrix and
 * receipt printers with an Ethernet/WiFi card support on port 9100).
 */
public class NetworkPrinter {

    private String id;
    private String name;
    private String host;
    private int port;
    private String encoding;

    public NetworkPrinter() {
        this.id = UUID.randomUUID().toString();
        this.port = 9100;
        this.encoding = "ISO-8859-1";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
}
