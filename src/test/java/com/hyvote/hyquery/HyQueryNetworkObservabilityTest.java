package com.hyvote.hyquery;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HyQueryNetworkObservabilityTest {

    @Test
    void metricsSummaryIncludesDetailedLatencyWhenConfigured() {
        HyQueryNetworkObservabilityConfig config = new HyQueryNetworkObservabilityConfig(
            "debug",
            true,
            "detailed"
        );

        HyQueryNetworkObservability observability = new HyQueryNetworkObservability(
            Logger.getLogger("test"),
            config
        );

        observability.recordPublishAttempt();
        observability.recordPublishSuccess(10);
        observability.recordReadAttempt();
        observability.recordReadSuccess(3, 20);
        observability.recordCacheHit();
        observability.recordStaleEvictions(1);

        String summary = observability.metricsSummary();

        assertTrue(summary.contains("publishes=1/1"));
        assertTrue(summary.contains("reads=1/1"));
        assertTrue(summary.contains("avgReadLatencyMs="));
        assertTrue(summary.contains("avgPublishLatencyMs="));
    }

    @Test
    void metricsCanBeDisabled() {
        HyQueryNetworkObservabilityConfig config = new HyQueryNetworkObservabilityConfig(
            "info",
            false,
            "basic"
        );

        HyQueryNetworkObservability observability = new HyQueryNetworkObservability(
            Logger.getLogger("test"),
            config
        );

        observability.recordReadAttempt();
        observability.recordReadSuccess(10, 50);

        assertTrue(observability.metricsSummary().contains("metrics=disabled"));
    }
}
