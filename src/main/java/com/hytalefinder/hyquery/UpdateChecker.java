package com.hytalefinder.hyquery;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Utility class for checking GitHub releases for plugin updates.
 */
public final class UpdateChecker {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/Hyvote/hyquery/releases/latest";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/hytale/mods/hyquery";
    private static final String GITHUB_RELEASES_URL = "https://github.com/Hyvote/hyquery/releases/latest";
    private static final Gson GSON = new Gson();

    private static String cachedLatestVersion = null;
    private static long lastCheckTime = 0;
    private static final long CACHE_DURATION_MS = 10 * 60 * 1000;

    private UpdateChecker() {
    }

    public static String getCurseForgeUrl() {
        return CURSEFORGE_URL;
    }

    public static String getGitHubReleasesUrl() {
        return GITHUB_RELEASES_URL;
    }

    /**
     * Asynchronously checks if a newer version is available on GitHub.
     *
     * @param plugin         the plugin instance for logging
     * @param currentVersion the current plugin version (e.g. "2.0.0")
     * @return a future that resolves to the newer version or null when up to date / failed
     */
    public static CompletableFuture<String> checkForUpdate(HyQueryPlugin plugin, String currentVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String latestVersion = fetchLatestVersion(plugin);
                if (latestVersion == null) {
                    return null;
                }

                if (isNewerVersion(latestVersion, currentVersion)) {
                    plugin.getLogger().at(Level.INFO).log(
                            "Update available: %s -> %s", currentVersion, latestVersion);
                    return latestVersion;
                }

                plugin.getLogger().at(Level.FINE).log(
                        "Plugin is up to date (current: %s, latest: %s)", currentVersion, latestVersion);
                return null;
            } catch (Exception e) {
                plugin.getLogger().at(Level.WARNING).log(
                        "Failed to check for updates: %s", e.getMessage());
                return null;
            }
        });
    }

    private static String fetchLatestVersion(HyQueryPlugin plugin) throws IOException {
        long now = System.currentTimeMillis();
        if (cachedLatestVersion != null && (now - lastCheckTime) < CACHE_DURATION_MS) {
            plugin.getLogger().at(Level.FINE).log("Using cached version: %s", cachedLatestVersion);
            return cachedLatestVersion;
        }

        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(GITHUB_API_URL);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "HyQuery-UpdateChecker");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                plugin.getLogger().at(Level.WARNING).log(
                        "GitHub API returned status %d", responseCode);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                JsonObject json = GSON.fromJson(response.toString(), JsonObject.class);
                if (!json.has("tag_name") || json.get("tag_name").isJsonNull()) {
                    plugin.getLogger().at(Level.WARNING).log("GitHub API response missing tag_name field");
                    return null;
                }

                String tagName = json.get("tag_name").getAsString();
                String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;

                cachedLatestVersion = version;
                lastCheckTime = now;
                return version;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static boolean isNewerVersion(String latestVersion, String currentVersion) {
        String latestBase = normalizeVersion(latestVersion);
        String currentBase = normalizeVersion(currentVersion);

        String[] latestParts = latestBase.split("\\.");
        String[] currentParts = currentBase.split("\\.");

        int maxLength = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < maxLength; i++) {
            int latestPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
            int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

            if (latestPart > currentPart) {
                return true;
            }
            if (latestPart < currentPart) {
                return false;
            }
        }

        boolean latestIsPreRelease = latestVersion.contains("-");
        boolean currentIsPreRelease = currentVersion.contains("-");
        return !latestIsPreRelease && currentIsPreRelease;
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0";
        }
        int dashIndex = version.indexOf('-');
        return dashIndex > 0 ? version.substring(0, dashIndex) : version;
    }

    private static int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void clearCache() {
        cachedLatestVersion = null;
        lastCheckTime = 0;
    }
}
