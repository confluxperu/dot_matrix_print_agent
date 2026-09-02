package com.dotmatrix.agent.update;

import com.dotmatrix.agent.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Downloads a Windows installer asset and launches it elevated and
 * silent, so the caller can then exit and let Setup replace files this
 * process might otherwise be holding open (the jar, the bundled JRE's
 * DLLs). The installer itself (see packaging/windows/installer.iss)
 * stops the running service before touching those files.
 */
public final class UpdateInstaller {

    private static final int TIMEOUT_MS = 15000;

    /** Downloads {@code url} to a fresh temp file and returns its path. */
    public Path download(String url, String suggestedFileName, Logger logger) throws IOException {
        Path dest = Files.createTempFile("dotmatrix-update-", "-" + suggestedFileName);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "dotmatrix-print-agent-updater");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " downloading " + url);
            }

            long totalBytes = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(dest)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    totalBytes += n;
                }
            }
            if (totalBytes < 1024L * 1024L) {
                // A real installer is tens of MB (it bundles a JRE); a
                // suspiciously small response usually means GitHub sent an
                // HTML/error page instead of the binary asset.
                throw new IOException("Downloaded file is unexpectedly small (" + totalBytes + " bytes)");
            }
            logger.log("Downloaded update installer to " + dest + " (" + (totalBytes / (1024 * 1024)) + " MB).");
            return dest;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Launches {@code installerExe} elevated (triggers one UAC prompt) and
     * fully silent, then returns immediately - it does not wait for the
     * install to finish. The caller should exit shortly after this returns
     * so Setup can freely replace files.
     */
    public void launchElevatedSilent(Path installerExe, Logger logger) throws IOException {
        String installerPath = installerExe.toAbsolutePath().toString().replace("'", "''");
        String psCommand = "Start-Process -FilePath '" + installerPath + "' "
                + "-ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Verb RunAs";
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(
                "powershell.exe", "-NoProfile", "-Command", psCommand));
        pb.redirectErrorStream(true);
        logger.log("Launching installer (a Windows security prompt will appear - please approve it)...");
        pb.start();
    }
}
