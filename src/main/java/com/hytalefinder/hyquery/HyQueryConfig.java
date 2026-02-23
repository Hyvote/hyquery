package com.hytalefinder.hyquery;

/**
 * Configuration for HyQuery plugin.
 *
 * HyQuery uses the same port as the game server by intercepting UDP packets
 * with magic bytes (HYQUERY\0) before the QUIC codec processes them.
 *
 * Server name and max players are pulled from the server's config.json.
 * This config controls query-specific settings including custom MOTD.
 *
 * @param enabled           Whether the query server is enabled.
 * @param showPlayerList    Whether to show online player names in responses.
 * @param showPlugins       Whether to show installed plugins list in responses.
 * @param useCustomMotd     Whether to use custom MOTD instead of server config MOTD.
 * @param customMotd        Custom MOTD with Minecraft color code support (e.g., section-sign a for green, section-sign l for bold).
 * @param rateLimitEnabled  Whether to enable per-IP rate limiting.
 * @param rateLimitPerSecond Maximum requests per second per IP address.
 * @param rateLimitBurst    Maximum burst capacity (requests allowed in a quick burst).
 * @param cacheEnabled      Whether to enable response caching.
 * @param cacheTtlSeconds   How long to cache responses in seconds.
 * @param v1Enabled         Whether legacy V1 request handling is enabled.
 * @param v2Enabled         Whether OneQuery V2 request handling is enabled.
 * @param challengeTokenValiditySeconds V2 challenge token validity period in seconds.
 * @param challengeSecret   Optional V2 challenge secret (blank = ephemeral secret each start).
 * @param network           Network mode configuration for multi-server setups.
 */
public record HyQueryConfig(
    boolean enabled,
    boolean showPlayerList,
    boolean showPlugins,
    boolean useCustomMotd,
    String customMotd,
    boolean rateLimitEnabled,
    int rateLimitPerSecond,
    int rateLimitBurst,
    boolean cacheEnabled,
    int cacheTtlSeconds,
    boolean v1Enabled,
    boolean v2Enabled,
    int challengeTokenValiditySeconds,
    String challengeSecret,
    HyQueryNetworkConfig network
) {
    /**
     * Returns a HyQueryConfig with default values.
     * By default, anonymous mode only shows basic info (name, motd, players count, version).
     * Rate limiting and caching are enabled by default for security.
     * Network mode is disabled by default.
     */
    public static HyQueryConfig defaults() {
        return new HyQueryConfig(
            true,           // enabled
            false,          // showPlayerList (anonymous mode default)
            false,          // showPlugins (anonymous mode default)
            false,          // useCustomMotd (use server config by default)
            "Welcome to My Server!",  // customMotd (example)
            true,           // rateLimitEnabled (security default)
            10,             // rateLimitPerSecond (10 requests/sec per IP)
            20,             // rateLimitBurst (allow short bursts)
            true,           // cacheEnabled (security default)
            5,              // cacheTtlSeconds (refresh every 5 seconds)
            true,           // v1Enabled
            true,           // v2Enabled
            30,             // challengeTokenValiditySeconds
            "",             // challengeSecret (ephemeral when empty)
            null            // network (disabled by default)
        );
    }

    /**
     * Check if network mode is enabled.
     */
    public boolean isNetworkEnabled() {
        return network != null && network.enabled();
    }

    /**
     * Check if this server is a network primary.
     */
    public boolean isNetworkPrimary() {
        return network != null && network.isPrimary();
    }

    /**
     * Check if this server is a network worker.
     */
    public boolean isNetworkWorker() {
        return network != null && network.isWorker();
    }
}
