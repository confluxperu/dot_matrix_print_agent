package com.dotmatrix.agent.print;

/**
 * Read-only view of a printer the agent can target, merging local
 * (OS-registered) printers and user-configured network printers into a
 * single list for the UI and the /printers HTTP endpoint.
 */
public class PrinterInfo {

    public enum Type {
        LOCAL,
        NETWORK
    }

    private final String id;
    private final String name;
    private final Type type;
    private final String detail;
    private final boolean isDefault;

    public PrinterInfo(String id, String name, Type type, String detail, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.detail = detail;
        this.isDefault = isDefault;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isDefault() {
        return isDefault;
    }
}
