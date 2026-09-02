package com.dotmatrix.agent.update;

/**
 * Result of {@link UpdateChecker#checkForUpdate}.
 */
public final class UpdateInfo {

    private final boolean available;
    private final String latestVersion;
    private final String downloadUrl;
    private final String assetName;

    public UpdateInfo(boolean available, String latestVersion, String downloadUrl, String assetName) {
        this.available = available;
        this.latestVersion = latestVersion;
        this.downloadUrl = downloadUrl;
        this.assetName = assetName;
    }

    public static UpdateInfo none() {
        return new UpdateInfo(false, null, null, null);
    }

    public boolean isAvailable() {
        return available;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getAssetName() {
        return assetName;
    }
}
