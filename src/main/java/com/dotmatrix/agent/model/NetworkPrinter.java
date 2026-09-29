package com.dotmatrix.agent.model;

import java.util.UUID;

/**
 * A user-configured network (host/port) print target. Two kinds:
 * <ul>
 *   <li>{@link Type#RAW}: a printer with its own Ethernet/WiFi card,
 *   printed to via a raw TCP socket (the classic "JetDirect/RAW" protocol
 *   most dot matrix and receipt printers support on port 9100).</li>
 *   <li>{@link Type#AGENT}: another Dot Matrix Print Agent on the LAN
 *   (typically the PC the printer is physically attached to by USB). Jobs
 *   are forwarded to its {@code POST /print} and printed on whatever that
 *   agent has as default, so every PC can keep Odoo pointed at its own
 *   {@code 127.0.0.1} while sharing one printer per store.</li>
 * </ul>
 */
public class NetworkPrinter {

    public enum Type {
        RAW,
        AGENT
    }

    public static final int DEFAULT_RAW_PORT = 9100;
    public static final int DEFAULT_AGENT_PORT = 8787;

    private String id;
    private String name;
    private Type type;
    private String host;
    private int port;
    private String encoding;

    public NetworkPrinter() {
        this.id = UUID.randomUUID().toString();
        this.type = Type.RAW;
        this.port = DEFAULT_RAW_PORT;
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

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type != null ? type : Type.RAW;
    }

    public boolean isAgent() {
        return type == Type.AGENT;
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
