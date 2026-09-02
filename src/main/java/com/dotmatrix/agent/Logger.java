package com.dotmatrix.agent;

/**
 * Shared logging callback used across the agent (HTTP server, print
 * manager) so every component can report to both stdout (headless mode)
 * and the GUI's Activity Log (when present).
 */
public interface Logger {
    void log(String message);
}
