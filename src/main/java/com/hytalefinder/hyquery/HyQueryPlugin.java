package com.hytalefinder.hyquery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.netty.channel.Channel;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * HyQuery Plugin - UDP query protocol for Hytale servers.
 *
 * Uses the same port as the game server by intercepting UDP packets
 * with magic bytes (HYQUERY\0) before the QUIC codec processes them.
 *
 * Supports network mode for multi-server setups:
 * - Primary: Aggregates status from worker servers
 * - Worker: Sends status updates to primary server
 */
public class HyQueryPlugin extends JavaPlugin {

    private static final String CONFIG_FILE = "config.json";
    private static final String HANDLER_NAME = "hyquery-handler";
    private static final String DATA_FOLDER = "HyQuery";
    private static final String LEGACY_DATA_FOLDER = "Hyvote_HyQuery";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HyQueryConfig config;
    private HyQueryHandler queryHandler;
    private HyQueryRateLimiter rateLimiter;
    private HyQueryCache cache;
    private HyQueryNetworkManager networkManager;

    public HyQueryPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("HyQuery loading...");
        loadConfig();
    }

    @Override
    protected void start() {
        if (!config.enabled()) {
            getLogger().at(Level.INFO).log("HyQuery is disabled in config");
            return;
        }

        getLogger().at(Level.INFO).log("HyQuery starting...");

        // Initialize rate limiter
        if (config.rateLimitEnabled()) {
            rateLimiter = new HyQueryRateLimiter(config.rateLimitBurst(), config.rateLimitPerSecond());
            getLogger().at(Level.INFO).log("Rate limiting enabled: %d req/s, burst %d",
                config.rateLimitPerSecond(), config.rateLimitBurst());
        }

        // Initialize response cache
        if (config.cacheEnabled()) {
            cache = new HyQueryCache(this, config.cacheTtlSeconds());
            getLogger().at(Level.INFO).log("Response caching enabled: %d second TTL", config.cacheTtlSeconds());
        }

        // Initialize network manager (if network mode enabled)
        if (config.isNetworkEnabled()) {
            networkManager = new HyQueryNetworkManager(this, config.network(),
                java.util.logging.Logger.getLogger("HyQuery"));
            networkManager.start();
        }

        // Create the query handler
        queryHandler = new HyQueryHandler(this, java.util.logging.Logger.getLogger("HyQuery"),
            rateLimiter, cache);

        // Register handler on all listener channels
        int registeredCount = 0;
        for (Channel channel : ServerManager.get().getListeners()) {
            try {
                // Add handler at the front of the pipeline (before QUIC codec)
                channel.pipeline().addFirst(HANDLER_NAME, queryHandler);
                registeredCount++;
                getLogger().at(Level.FINE).log("Registered query handler on channel: %s", channel);
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log("Failed to register query handler on channel %s: %s",
                        channel, e.getMessage());
            }
        }

        if (registeredCount > 0) {
            int port = getGamePort();
            getLogger().at(Level.INFO).log("HyQuery enabled on UDP port %d (%d channels)", port, registeredCount);
            getLogger().at(Level.INFO).log("  - Server name: %s", getServerName());

            String motd = config.useCustomMotd() ? config.customMotd() : getMotd();
            getLogger().at(Level.INFO).log("  - MOTD: %s %s",
                motd.isEmpty() ? "(empty)" : motd,
                config.useCustomMotd() ? "(custom)" : "(server config)");

            getLogger().at(Level.INFO).log("  - Max players: %d", getMaxPlayers());
            getLogger().at(Level.INFO).log("  - Show player list: %s", config.showPlayerList());
            getLogger().at(Level.INFO).log("  - Show plugins: %s", config.showPlugins());

            // Log network mode status
            if (config.isNetworkPrimary()) {
                getLogger().at(Level.INFO).log("  - Network mode: PRIMARY");
            } else if (config.isNetworkWorker()) {
                getLogger().at(Level.INFO).log("  - Network mode: WORKER (-> %s:%d)",
                    config.network().primaryHost(), config.network().primaryPort());
            }
        } else {
            getLogger().at(Level.WARNING).log("HyQuery failed to register on any channels");
        }
    }

    @Override
    protected void shutdown() {
        // Stop network manager
        if (networkManager != null) {
            networkManager.stop();
            networkManager = null;
        }

        // Remove handler from all channels
        for (Channel channel : ServerManager.get().getListeners()) {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            } catch (Exception e) {
                getLogger().at(Level.FINE).log("Error removing handler from channel: %s", e.getMessage());
            }
        }

        getLogger().at(Level.INFO).log("HyQuery disabled");
    }

    public HyQueryConfig getQueryConfig() {
        return config;
    }

    public HyQueryCache getCache() {
        return cache;
    }

    public HyQueryNetworkManager getNetworkManager() {
        return networkManager;
    }

    /**
     * Get game server port.
     */
    public int getGamePort() {
        try {
            return Options.getOptionSet().valueOf(Options.BIND).getPort();
        } catch (Exception e) {
            return 5520;
        }
    }

    /**
     * Get server name from server config.
     */
    public String getServerName() {
        try {
            return HytaleServer.get().getConfig().getServerName();
        } catch (Exception e) {
            return "Hytale Server";
        }
    }

    /**
     * Get MOTD from server config.
     */
    public String getMotd() {
        try {
            return HytaleServer.get().getConfig().getMotd();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get max players from server config.
     */
    public int getMaxPlayers() {
        try {
            return HytaleServer.get().getConfig().getMaxPlayers();
        } catch (Exception e) {
            return 100;
        }
    }

    private void loadConfig() {
        getLogger().at(Level.INFO).log("Loading configuration...");

        // Get the mods directory (parent of the default data directory)
        Path modsDir = getDataDirectory().getParent();
        Path dataDir = modsDir.resolve(DATA_FOLDER);
        Path legacyDir = modsDir.resolve(LEGACY_DATA_FOLDER);

        // Migrate from legacy folder if it exists and new folder doesn't
        if (Files.exists(legacyDir) && !Files.exists(dataDir)) {
            try {
                Files.move(legacyDir, dataDir);
                getLogger().at(Level.INFO).log("Migrated config folder from %s to %s", LEGACY_DATA_FOLDER, DATA_FOLDER);
            } catch (IOException e) {
                getLogger().at(Level.WARNING).log("Failed to migrate config folder: %s", e.getMessage());
            }
        }

        Path configPath = dataDir.resolve(CONFIG_FILE);
        HyQueryConfig defaults = HyQueryConfig.defaults();

        try {
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                HyQueryConfig loaded = GSON.fromJson(json, HyQueryConfig.class);

                // Use defaults for any null/missing fields (backwards compatibility)
                this.config = new HyQueryConfig(
                    loaded.enabled(),
                    loaded.showPlayerList(),
                    loaded.showPlugins(),
                    loaded.useCustomMotd(),
                    loaded.customMotd() != null ? loaded.customMotd() : defaults.customMotd(),
                    // New security fields - default to enabled if missing from old config
                    loaded.rateLimitPerSecond() > 0 ? loaded.rateLimitEnabled() : defaults.rateLimitEnabled(),
                    loaded.rateLimitPerSecond() > 0 ? loaded.rateLimitPerSecond() : defaults.rateLimitPerSecond(),
                    loaded.rateLimitBurst() > 0 ? loaded.rateLimitBurst() : defaults.rateLimitBurst(),
                    loaded.cacheTtlSeconds() > 0 ? loaded.cacheEnabled() : defaults.cacheEnabled(),
                    loaded.cacheTtlSeconds() > 0 ? loaded.cacheTtlSeconds() : defaults.cacheTtlSeconds(),
                    // Network config - apply defaults to nested object
                    HyQueryNetworkConfig.withDefaults(loaded.network())
                );

                // Rewrite config to include new fields if they were missing
                Files.writeString(configPath, GSON.toJson(config));
                getLogger().at(Level.INFO).log("Loaded configuration from %s", configPath);
            } else {
                this.config = defaults;
                Files.createDirectories(dataDir);
                Files.writeString(configPath, GSON.toJson(config));
                getLogger().at(Level.INFO).log("Created default configuration at %s", configPath);
            }
        } catch (IOException e) {
            getLogger().at(Level.WARNING).log("Failed to load/save config, using defaults: %s", e.getMessage());
            this.config = defaults;
        }
    }
}
