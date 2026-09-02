package com.dotmatrix.agent.ui;

import com.dotmatrix.agent.Logger;
import com.dotmatrix.agent.config.AppConfig;
import com.dotmatrix.agent.config.ConfigStore;
import com.dotmatrix.agent.model.NetworkPrinter;
import com.dotmatrix.agent.print.PrintAgentException;
import com.dotmatrix.agent.print.PrintManager;
import com.dotmatrix.agent.print.PrinterInfo;
import com.dotmatrix.agent.server.HttpApiServer;
import com.dotmatrix.agent.update.UpdateChecker;
import com.dotmatrix.agent.update.UpdateInfo;
import com.dotmatrix.agent.update.UpdateInstaller;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.SystemTray;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Desktop UI: shows the local (OS) printers, lets the user manage
 * network (IP/port) printers, pick which one is the default (so Odoo's
 * print button never has to ask), and reports the local HTTP API status.
 */
public class MainFrame extends JFrame {

    private final AppConfig config;
    private final ConfigStore configStore;
    private final PrintManager printManager;
    private final HttpApiServer server;

    private final DefaultListModel<String> localPrintersModel = new DefaultListModel<String>();
    private final JList<String> localPrintersList = new JList<String>(localPrintersModel);
    private List<PrinterInfo> localPrinterInfos = java.util.Collections.emptyList();

    private final DefaultTableModel networkTableModel = new DefaultTableModel(
            new Object[]{"Default", "Name", "Host", "Port", "Encoding"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable networkTable = new JTable(networkTableModel);

    private final JTextArea logArea = new JTextArea();
    private final JLabel serverStatusLabel = new JLabel();
    private final JLabel defaultPrinterLabel = new JLabel();
    private final JSpinner portSpinner;
    private final JCheckBox bindAllCheckbox;

    private final JPanel updateBanner = new JPanel(new BorderLayout(8, 0));
    private final JLabel updateLabel = new JLabel();
    private final JButton updateButton = new JButton("Update Now");
    private UpdateInfo pendingUpdate;

    private TrayManager trayManager;

    public MainFrame(AppConfig config, ConfigStore configStore, PrintManager printManager, HttpApiServer server) {
        super("Dot Matrix Print Agent");
        this.config = config;
        this.configStore = configStore;
        this.printManager = printManager;
        this.server = server;

        this.portSpinner = new JSpinner(new SpinnerNumberModel(config.getServerPort(), 1, 65535, 1));
        this.bindAllCheckbox = new JCheckBox(
                "Accept connections from other computers (0.0.0.0)", config.isBindAllInterfaces());

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(820, 640);
        setLocationRelativeTo(null);

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        defaultPrinterLabel.setFont(defaultPrinterLabel.getFont().deriveFont(Font.BOLD));
        topBar.add(defaultPrinterLabel, BorderLayout.CENTER);
        JButton clearDefaultButton = new JButton("Clear Default");
        clearDefaultButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearDefaultPrinter();
            }
        });
        topBar.add(clearDefaultButton, BorderLayout.EAST);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Local Printers", buildLocalPanel());
        tabs.addTab("Network Printers", buildNetworkPanel());
        tabs.addTab("Server", buildServerPanel());

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Activity Log"));
        logScroll.setPreferredSize(new Dimension(820, 160));

        buildUpdateBanner();
        JPanel northContainer = new JPanel();
        northContainer.setLayout(new BoxLayout(northContainer, BoxLayout.Y_AXIS));
        northContainer.add(updateBanner);
        northContainer.add(topBar);

