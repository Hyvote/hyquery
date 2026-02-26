package com.hyvote.hyquery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyQueryNetworkConfigTest {

    @Test
    void defaultsUseUdpAndRedisHardFail() {
        HyQueryNetworkConfig defaults = HyQueryNetworkConfig.defaults();

        assertEquals(HyQueryNetworkConfig.COORDINATOR_UDP, defaults.coordinator());
        assertEquals(10, defaults.staleAfterSeconds());
        assertEquals("global", defaults.namespace());
        assertFalse(defaults.includeGlobalNamespace());
        assertTrue(defaults.redis().requireAvailableOrDefault());
        assertTrue(defaults.observability().metricsEnabledOrDefault());
    }

    @Test
    void readNamespacesIncludeGlobalOnlyWhenRequested() {
        HyQueryNetworkConfig specificNs = new HyQueryNetworkConfig(
            true,
            HyQueryNetworkConfig.ROLE_PRIMARY,
            HyQueryNetworkConfig.COORDINATOR_REDIS,
            "prod-us",
            false,
            10,
            30,
            List.of(),
            "server-1",
            "localhost",
            5520,
            List.of(),
            "secret",
            5,
            false,
            HyQueryRedisConfig.defaults(),
            HyQueryNetworkObservabilityConfig.defaults()
        );

        HyQueryNetworkConfig includeGlobal = new HyQueryNetworkConfig(
            true,
            HyQueryNetworkConfig.ROLE_PRIMARY,
            HyQueryNetworkConfig.COORDINATOR_REDIS,
            "prod-us",
            true,
            10,
            30,
            List.of(),
            "server-1",
            "localhost",
            5520,
            List.of(),
            "secret",
            5,
            false,
            HyQueryRedisConfig.defaults(),
            HyQueryNetworkObservabilityConfig.defaults()
        );

        assertEquals(List.of("prod-us"), specificNs.getReadNamespaces());
        assertEquals(List.of("prod-us", "global"), includeGlobal.getReadNamespaces());
    }

    @Test
    void withDefaultsPreservesUdpLegacyFieldsAndFillsCoordinatorFields() {
        HyQueryNetworkConfig legacy = new HyQueryNetworkConfig(
            true,
            HyQueryNetworkConfig.ROLE_WORKER,
            null,
            null,
            false,
            0,
            45,
            List.of(),
            "game-1",
            "hub.example.com",
            5521,
            List.of(),
            "legacy-key",
            7,
            true,
            null,
            null
        );

        HyQueryNetworkConfig resolved = HyQueryNetworkConfig.withDefaults(legacy);

        assertTrue(resolved.enabled());
        assertTrue(resolved.isWorker());
        assertTrue(resolved.isUdpCoordinator());
        assertEquals("game-1", resolved.id());
        assertEquals("hub.example.com", resolved.primaryHost());
        assertEquals(5521, resolved.primaryPort());
        assertEquals(7, resolved.updateIntervalSeconds());
        assertEquals("global", resolved.namespace());
        assertEquals(10, resolved.staleAfterSeconds());
    }

    @Test
    void invalidCoordinatorFallsBackToUdp() {
        HyQueryNetworkConfig loaded = new HyQueryNetworkConfig(
            true,
            HyQueryNetworkConfig.ROLE_PRIMARY,
            "invalid-mode",
            "ns",
            false,
            10,
            30,
            List.of(),
            "server-1",
            "localhost",
            5520,
            List.of(),
            "secret",
            5,
            false,
            HyQueryRedisConfig.defaults(),
            HyQueryNetworkObservabilityConfig.defaults()
        );

        HyQueryNetworkConfig resolved = HyQueryNetworkConfig.withDefaults(loaded);
        assertTrue(resolved.isUdpCoordinator());
    }

    @Test
    void redisSecurityConfigIsPreserved() {
        HyQueryRedisConfig redis = new HyQueryRedisConfig(
            "redis.internal",
            6380,
            "hyquery-user",
            "hyquery-pass",
            2,
            true,
            1500,
            2000,
            6,
            true
        );

        HyQueryNetworkConfig config = new HyQueryNetworkConfig(
            true,
            HyQueryNetworkConfig.ROLE_WORKER,
            HyQueryNetworkConfig.COORDINATOR_REDIS,
            "prod",
            false,
            10,
            30,
            List.of(),
            "game-2",
            "localhost",
            5520,
            List.of(),
            "secret",
            5,
            false,
            redis,
            HyQueryNetworkObservabilityConfig.defaults()
        );

        HyQueryNetworkConfig resolved = HyQueryNetworkConfig.withDefaults(config);
        assertTrue(resolved.redis().tls());
        assertEquals("hyquery-user", resolved.redis().username());
        assertEquals("hyquery-pass", resolved.redis().password());
    }

    @Test
    void withDefaultsGeneratesRandomRedisWorkerIdWhenMissing() {
        HyQueryNetworkConfig loaded = new HyQueryNetworkConfig(
            true,
            HyQueryNetworkConfig.ROLE_WORKER,
            HyQueryNetworkConfig.COORDINATOR_REDIS,
            "prod",
            false,
            10,
            30,
            List.of(),
            null,
            "localhost",
            5520,
            List.of(),
            "secret",
            5,
            false,
            HyQueryRedisConfig.defaults(),
            HyQueryNetworkObservabilityConfig.defaults()
        );

        HyQueryNetworkConfig resolved = HyQueryNetworkConfig.withDefaults(loaded);
        assertNotEquals("server-1", resolved.id());
        assertTrue(resolved.id().matches("server-[A-Z0-9]{8}"));
    }
}
