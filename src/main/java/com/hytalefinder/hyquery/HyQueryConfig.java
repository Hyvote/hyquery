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
 * @param customMotd        Custom MOTD with Minecraft color code support (e.g., §aGreen §lBold).
 */
public record HyQueryConfig(
    boolean enabled,
    boolean showPlayerList,
    boolean showPlugins,
    boolean useCustomMotd,
    String customMotd
) {
    /**
     * Returns a HyQueryConfig with default values.
     * By default, anonymous mode only shows basic info (name, motd, players count, version).
     */
    public static HyQueryConfig defaults() {
        return new HyQueryConfig(
            true,           // enabled
            false,          // showPlayerList (anonymous mode default)
            false,          // showPlugins (anonymous mode default)
            false,          // useCustomMotd (use server config by default)
            "§aWelcome to §lMy Server§r!"  // customMotd (example with color codes)
        );
    }
}
