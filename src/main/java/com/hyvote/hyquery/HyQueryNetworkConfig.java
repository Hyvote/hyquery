package com.hyvote.hyquery;

import java.util.ArrayList;
import java.util.List;

/**
 * Network configuration for HyQuery multi-server mode.
 *
 * Supports two roles:
 * - primary: Receives status updates from workers, aggregates data
 * - worker: Sends status updates to primary server(s)
 *
 * Hub clustering: Workers can send updates to multiple primary servers by
 * configuring the "primaries" list. Each primary independently aggregates
 * network status, allowing any hub to answer queries with full network data.
 *
 * @param enabled              Whether network mode is enabled
 * @param role                 Server role: "primary" or "worker"
 * @param workerTimeoutSeconds (Primary) Seconds before marking worker offline
 * @param workers              (Primary) List of authorized workers
 * @param id                   (Worker) This worker's unique identifier
 * @param primaryHost          (Worker) Legacy: single primary hostname/IP (use primaries instead)
 * @param primaryPort          (Worker) Legacy: single primary port (use primaries instead)
 * @param primaries            (Worker) List of primary servers to send updates to (hub clustering)
 * @param key                  (Worker) Shared HMAC secret
 * @param updateIntervalSeconds (Worker) How often to send status updates
 * @param logStatusUpdates     Whether to log status update activity
 */
public record HyQueryNetworkConfig(
    boolean enabled,
    String role,
    int workerTimeoutSeconds,
    List<HyQueryWorkerEntry> workers,
    String id,
    String primaryHost,
    int primaryPort,
    List<HyQueryPrimaryTarget> primaries,
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
            "localhost",        // primaryHost (legacy)
            5520,               // primaryPort (legacy)
            List.of(),          // primaries (hub clustering)
            "change-me-secret", // key
            5,                  // updateIntervalSeconds
            false               // logStatusUpdates (off by default to reduce spam)
        );
    }

    /**
     * Get all primary targets this worker should send updates to.
     * Supports both legacy (single primary) and hub clustering (multiple primaries).
     *
     * If primaries list is configured, uses that.
     * Otherwise falls back to legacy primaryHost/primaryPort.
     */
    public List<HyQueryPrimaryTarget> getPrimaryTargets() {
        // If primaries list is configured and non-empty, use it
        if (primaries != null && !primaries.isEmpty()) {
            return primaries;
        }
        // Fall back to legacy single primary config
        if (primaryHost != null && !primaryHost.isEmpty() && primaryPort > 0) {
            return List.of(new HyQueryPrimaryTarget(primaryHost, primaryPort));
        }
        return List.of();
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
            loaded.primaries() != null ? loaded.primaries() : def.primaries(),
            loaded.key() != null ? loaded.key() : def.key(),
            loaded.updateIntervalSeconds() > 0 ? loaded.updateIntervalSeconds() : def.updateIntervalSeconds(),
            loaded.logStatusUpdates()
        );
    }
}
