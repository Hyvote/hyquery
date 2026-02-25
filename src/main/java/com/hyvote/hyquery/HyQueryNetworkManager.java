package com.hyvote.hyquery;

import io.netty.channel.socket.DatagramPacket;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Coordinates network mode lifecycle and delegates to the selected coordinator.
 */
public class HyQueryNetworkManager {

    private final HyQueryPlugin plugin;
    private final HyQueryNetworkConfig config;
    private final HyQueryNetworkObservability observability;

    private HyQueryNetworkCoordinator coordinator;
    private ScheduledExecutorService metricsScheduler;
    private ScheduledFuture<?> metricsTask;

    public HyQueryNetworkManager(HyQueryPlugin plugin, HyQueryNetworkConfig config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.observability = new HyQueryNetworkObservability(logger, config.observability());
    }

    /**
     * Start the network manager.
     */
    public void start() {
        if (!config.enabled()) {
            return;
        }

        coordinator = createCoordinator();
        coordinator.start();
        startMetricsReporter();
    }

    /**
     * Stop the network manager.
     */
    public void stop() {
        stopMetricsReporter();

        if (coordinator != null) {
            coordinator.stop();
            if (config.observability().metricsEnabledOrDefault()) {
                observability.info("Network metrics summary: " + coordinator.getMetricsSummary());
            }
            coordinator = null;
        }
    }

    /**
     * Process an incoming UDP status update packet.
     */
    public DatagramPacket processStatusUpdate(DatagramPacket packet) {
        if (coordinator == null) {
            return null;
        }
        return coordinator.processStatusUpdate(packet);
    }

    /**
     * Whether the current coordinator accepts UDP status packets.
     */
    public boolean handlesStatusPackets() {
        return coordinator != null && coordinator.handlesStatusPackets();
    }

    /**
     * Get aggregated remote network data for query responses.
     */
    public HyQueryNetworkAggregate getAggregate(boolean includePlayers) {
        if (coordinator == null || !config.isPrimary()) {
            return HyQueryNetworkAggregate.empty();
        }
        return coordinator.getAggregate(includePlayers);
    }

    /**
     * Backwards-compatible access to UDP worker registry (UDP primary only).
     */
    public HyQueryWorkerRegistry getRegistry() {
        if (coordinator instanceof UdpNetworkCoordinator udpCoordinator) {
            return udpCoordinator.getRegistry();
        }
        return null;
    }

    /**
     * Check if running in primary mode.
     */
    public boolean isPrimary() {
        return config.isPrimary();
    }

    /**
     * Check if running in worker mode.
     */
    public boolean isWorker() {
        return config.isWorker();
    }

    private HyQueryNetworkCoordinator createCoordinator() {
        if (config.isRedisCoordinator()) {
            return new RedisNetworkCoordinator(plugin, config, observability);
        }
        return new UdpNetworkCoordinator(plugin, config, observability);
    }

    private void startMetricsReporter() {
        if (!config.observability().metricsEnabledOrDefault()) {
            return;
        }

        metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HyQuery-Network-Metrics");
            t.setDaemon(true);
            return t;
        });

        metricsTask = metricsScheduler.scheduleAtFixedRate(() -> {
            if (coordinator != null) {
                observability.info("Network metrics: " + coordinator.getMetricsSummary());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void stopMetricsReporter() {
        if (metricsTask != null) {
            metricsTask.cancel(false);
            metricsTask = null;
        }

        if (metricsScheduler != null) {
            metricsScheduler.shutdown();
            metricsScheduler = null;
        }
    }
}