        setLayout(new BorderLayout());
        add(northContainer, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(logScroll, BorderLayout.SOUTH);

        setupTray();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (trayManager != null && trayManager.isAvailable()) {
                    setVisible(false);
                    log("Window hidden. The agent keeps running in the system tray.");
                } else {
                    shutdown();
                }
            }
        });

        refreshLocalPrinters();
        refreshNetworkTable();
        updateServerStatus();
        updateDefaultPrinterLabel();
        log("Dot Matrix Print Agent started.");

        checkForUpdatesInBackground();
    }

    private JPanel buildLocalPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JScrollPane(localPrintersList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new JButton("Refresh");
        JButton setDefaultButton = new JButton("Set as Default");
        JButton testButton = new JButton("Send Test Print");
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshLocalPrinters();
            }
        });
        setDefaultButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setSelectedLocalAsDefault();
            }
        });
        testButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testPrintLocal();
            }
        });
        buttons.add(refreshButton);
        buttons.add(setDefaultButton);
        buttons.add(testButton);
        panel.add(buttons, BorderLayout.NORTH);

        JLabel hint = new JLabel(
                "Printers marked ⚠ only render through Windows' graphics pipeline and typically "
                        + "produce an empty document with raw/dot matrix output (e.g. \"Microsoft Print to PDF\").");
        hint.setForeground(new Color(0x99, 0x55, 0x00));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildNetworkPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JScrollPane(networkTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addButton = new JButton("Add...");
        JButton editButton = new JButton("Edit...");
        JButton removeButton = new JButton("Remove");
        JButton setDefaultButton = new JButton("Set as Default");
        JButton testButton = new JButton("Test Connection");
        JButton testPrintButton = new JButton("Send Test Print");

        addButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addNetworkPrinter();
            }
        });
        editButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editNetworkPrinter();
            }
        });
        removeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeNetworkPrinter();
            }
        });
        setDefaultButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setSelectedNetworkAsDefault();
            }
        });
        testButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testNetworkConnection();
            }
        });
        testPrintButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testPrintNetwork();
            }
        });

        buttons.add(addButton);
        buttons.add(editButton);
        buttons.add(removeButton);
        buttons.add(setDefaultButton);
        buttons.add(testButton);
        buttons.add(testPrintButton);
        panel.add(buttons, BorderLayout.NORTH);
        return panel;
    }

    private JPanel buildServerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        serverStatusLabel.setFont(serverStatusLabel.getFont().deriveFont(Font.BOLD));
        panel.add(serverStatusLabel);
        panel.add(Box.createVerticalStrut(12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Port:"), c);
        c.gridx = 1;
        form.add(portSpinner, c);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        form.add(bindAllCheckbox, c);
        panel.add(form);
        panel.add(Box.createVerticalStrut(12));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton applyButton = new JButton("Apply and Restart Server");
        applyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                applyServerSettings();
            }
        });
        buttons.add(applyButton);
        panel.add(buttons);
        panel.add(Box.createVerticalStrut(12));

        JTextArea help = new JTextArea(
                "Any HTTP client (Odoo included) can submit a print job with:\n\n"
                + "  POST http://<this-computer-ip>:" + config.getServerPort() + "/print\n"
                + "  Body: {\"content\": \"<text>\"}\n\n"
                + "The 'printer' field is optional: when omitted, the job goes to whichever\n"
                + "printer is marked as Default in the Local/Network Printers tabs, so the\n"
                + "caller (e.g. the Odoo button) never has to ask the user to pick one.\n"
                + "Add {\"printer\": \"<id from /printers>\"} to target a specific printer instead.\n\n"
                + "List available printers (local + network) with:\n\n"
                + "  GET http://<this-computer-ip>:" + config.getServerPort() + "/printers\n\n"
                + "By default the server only listens on 127.0.0.1, so it can only be\n"
                + "reached from this same computer. Enable the checkbox above only if\n"
                + "another computer on the network needs to submit print jobs here.");
        help.setEditable(false);
        help.setOpaque(false);
        panel.add(help);

        return panel;
    }

    private void refreshLocalPrinters() {
        localPrintersModel.clear();
        localPrinterInfos = new java.util.ArrayList<PrinterInfo>();
        for (PrinterInfo p : printManager.listAll()) {
            if (p.getType() == PrinterInfo.Type.LOCAL) {
                localPrinterInfos.add(p);
                StringBuilder label = new StringBuilder(p.getName());
                if (p.isDefault()) {
                    label.append("  [DEFAULT]");
                }
                if (printManager.isLikelyVirtual(p.getName())) {
                    label.append("  ⚠");
                }
                localPrintersModel.addElement(label.toString());
            }
        }
        log("Local printers refreshed (" + localPrintersModel.size() + " found).");
    }

    private void refreshNetworkTable() {
        networkTableModel.setRowCount(0);
        String defaultId = config.getDefaultPrinterId();
        for (NetworkPrinter np : config.getNetworkPrinters()) {
            boolean isDefault = (PrintManager.NETWORK_PREFIX + np.getId()).equals(defaultId);
            networkTableModel.addRow(new Object[]{
                    isDefault ? "✓" : "", np.getName(), np.getHost(), np.getPort(), np.getEncoding()
            });
        }
    }

    private void updateDefaultPrinterLabel() {
        String defaultId = config.getDefaultPrinterId();
        if (defaultId == null || defaultId.trim().isEmpty()) {
            defaultPrinterLabel.setText("Default printer: (none configured yet - Odoo prints will fail)");
            return;
        }
        for (PrinterInfo p : printManager.listAll()) {
            if (defaultId.equals(p.getId())) {
                defaultPrinterLabel.setText("Default printer: " + p.getName() + " (" + p.getType() + ")");
                return;
            }
        }
        defaultPrinterLabel.setText("Default printer: (configured printer not found anymore)");
    }

    private void setDefaultPrinter(String printerId, String label) {
        config.setDefaultPrinterId(printerId);
        configStore.save(config);
        refreshLocalPrinters();
        refreshNetworkTable();
        updateDefaultPrinterLabel();
        log("Default printer set to '" + label + "'. Odoo's print button will use it automatically.");
    }

    private void clearDefaultPrinter() {
        config.setDefaultPrinterId(null);
        configStore.save(config);
        refreshLocalPrinters();
        refreshNetworkTable();
        updateDefaultPrinterLabel();
        log("Default printer cleared.");
    }

    private void setSelectedLocalAsDefault() {
        int index = localPrintersList.getSelectedIndex();
        if (index < 0 || index >= localPrinterInfos.size()) {
            JOptionPane.showMessageDialog(this, "Select a local printer first.");
            return;
        }
        PrinterInfo info = localPrinterInfos.get(index);
        if (printManager.isLikelyVirtual(info.getName())) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "'" + info.getName() + "' looks like a virtual/document-writer printer.\n"
                            + "It will likely accept jobs but produce empty documents.\n"
                            + "Set it as default anyway?",
                    "Warning", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        setDefaultPrinter(info.getId(), info.getName());
    }

    private void setSelectedNetworkAsDefault() {
        NetworkPrinter selected = getSelectedNetworkPrinter();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a printer first.");
            return;
        }
        setDefaultPrinter(PrintManager.NETWORK_PREFIX + selected.getId(), selected.getName());
    }

    private void addNetworkPrinter() {
        NetworkPrinterDialog dialog = new NetworkPrinterDialog(this, null);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            NetworkPrinter np = dialog.apply(null);
            config.getNetworkPrinters().add(np);
            configStore.save(config);
            refreshNetworkTable();
            log("Added network printer '" + np.getName() + "' (" + np.getHost() + ":" + np.getPort() + ").");
        }
    }

    private NetworkPrinter getSelectedNetworkPrinter() {
        int row = networkTable.getSelectedRow();
        if (row < 0 || row >= config.getNetworkPrinters().size()) {
            return null;
        }
        return config.getNetworkPrinters().get(row);
    }

    private void editNetworkPrinter() {
        NetworkPrinter selected = getSelectedNetworkPrinter();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a printer first.");
            return;
        }
        NetworkPrinterDialog dialog = new NetworkPrinterDialog(this, selected);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            dialog.apply(selected);
            configStore.save(config);
            refreshNetworkTable();
            updateDefaultPrinterLabel();
            log("Updated network printer '" + selected.getName() + "'.");
        }
    }

    private void removeNetworkPrinter() {
        NetworkPrinter selected = getSelectedNetworkPrinter();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a printer first.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(
                this, "Remove '" + selected.getName() + "'?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            boolean wasDefault = (PrintManager.NETWORK_PREFIX + selected.getId())
                    .equals(config.getDefaultPrinterId());
            config.getNetworkPrinters().remove(selected);
            if (wasDefault) {
                config.setDefaultPrinterId(null);
            }
            configStore.save(config);
            refreshNetworkTable();
            updateDefaultPrinterLabel();
            log("Removed network printer '" + selected.getName() + "'.");
        }
    }

    private void testNetworkConnection() {
        NetworkPrinter selected = getSelectedNetworkPrinter();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a printer first.");
            return;
        }
        try {
            printManager.testConnection(selected);
            log("Connection OK: " + selected.getName() + " (" + selected.getHost() + ":" + selected.getPort() + ")");
            JOptionPane.showMessageDialog(this, "Connection successful.");
        } catch (PrintAgentException e) {
            log("Connection FAILED: " + selected.getName() + " - " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Connection failed: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String buildTestContent() {
        return "DOT MATRIX PRINT AGENT - TEST PAGE\n"
                + "Generated: " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()) + "\n"
                + "--------------------------------------------------------------------------------\n"
                + "If you can read this, the connection to the printer is working correctly.\n"
                + "\f";
    }

    private void testPrintLocal() {
        int index = localPrintersList.getSelectedIndex();
        if (index < 0 || index >= localPrinterInfos.size()) {
            JOptionPane.showMessageDialog(this, "Select a local printer first.");
            return;
        }
        PrinterInfo info = localPrinterInfos.get(index);
        try {
            printManager.printRaw(info.getId(), buildTestContent(), null);
            log("Test print sent to local printer '" + info.getName() + "'.");
        } catch (PrintAgentException e) {
            log("ERROR printing to '" + info.getName() + "': " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Print failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void testPrintNetwork() {
        NetworkPrinter selected = getSelectedNetworkPrinter();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a printer first.");
            return;
        }
        try {
            printManager.printRaw(PrintManager.NETWORK_PREFIX + selected.getId(), buildTestContent(), null);
            log("Test print sent to network printer '" + selected.getName() + "'.");
        } catch (PrintAgentException e) {
            log("ERROR printing to '" + selected.getName() + "': " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Print failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyServerSettings() {
        config.setServerPort((Integer) portSpinner.getValue());
        config.setBindAllInterfaces(bindAllCheckbox.isSelected());
        configStore.save(config);
        try {
            server.start();
            log("Server restarted with new settings.");
        } catch (IOException e) {
            log("ERROR restarting server: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Could not restart server: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
        updateServerStatus();
    }

    private void updateServerStatus() {
        serverStatusLabel.setText(server.isRunning()
                ? "Server RUNNING on port " + config.getServerPort()
                : "Server STOPPED");
    }

    private void buildUpdateBanner() {
        updateBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        updateBanner.setBackground(new Color(0xFF, 0xF3, 0xCD));
        updateBanner.setOpaque(true);
        updateLabel.setFont(updateLabel.getFont().deriveFont(Font.BOLD));
        updateBanner.add(updateLabel, BorderLayout.CENTER);
        updateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performUpdate();
            }
        });
        updateBanner.add(updateButton, BorderLayout.EAST);
        updateBanner.setVisible(false);
    }

    /**
     * Runs once on startup, in the background so it never delays showing
     * the window. Queries the project's public GitHub Releases feed (see
     * {@link UpdateChecker}) - silently does nothing if unreachable, not
     * newer, or if this build has no packaged version (dev/classpath run).
     */
    private void checkForUpdatesInBackground() {
        final String currentVersion = getClass().getPackage().getImplementationVersion();
        Thread checkThread = new Thread("update-check") {
            @Override
            public void run() {
                UpdateInfo info = new UpdateChecker().checkForUpdate(currentVersion, backgroundLogger());
                if (info.isAvailable()) {
                    final UpdateInfo finalInfo = info;
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateBanner(finalInfo);
                        }
                    });
                }
            }
        };
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private Logger backgroundLogger() {
        return new Logger() {
            @Override
            public void log(String message) {
                MainFrame.this.log(message);
            }
        };
    }

    private void showUpdateBanner(UpdateInfo info) {
        pendingUpdate = info;
        updateLabel.setText("A new version is available: v" + info.getLatestVersion());
        updateButton.setEnabled(true);
        updateButton.setText("Update Now");
        updateBanner.setVisible(true);
        log("Update available: v" + info.getLatestVersion() + ". Click 'Update Now' above to install it.");
    }

    /**
     * Downloads the installer matching this JVM's architecture and
     * launches it elevated and silent (one Windows security prompt), then
     * closes this application so Setup can freely replace the jar/JRE
     * files. The installer stops and restarts the background service on
     * its own.
     */
    private void performUpdate() {
        if (pendingUpdate == null) {
            return;
        }
        final UpdateInfo info = pendingUpdate;
        updateButton.setEnabled(false);
        updateButton.setText("Downloading...");

        Thread updateThread = new Thread("perform-update") {
            @Override
            public void run() {
                try {
                    UpdateInstaller installer = new UpdateInstaller();
                    Path installerPath = installer.download(info.getDownloadUrl(), info.getAssetName(), backgroundLogger());
                    installer.launchElevatedSilent(installerPath, backgroundLogger());
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            JOptionPane.showMessageDialog(MainFrame.this,
                                    "Update v" + info.getLatestVersion() + " is installing.\n"
                                            + "Approve the Windows security prompt if you see one.\n\n"
                                            + "This application will now close - the background service "
                                            + "restarts automatically once the update finishes.",
                                    "Updating", JOptionPane.INFORMATION_MESSAGE);
                            shutdown();
                        }
                    });
                } catch (final Exception e) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            log("Update failed: " + e.getMessage());
                            JOptionPane.showMessageDialog(MainFrame.this,
                                    "Could not download or launch the update: " + e.getMessage(),
                                    "Update failed", JOptionPane.ERROR_MESSAGE);
                            updateButton.setEnabled(true);
                            updateButton.setText("Update Now");
                        }
                    });
                }
            }
        };
        updateThread.setDaemon(true);
        updateThread.start();
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            trayManager = new TrayManager(this);
            trayManager.install();
        } catch (Exception e) {
            log("Could not set up system tray icon: " + e.getMessage());
        }
    }

    public void log(final String message) {
        final String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + message;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                logArea.append(line + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    public void shutdown() {
        server.stop();
        if (trayManager != null) {
            trayManager.uninstall();
        }
        dispose();
        System.exit(0);
    }
}
