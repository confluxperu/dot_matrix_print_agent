package com.dotmatrix.agent.update;

import com.dotmatrix.agent.Logger;
import com.dotmatrix.agent.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Checks the project's public GitHub Releases feed
 * (https://github.com/confluxperu/dot_matrix_print_agent) for a version
 * newer than the one currently running, and resolves the installer asset
 * matching this JVM's architecture.
 *
 * <p>Only works once that repository is public - the plain REST endpoint
 * used here (`/releases/latest`) requires no authentication for a public
 * repo, and deliberately stays that way: no token is embedded in the
 * application (see the packaging/windows README for why).
 */
public final class UpdateChecker {

    private static final String API_URL =
            "https://api.github.com/repos/confluxperu/dot_matrix_print_agent/releases/latest";
    private static final int TIMEOUT_MS = 8000;

    /**
     * @param currentVersion this build's version (e.g. "1.0.0"), or {@code
     *                        null}/empty when running unpackaged (dev mode) -
     *                        in that case no check is performed.
     */
    public UpdateInfo checkForUpdate(String currentVersion, Logger logger) {
        if (currentVersion == null || currentVersion.trim().isEmpty()) {
            logger.log("Update check skipped: running without a packaged version.");
            return UpdateInfo.none();
        }
        try {
            String body = httpGet(API_URL);
            Object parsed = Json.parse(body);
            if (!(parsed instanceof Map)) {
                logger.log("Update check: unexpected response from GitHub.");
                return UpdateInfo.none();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> release = (Map<String, Object>) parsed;

            Object tagObj = release.get("tag_name");
            if (!(tagObj instanceof String)) {
                return UpdateInfo.none();
            }
            String latestVersion = stripLeadingV((String) tagObj);

            if (compareVersions(latestVersion, currentVersion) <= 0) {
                return UpdateInfo.none();
            }

            String assetName = "DotMatrixPrintAgentSetup-" + currentArch() + ".exe";
            String downloadUrl = findAssetUrl(release, assetName);
            if (downloadUrl == null) {
                logger.log("Update check: v" + latestVersion + " is available, but no '" + assetName
                        + "' asset was found on the release yet.");
                return UpdateInfo.none();
            }

            logger.log("Update available: v" + latestVersion + " (currently running v" + currentVersion + ").");
            return new UpdateInfo(true, latestVersion, downloadUrl, assetName);
        } catch (Exception e) {
            logger.log("Update check failed: " + e.getMessage());
            return UpdateInfo.none();
        }
    }

    /** "x86" or "x64", matching the installer asset names built by CI. */
    static String currentArch() {
        String arch = System.getProperty("os.arch", "");
        return arch.contains("64") ? "x64" : "x86";
    }

    @SuppressWarnings("unchecked")
    private static String findAssetUrl(Map<String, Object> release, String assetName) {
        Object assetsObj = release.get("assets");
        if (!(assetsObj instanceof List)) {
            return null;
        }
        for (Object item : (List<Object>) assetsObj) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> asset = (Map<String, Object>) item;
            if (assetName.equals(asset.get("name"))) {
                Object url = asset.get("browser_download_url");
                return url instanceof String ? (String) url : null;
            }
        }
        return null;
    }

    private static String stripLeadingV(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /**
     * Compares two dotted version strings (e.g. "1.2.0" vs "1.10.0")
     * numerically, segment by segment. Falls back to a plain string
     * comparison if either side does not look like a numeric version.
     */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int va = parseSegment(i < partsA.length ? partsA[i] : "0");
            int vb = parseSegment(i < partsB.length ? partsB[i] : "0");
            if (va != vb) {
                return va - vb;
            }
        }
        return 0;
    }

    private static int parseSegment(String segment) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub's API rejects requests with no User-Agent header.
            conn.setRequestProperty("User-Agent", "dotmatrix-print-agent-updater");

            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " from GitHub: " + body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
