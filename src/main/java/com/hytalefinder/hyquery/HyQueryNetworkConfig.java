package com.hytalefinder.hyquery;

import java.util.List;

/**
 * Network configuration for HyQuery multi-server mode.
 *
 * Supports two roles:
 * - primary: Receives status updates from workers, aggregates data
 * - worker: Sends status updates to a primary server
 *
 * @param enabled              Whether network mode is enabled
 * @param role                 Server role: "primary" or "worker"
 * @param workerTimeoutSeconds (Primary) Seconds before marking worker offline
 * @param workers              (Primary) List of authorized workers
 * @param id                   (Worker) This worker's unique identifier
 * @param primaryHost          (Worker) Primary server hostname/IP
 * @param primaryPort          (Worker) Primary server port
 * @param key                  (Worker) Shared HMAC secret
 * @param updateIntervalSeconds (Worker) How often to send status updates
 */
public record HyQueryNetworkConfig(
    boolean enabled,
    String role,
    int workerTimeoutSeconds,
    List<HyQueryWorkerEntry> workers,
    String id,
    String primaryHost,
    int primaryPort,
    String key,
    int updateIntervalSeconds,
    boolean logStatusUpdates
) {
    public static final String ROLE_PRIMARY = "primary";
    public static final String ROLE_WORKER = "worker";

    /**
     * Returns default network config (disabled).
     */
    public static HyQueryNetworkConfig defaults() {
        return new HyQueryNetworkConfig(
            false,              // enabled
            ROLE_WORKER,        // role
            30,                 // workerTimeoutSeconds
            List.of(),          // workers
            "server-1",         // id
            "localhost",        // primaryHost
            5520,               // primaryPort
            "change-me-secret", // key
            5,                  // updateIntervalSeconds
            false               // logStatusUpdates (off by default to reduce spam)
        );
    }

    /**
     * Check if this server is configured as a primary.
     */
    public boolean isPrimary() {
        return enabled && ROLE_PRIMARY.equalsIgnoreCase(role);
    }

    /**
     * Check if this server is configured as a worker.
     */
    public boolean isWorker() {
        return enabled && ROLE_WORKER.equalsIgnoreCase(role);
    }

    /**
     * Create config with defaults for null fields (backwards compatibility).
     */
    public static HyQueryNetworkConfig withDefaults(HyQueryNetworkConfig loaded) {
        if (loaded == null) {
            return defaults();
        }
        HyQueryNetworkConfig def = defaults();
        return new HyQueryNetworkConfig(
            loaded.enabled(),
            loaded.role() != null ? loaded.role() : def.role(),
            loaded.workerTimeoutSeconds() > 0 ? loaded.workerTimeoutSeconds() : def.workerTimeoutSeconds(),
            loaded.workers() != null ? loaded.workers() : def.workers(),
            loaded.id() != null ? loaded.id() : def.id(),
            loaded.primaryHost() != null ? loaded.primaryHost() : def.primaryHost(),
            loaded.primaryPort() > 0 ? loaded.primaryPort() : def.primaryPort(),
            loaded.key() != null ? loaded.key() : def.key(),
            loaded.updateIntervalSeconds() > 0 ? loaded.updateIntervalSeconds() : def.updateIntervalSeconds(),
            loaded.logStatusUpdates()
        );
    }
}
