package com.hyvote.hyquery;

/**
 * Redis connection settings for Redis coordinator mode.
 */
public record HyQueryRedisConfig(
    String host,
    int port,
    String username,
    String password,
    int database,
    boolean tls,
    int connectTimeoutMillis,
    int readTimeoutMillis,
    int publishIntervalSeconds,
    Boolean requireAvailable
) {
    public static HyQueryRedisConfig defaults() {
        return new HyQueryRedisConfig(
            "localhost",
            6379,
            "",
            "",
            0,
            false,
            1000,
            1000,
            5,
            true
        );
    }

    public static HyQueryRedisConfig withDefaults(HyQueryRedisConfig loaded) {
        if (loaded == null) {
            return defaults();
        }

        HyQueryRedisConfig def = defaults();
        return new HyQueryRedisConfig(
            loaded.host() != null && !loaded.host().isBlank() ? loaded.host() : def.host(),
            loaded.port() > 0 ? loaded.port() : def.port(),
            loaded.username() != null ? loaded.username() : def.username(),
            loaded.password() != null ? loaded.password() : def.password(),
            loaded.database() >= 0 ? loaded.database() : def.database(),
            loaded.tls(),
            loaded.connectTimeoutMillis() > 0 ? loaded.connectTimeoutMillis() : def.connectTimeoutMillis(),
            loaded.readTimeoutMillis() > 0 ? loaded.readTimeoutMillis() : def.readTimeoutMillis(),
            loaded.publishIntervalSeconds() > 0 ? loaded.publishIntervalSeconds() : def.publishIntervalSeconds(),
            loaded.requireAvailable() != null ? loaded.requireAvailable() : def.requireAvailable()
        );
    }

    public boolean requireAvailableOrDefault() {
        return requireAvailable != null ? requireAvailable : defaults().requireAvailable();
    }
}
