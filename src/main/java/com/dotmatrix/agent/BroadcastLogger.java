package com.dotmatrix.agent;

import com.dotmatrix.agent.ui.MainFrame;

/**
 * Always logs to stdout; additionally forwards to the {@link MainFrame}'s
 * Activity Log once one has been created (GUI mode). The frame is attached
 * after construction since the server/print manager are started before the
 * window exists.
 */
public class BroadcastLogger implements Logger {

    private volatile MainFrame frame;

    public void attach(MainFrame frame) {
        this.frame = frame;
    }

    @Override
    public void log(String message) {
        System.out.println(message);
        MainFrame current = frame;
        if (current != null) {
            current.log(message);
        }
    }
}
