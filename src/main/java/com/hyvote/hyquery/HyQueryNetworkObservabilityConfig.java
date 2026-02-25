package com.hyvote.hyquery;

/**
 * Network coordinator observability controls.
 */
public record HyQueryNetworkObservabilityConfig(
    String logLevel,
    Boolean metricsEnabled,
    String metricsDetail
) {
    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_DEBUG = "debug";

    public static final String DETAIL_BASIC = "basic";
    public static final String DETAIL_DETAILED = "detailed";

    public static HyQueryNetworkObservabilityConfig defaults() {
        return new HyQueryNetworkObservabilityConfig(
            LEVEL_INFO,
            true,
            DETAIL_BASIC
        );
    }

    public static HyQueryNetworkObservabilityConfig withDefaults(HyQueryNetworkObservabilityConfig loaded) {
        if (loaded == null) {
            return defaults();
        }

        HyQueryNetworkObservabilityConfig def = defaults();
        return new HyQueryNetworkObservabilityConfig(
            normalizeLogLevel(loaded.logLevel(), def.logLevel()),
            loaded.metricsEnabled() != null ? loaded.metricsEnabled() : def.metricsEnabled(),
            normalizeMetricsDetail(loaded.metricsDetail(), def.metricsDetail())
        );
    }

    public boolean metricsEnabledOrDefault() {
        return metricsEnabled != null ? metricsEnabled : defaults().metricsEnabled();
    }

    private static String normalizeLogLevel(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String normalized = raw.toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case LEVEL_ERROR, LEVEL_WARN, LEVEL_INFO, LEVEL_DEBUG -> normalized;
            default -> fallback;
        };
    }

    private static String normalizeMetricsDetail(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String normalized = raw.toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case DETAIL_BASIC, DETAIL_DETAILED -> normalized;
            default -> fallback;
        };
    }
}
