package com.dotmatrix.agent;

import com.dotmatrix.agent.config.AppConfig;
import com.dotmatrix.agent.config.ConfigStore;
import com.dotmatrix.agent.print.PrintManager;
import com.dotmatrix.agent.server.HttpApiServer;
import com.dotmatrix.agent.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point. Starts the local HTTP API and either the Swing UI, or,
 * with {@code --headless} (or on a machine without a display), runs as a
 * background process only.
 *
 * <p>On Windows the installer launches the GUI with {@code --minimized} at
 * every user logon, so the agent starts straight into the system tray. If
 * the agent is already running when it is launched again (desktop icon,
 * Start Menu), the new copy asks the running one to show its window and
 * exits, instead of failing on the busy HTTP port; a second {@code
 * --minimized} launch just exits.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);
        boolean headless = argList.contains("--headless") || GraphicsEnvironment.isHeadless();
        final boolean minimized = argList.contains("--minimized");

        ConfigStore configStore = new ConfigStore();
        AppConfig config = configStore.load();
        configStore.save(config); // create the config file on first run

        final BroadcastLogger logger = new BroadcastLogger();
        PrintManager printManager = new PrintManager(config, logger);
        final HttpApiServer server = new HttpApiServer(config, printManager, logger);
        // Registered before the server starts: a second launch may ask for
        // the window while it is still being built, so remember the request
        // and honour it once the frame exists.
        final AtomicReference<MainFrame> frameRef = new AtomicReference<MainFrame>();
        final AtomicBoolean showRequested = new AtomicBoolean(false);
        if (!headless) {
            server.setShowWindowAction(new Runnable() {
                @Override
                public void run() {
                    MainFrame frame = frameRef.get();
                    if (frame != null) {
                        frame.showWindow();
                    } else {
                        showRequested.set(true);
                    }
                }
            });
        }
        try {
            server.start();
        } catch (IOException e) {
            if (headless) {
                throw e;
            }
            // The logon autostart (--minimized) just leaves an agent that is
            // already running alone; a launch from the icon brings it up.
            String path = minimized ? "/status" : "/ui/show";
            String reply = localGet(config.getServerPort(), path);
            if (reply != null && (!minimized || reply.contains("dotmatrix-print-agent"))) {
                return;
            }
            // Port taken by some other program: still open the window, which
            // reports the server as stopped and lets the user pick another port.
            logger.log("ERROR starting HTTP server on port " + config.getServerPort() + ": " + e.getMessage());
        }

        if (headless) {
            System.out.println("Dot Matrix Print Agent running in headless mode.");
            System.out.println("Press Ctrl+C to stop.");
            // Lets a service manager (e.g. WinSW on Windows) stop the process
            // cleanly - releases the HTTP port immediately instead of relying
            // on the OS to reclaim it after a hard kill.
            Runtime.getRuntime().addShutdownHook(new Thread("shutdown") {
                @Override
                public void run() {
                    System.out.println("Stopping Dot Matrix Print Agent...");
                    server.stop();
                }
            });
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
                frameRef.set(frame);
                // Without a tray icon there would be no way back to a hidden
                // window, so --minimized only applies when the tray works.
                if (!minimized || !frame.hasTray() || showRequested.get()) {
                    frame.setVisible(true);
                }
            }
        });
    }

    /**
     * GETs {@code path} from whatever listens on {@code port} on this
     * computer. Returns the body on HTTP 200, or null when nothing answers
     * or the answer is an error.
     */
    private static String localGet(int port, String path) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try {
                if (conn.getResponseCode() != 200) {
                    return null;
                }
                InputStream in = conn.getInputStream();
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
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
