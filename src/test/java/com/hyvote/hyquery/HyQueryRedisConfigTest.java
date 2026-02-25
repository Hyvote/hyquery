package com.hyvote.hyquery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyQueryRedisConfigTest {

    @Test
    void withDefaultsBackfillsMissingRequireAvailableToTrue() {
        HyQueryRedisConfig loaded = new HyQueryRedisConfig(
            "redis.local",
            6379,
            "",
            "",
            0,
            false,
            500,
            700,
            4,
            null
        );

        HyQueryRedisConfig resolved = HyQueryRedisConfig.withDefaults(loaded);

        assertTrue(resolved.requireAvailableOrDefault());
        assertEquals(500, resolved.connectTimeoutMillis());
        assertEquals(700, resolved.readTimeoutMillis());
        assertEquals(4, resolved.publishIntervalSeconds());
    }
}
