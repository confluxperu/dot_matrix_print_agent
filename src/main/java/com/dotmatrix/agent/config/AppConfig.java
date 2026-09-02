package com.dotmatrix.agent.config;

import com.dotmatrix.agent.model.NetworkPrinter;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {

    private int serverPort = 8787;
    private boolean bindAllInterfaces = false;
    private List<NetworkPrinter> networkPrinters = new ArrayList<NetworkPrinter>();
    private String defaultPrinterId;

    public String getDefaultPrinterId() {
        return defaultPrinterId;
    }

    public void setDefaultPrinterId(String defaultPrinterId) {
        this.defaultPrinterId = defaultPrinterId;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public boolean isBindAllInterfaces() {
        return bindAllInterfaces;
    }

    public void setBindAllInterfaces(boolean bindAllInterfaces) {
        this.bindAllInterfaces = bindAllInterfaces;
    }

    public List<NetworkPrinter> getNetworkPrinters() {
        return networkPrinters;
    }

    public void setNetworkPrinters(List<NetworkPrinter> networkPrinters) {
        this.networkPrinters = networkPrinters;
    }
}
