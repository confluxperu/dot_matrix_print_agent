package com.dotmatrix.agent.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Keeps the agent reachable in the system tray so the HTTP server can keep
 * running in the background after the window is closed.
 */
public class TrayManager {

    private final MainFrame frame;
    private TrayIcon trayIcon;

    public TrayManager(MainFrame frame) {
        this.frame = frame;
    }

    public boolean isAvailable() {
        return trayIcon != null;
    }

    public void install() throws AWTException {
        SystemTray tray = SystemTray.getSystemTray();

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(new Color(0x33, 0x33, 0x33));
        g.fillRect(0, 0, 16, 16);
        g.setColor(Color.WHITE);
        g.drawString("P", 4, 12);
        g.dispose();

        PopupMenu popup = new PopupMenu();
        MenuItem showItem = new MenuItem("Show");
        MenuItem exitItem = new MenuItem("Exit");
        showItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showFrame();
            }
        });
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frame.shutdown();
            }
        });
        popup.add(showItem);
        popup.addSeparator();
        popup.add(exitItem);

        trayIcon = new TrayIcon(icon, "Dot Matrix Print Agent", popup);
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showFrame();
            }
        });
        tray.add(trayIcon);
    }

    private void showFrame() {
        frame.setVisible(true);
        frame.setExtendedState(Frame.NORMAL);
        frame.toFront();
    }

    public void uninstall() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }
}
