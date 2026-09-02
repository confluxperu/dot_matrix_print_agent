package com.dotmatrix.agent;

import com.dotmatrix.agent.config.AppConfig;
import com.dotmatrix.agent.config.ConfigStore;
import com.dotmatrix.agent.print.PrintManager;
import com.dotmatrix.agent.server.HttpApiServer;
import com.dotmatrix.agent.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point. Starts the local HTTP API and either the Swing UI, or,
 * with {@code --headless} (or on a machine without a display), runs as a
 * background service only.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);
        boolean headless = argList.contains("--headless") || GraphicsEnvironment.isHeadless();

        ConfigStore configStore = new ConfigStore();
        AppConfig config = configStore.load();
        configStore.save(config); // create the config file on first run

        final BroadcastLogger logger = new BroadcastLogger();
        PrintManager printManager = new PrintManager(config, logger);
        final HttpApiServer server = new HttpApiServer(config, printManager, logger);
        server.start();

        if (headless) {
            System.out.println("Dot Matrix Print Agent running in headless mode.");
            System.out.println("Press Ctrl+C to stop.");
            Thread.currentThread().join();
            return;
        }

        final AppConfig finalConfig = config;
        final ConfigStore finalConfigStore = configStore;
        final PrintManager finalPrintManager = printManager;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // fall back to the default look and feel
                }
                MainFrame frame = new MainFrame(finalConfig, finalConfigStore, finalPrintManager, server);
                logger.attach(frame);
                frame.setVisible(true);
            }
        });
    }
}
