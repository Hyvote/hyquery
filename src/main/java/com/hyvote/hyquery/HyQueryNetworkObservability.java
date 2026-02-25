package com.hyvote.hyquery;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared network observability helper with bounded log level filtering and counters.
 */
public final class HyQueryNetworkObservability {

    public enum LogLevel {
        ERROR,
        WARN,
        INFO,
        DEBUG;

        static LogLevel fromConfig(String raw) {
            if (raw == null || raw.isBlank()) {
                return INFO;
            }
            return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case HyQueryNetworkObservabilityConfig.LEVEL_ERROR -> ERROR;
                case HyQueryNetworkObservabilityConfig.LEVEL_WARN -> WARN;
                case HyQueryNetworkObservabilityConfig.LEVEL_DEBUG -> DEBUG;
                default -> INFO;
            };
        }

        boolean allows(LogLevel desired) {
            return desired.ordinal() <= ordinal();
        }
    }

    public enum MetricsDetail {
        BASIC,
        DETAILED;

        static MetricsDetail fromConfig(String raw) {
            if (HyQueryNetworkObservabilityConfig.DETAIL_DETAILED.equalsIgnoreCase(raw)) {
                return DETAILED;
            }
            return BASIC;
        }
    }

    private final Logger logger;
    private final LogLevel level;
    private final boolean metricsEnabled;
    private final MetricsDetail metricsDetail;

    private final AtomicLong publishAttempts = new AtomicLong();
    private final AtomicLong publishSuccess = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();

    private final AtomicLong readAttempts = new AtomicLong();
    private final AtomicLong readSuccess = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong staleEvictions = new AtomicLong();
    private final AtomicLong snapshotsRead = new AtomicLong();

    private final AtomicLong totalReadLatencyMillis = new AtomicLong();
    private final AtomicLong totalPublishLatencyMillis = new AtomicLong();

    private final AtomicLong statusAccepted = new AtomicLong();
    private final AtomicLong statusRejected = new AtomicLong();

    public HyQueryNetworkObservability(
        Logger logger,
        HyQueryNetworkObservabilityConfig config
    ) {
        this.logger = logger;
        this.level = LogLevel.fromConfig(config.logLevel());
        this.metricsEnabled = config.metricsEnabledOrDefault();
        this.metricsDetail = MetricsDetail.fromConfig(config.metricsDetail());
    }

    public boolean metricsEnabled() {
        return metricsEnabled;
    }

    public void debug(String msg) {
        log(LogLevel.DEBUG, Level.FINE, msg);
    }

    public void info(String msg) {
        log(LogLevel.INFO, Level.INFO, msg);
    }

    public void warn(String msg) {
        log(LogLevel.WARN, Level.WARNING, msg);
    }

    public void error(String msg) {
        log(LogLevel.ERROR, Level.SEVERE, msg);
    }

    public void recordPublishAttempt() {
        increment(publishAttempts);
    }

    public void recordPublishSuccess(long latencyMillis) {
        increment(publishSuccess);
        add(totalPublishLatencyMillis, latencyMillis);
    }

    public void recordPublishFailure() {
        increment(publishFailures);
    }

    public void recordReadAttempt() {
        increment(readAttempts);
    }

    public void recordReadSuccess(int snapshotCount, long latencyMillis) {
        increment(readSuccess);
        add(snapshotsRead, snapshotCount);
        add(totalReadLatencyMillis, latencyMillis);
    }

    public void recordReadFailure() {
        increment(readFailures);
    }

    public void recordCacheHit() {
        increment(cacheHits);
    }

    public void recordCacheMiss() {
        increment(cacheMisses);
    }

    public void recordStaleEvictions(long count) {
        add(staleEvictions, count);
    }

    public void recordStatusAccepted() {
        increment(statusAccepted);
    }

    public void recordStatusRejected() {
        increment(statusRejected);
    }

    public String metricsSummary() {
        if (!metricsEnabled) {
            return "metrics=disabled";
        }

        String base = "publishes=" + publishSuccess.get() + "/" + publishAttempts.get()
            + " publishFailures=" + publishFailures.get()
            + " reads=" + readSuccess.get() + "/" + readAttempts.get()
            + " readFailures=" + readFailures.get()
            + " cacheHits=" + cacheHits.get()
            + " cacheMisses=" + cacheMisses.get()
            + " staleEvictions=" + staleEvictions.get()
            + " snapshotsRead=" + snapshotsRead.get()
            + " statusAccepted=" + statusAccepted.get()
            + " statusRejected=" + statusRejected.get();

        if (metricsDetail == MetricsDetail.DETAILED) {
            long readSuccessCount = Math.max(1, readSuccess.get());
            long publishSuccessCount = Math.max(1, publishSuccess.get());
            base += " avgReadLatencyMs=" + (totalReadLatencyMillis.get() / readSuccessCount)
                + " avgPublishLatencyMs=" + (totalPublishLatencyMillis.get() / publishSuccessCount);
        }

        return base;
    }

    private void log(LogLevel desiredLevel, Level javaLevel, String msg) {
        if (level.allows(desiredLevel)) {
            logger.log(javaLevel, msg);
        }
    }

    private void increment(AtomicLong counter) {
        if (metricsEnabled) {
            counter.incrementAndGet();
        }
    }

    private void add(AtomicLong counter, long value) {
        if (metricsEnabled) {
            counter.addAndGet(Math.max(0L, value));
        }
    }
}
