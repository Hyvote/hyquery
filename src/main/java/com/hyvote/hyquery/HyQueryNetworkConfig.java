package com.hyvote.hyquery;

import java.util.List;

/**
 * Network configuration for HyQuery multi-server mode.
 *
 * Supports two server roles:
 * - primary: Aggregates remote state and serves network-wide responses
 * - worker: Publishes local state to the configured coordinator
 */
public record HyQueryNetworkConfig(
    boolean enabled,
    String role,
    String coordinator,
    String namespace,
    boolean includeGlobalNamespace,
    int staleAfterSeconds,
    int workerTimeoutSeconds,
    List<HyQueryWorkerEntry> workers,
    String id,
    String primaryHost,
    int primaryPort,
    List<HyQueryPrimaryTarget> primaries,
    String key,
    int updateIntervalSeconds,
    boolean logStatusUpdates,
    HyQueryRedisConfig redis,
    HyQueryNetworkObservabilityConfig observability
) {
    public static final String ROLE_PRIMARY = "primary";
    public static final String ROLE_WORKER = "worker";

    public static final String COORDINATOR_UDP = "udp";
    public static final String COORDINATOR_REDIS = "redis";

    public static final String GLOBAL_NAMESPACE = "global";

    /**
     * Returns default network config (disabled, UDP coordinator).
     */
    public static HyQueryNetworkConfig defaults() {
        return new HyQueryNetworkConfig(
            false,                                  // enabled
            ROLE_WORKER,                            // role
            COORDINATOR_UDP,                        // coordinator
            GLOBAL_NAMESPACE,                       // namespace
            false,                                  // includeGlobalNamespace
            10,                                     // staleAfterSeconds
            30,                                     // workerTimeoutSeconds (UDP primary)
            List.of(),                              // workers
            "server-1",                            // id
            "localhost",                           // primaryHost (legacy UDP)
            5520,                                   // primaryPort (legacy UDP)
            List.of(),                              // primaries (UDP clustering)
            "change-me-secret",                    // key (UDP worker HMAC)
            5,                                      // updateIntervalSeconds (UDP worker)
            false,                                  // logStatusUpdates
            HyQueryRedisConfig.defaults(),          // redis
            HyQueryNetworkObservabilityConfig.defaults() // observability
        );
    }

    /**
     * Get all primary targets this worker should send UDP updates to.
     *
     * Supports both legacy single primary and hub clustering list.
     */
    public List<HyQueryPrimaryTarget> getPrimaryTargets() {
        if (primaries != null && !primaries.isEmpty()) {
            return primaries;
        }
        if (primaryHost != null && !primaryHost.isEmpty() && primaryPort > 0) {
            return List.of(new HyQueryPrimaryTarget(primaryHost, primaryPort));
        }
        return List.of();
    }

    /**
     * Read namespaces used by Redis primary read path.
     */
    public List<String> getReadNamespaces() {
        String configured = normalizedNamespace(namespace);
        if (includeGlobalNamespace && !GLOBAL_NAMESPACE.equals(configured)) {
            return List.of(configured, GLOBAL_NAMESPACE);
        }
        return List.of(configured);
    }

    /**
     * Get normalized namespace value.
     */
    public static String normalizedNamespace(String raw) {
        if (raw == null || raw.isBlank()) {
            return GLOBAL_NAMESPACE;
        }
        return raw.trim();
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
     * Check if UDP coordinator mode is enabled.
     */
    public boolean isUdpCoordinator() {
        return COORDINATOR_UDP.equalsIgnoreCase(coordinator);
    }

    /**
     * Check if Redis coordinator mode is enabled.
     */
    public boolean isRedisCoordinator() {
        return COORDINATOR_REDIS.equalsIgnoreCase(coordinator);
    }

    /**
     * Create config with defaults for null or missing fields (backwards compatibility).
     */
    public static HyQueryNetworkConfig withDefaults(HyQueryNetworkConfig loaded) {
        if (loaded == null) {
            return defaults();
        }

        HyQueryNetworkConfig def = defaults();
        String normalizedCoordinator = normalizeCoordinator(loaded.coordinator(), def.coordinator());

        return new HyQueryNetworkConfig(
            loaded.enabled(),
            loaded.role() != null ? loaded.role() : def.role(),
            normalizedCoordinator,
            normalizedNamespace(loaded.namespace() != null ? loaded.namespace() : def.namespace()),
            loaded.includeGlobalNamespace(),
            loaded.staleAfterSeconds() > 0 ? loaded.staleAfterSeconds() : def.staleAfterSeconds(),
            loaded.workerTimeoutSeconds() > 0 ? loaded.workerTimeoutSeconds() : def.workerTimeoutSeconds(),
            loaded.workers() != null ? loaded.workers() : def.workers(),
            loaded.id() != null ? loaded.id() : def.id(),
            loaded.primaryHost() != null ? loaded.primaryHost() : def.primaryHost(),
            loaded.primaryPort() > 0 ? loaded.primaryPort() : def.primaryPort(),
            loaded.primaries() != null ? loaded.primaries() : def.primaries(),
            loaded.key() != null ? loaded.key() : def.key(),
            loaded.updateIntervalSeconds() > 0 ? loaded.updateIntervalSeconds() : def.updateIntervalSeconds(),
            loaded.logStatusUpdates(),
            HyQueryRedisConfig.withDefaults(loaded.redis()),
            HyQueryNetworkObservabilityConfig.withDefaults(loaded.observability())
        );
    }

    private static String normalizeCoordinator(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String normalized = raw.toLowerCase(java.util.Locale.ROOT);
        if (COORDINATOR_UDP.equals(normalized) || COORDINATOR_REDIS.equals(normalized)) {
            return normalized;
        }
        return fallback;
    }
}
