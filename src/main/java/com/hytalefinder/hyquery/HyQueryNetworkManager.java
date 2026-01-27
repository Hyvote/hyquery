package com.hytalefinder.hyquery;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages network mode functionality for HyQuery.
 *
 * For primary servers: maintains worker registry, processes status updates.
 * For worker servers: sends periodic status updates to primary server(s).
 *
 * Hub clustering: Workers can send updates to multiple primary servers,
 * allowing any hub to answer queries with full network status.
 */
public class HyQueryNetworkManager {

    private final HyQueryPlugin plugin;
    private final HyQueryNetworkConfig config;
    private final Logger logger;

    // Primary mode
    private HyQueryWorkerRegistry registry;

    // Worker mode
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updateTask;
    private DatagramChannel udpChannel;
    private List<InetSocketAddress> primaryAddresses;

    public HyQueryNetworkManager(HyQueryPlugin plugin, HyQueryNetworkConfig config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Start the network manager.
     */
    public void start() {
        if (!config.enabled()) {
            return;
        }

        if (config.isPrimary()) {
            startPrimary();
        } else if (config.isWorker()) {
            startWorker();
        }
    }

    /**
     * Stop the network manager.
     */
    public void stop() {
        if (config.isWorker()) {
            stopWorker();
        }
    }

    /**
     * Get the worker registry (primary mode only).
     */
    public HyQueryWorkerRegistry getRegistry() {
        return registry;
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

    // ========== Primary Mode ==========

    private void startPrimary() {
        registry = new HyQueryWorkerRegistry(config);
        logger.log(Level.INFO, "Network mode: PRIMARY");
        logger.log(Level.INFO, "  - Worker timeout: " + config.workerTimeoutSeconds() + "s");
        logger.log(Level.INFO, "  - Authorized workers: " + config.workers().size());
        for (HyQueryWorkerEntry entry : config.workers()) {
            logger.log(Level.INFO, "    - " + entry.id());
        }
    }

    /**
     * Process a status update from a worker.
     *
     * @param packet The received datagram packet
     * @return Response packet to send back, or null on error
     */
    public DatagramPacket processStatusUpdate(DatagramPacket packet) {
        if (registry == null) {
            return null;
        }

        ByteBuf content = packet.content();
        InetSocketAddress sender = packet.sender();

        try {
            // Parse the status packet
            HyQueryProtocol.StatusPacket status = HyQueryProtocol.parseStatusPacket(content);
            if (status == null) {
                logger.log(Level.WARNING, "Rejected invalid status packet from " + sender + " (malformed packet)");
                return buildAckPacket(sender, HyQueryProtocol.ACK_BAD_HMAC, 0);
            }

            // Find the worker entry
            HyQueryWorkerEntry entry = registry.findWorkerEntry(status.workerId());
            if (entry == null) {
                logger.log(Level.WARNING, "Rejected status from " + sender + " - unknown worker ID: " + status.workerId());
                return buildAckPacket(sender, HyQueryProtocol.ACK_UNKNOWN_ID, status.timestamp());
            }

            // Verify HMAC
            if (!HyQueryProtocol.verifyStatusHmac(content, entry.key())) {
                logger.log(Level.WARNING, "Rejected status from worker '" + status.workerId() + "' - invalid HMAC (check keys match)");
                return buildAckPacket(sender, HyQueryProtocol.ACK_BAD_HMAC, status.timestamp());
            }

            // Check timestamp freshness (30 second window)
            long now = System.currentTimeMillis();
            if (Math.abs(now - status.timestamp()) > 30_000) {
                logger.log(Level.WARNING, "Rejected status from worker '" + status.workerId() + "' - stale timestamp (clock sync issue?)");
                return buildAckPacket(sender, HyQueryProtocol.ACK_STALE, status.timestamp());
            }

            // Update registry
            HyQueryWorkerState state = new HyQueryWorkerState(
                status.workerId(),
                status.serverName(),
                status.motd(),
                status.onlinePlayers(),
                status.maxPlayers(),
                status.port(),
                status.version(),
                status.players()
            );

            boolean isNewWorker = registry.getWorker(status.workerId()) == null;
            registry.updateWorker(state);

            // Log the update (if enabled)
            if (config.logStatusUpdates()) {
                int workerTotal = registry.getTotalOnlinePlayers();
                int primaryPlayers = Universe.get().getPlayerCount();
                int networkTotal = primaryPlayers + workerTotal;
                if (isNewWorker) {
                    logger.log(Level.INFO, "Worker '" + status.workerId() + "' connected (" +
                        status.onlinePlayers() + " players) - Workers: " + workerTotal + ", Network total: " + networkTotal);
                } else {
                    logger.log(Level.INFO, "Received update from '" + status.workerId() + "' (" +
                        status.onlinePlayers() + "/" + status.maxPlayers() + " players) - Workers: " + workerTotal + ", Network total: " + networkTotal);
                }
            }

            // Invalidate cache since network state changed
            if (plugin.getCache() != null) {
                plugin.getCache().invalidate();
            }

            return buildAckPacket(sender, HyQueryProtocol.ACK_OK, status.timestamp());

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing status from " + sender + ": " + e.getMessage());
            return null;
        }
    }

    private DatagramPacket buildAckPacket(InetSocketAddress target, byte status, long timestamp) {
        ByteBuf buf = HyQueryProtocol.buildAckPacket(status, timestamp, config.workers().isEmpty() ? "" :
            config.workers().get(0).key());
        return new DatagramPacket(buf, target);
    }

    // ========== Worker Mode ==========

    private void startWorker() {
        List<HyQueryPrimaryTarget> targets = config.getPrimaryTargets();
        if (targets.isEmpty()) {
            logger.log(Level.WARNING, "Network mode: WORKER - No primary servers configured!");
            return;
        }

        logger.log(Level.INFO, "Network mode: WORKER");
        logger.log(Level.INFO, "  - Worker ID: " + config.id());
        logger.log(Level.INFO, "  - Update interval: " + config.updateIntervalSeconds() + "s");

        // Build list of primary addresses
        primaryAddresses = new ArrayList<>();
        if (targets.size() == 1) {
            logger.log(Level.INFO, "  - Primary: " + targets.get(0));
        } else {
            logger.log(Level.INFO, "  - Hub clustering: sending to " + targets.size() + " primaries");
        }
        for (HyQueryPrimaryTarget target : targets) {
            primaryAddresses.add(new InetSocketAddress(target.host(), target.port()));
            if (targets.size() > 1) {
                logger.log(Level.INFO, "    - " + target);
            }
        }

        try {
            udpChannel = DatagramChannel.open();
            udpChannel.configureBlocking(false);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HyQuery-Worker");
                t.setDaemon(true);
                return t;
            });

            updateTask = scheduler.scheduleAtFixedRate(
                this::sendStatusUpdate,
                config.updateIntervalSeconds(),
                config.updateIntervalSeconds(),
                TimeUnit.SECONDS
            );

            logger.log(Level.INFO, "Worker status updates started");

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to start worker mode: " + e.getMessage());
        }
    }

    private void stopWorker() {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        if (udpChannel != null) {
            try {
                udpChannel.close();
            } catch (Exception ignored) {}
            udpChannel = null;
        }
        primaryAddresses = null;
    }

    private void sendStatusUpdate() {
        if (primaryAddresses == null || primaryAddresses.isEmpty()) {
            return;
        }

        try {
            Universe universe = Universe.get();
            int playerCount = universe.getPlayerCount();
            int maxPlayers = plugin.getMaxPlayers();

            // Build status packet once
            ByteBuf buf = buildStatusPacket();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();

            // Send to all primary servers
            int successCount = 0;
            int failCount = 0;

            for (InetSocketAddress primaryAddress : primaryAddresses) {
                try {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(data);
                    int bytesSent = udpChannel.send(byteBuffer, primaryAddress);

                    if (bytesSent > 0) {
                        successCount++;
                    } else {
                        failCount++;
                        logger.log(Level.WARNING, "Failed to send status update to " + primaryAddress + " - no bytes sent");
                    }
                } catch (Exception e) {
                    failCount++;
                    logger.log(Level.WARNING, "Failed to send status update to " + primaryAddress + " - " + e.getMessage());
                }
            }

            // Log summary if logging is enabled
            if (config.logStatusUpdates()) {
                if (primaryAddresses.size() == 1) {
                    if (successCount > 0) {
                        logger.log(Level.INFO, "Sent status update to " + primaryAddresses.get(0) +
                            " (" + playerCount + "/" + maxPlayers + " players)");
                    }
                } else {
                    logger.log(Level.INFO, "Sent status update to " + successCount + "/" + primaryAddresses.size() +
                        " primaries (" + playerCount + "/" + maxPlayers + " players)");
                }
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build status update: " + e.getMessage());
        }
    }

    private ByteBuf buildStatusPacket() {
        Universe universe = Universe.get();
        HyQueryConfig queryConfig = plugin.getQueryConfig();

        String serverName = plugin.getServerName();
        String motd = queryConfig.useCustomMotd() ? queryConfig.customMotd() : plugin.getMotd();
        int onlinePlayers = universe.getPlayerCount();
        int maxPlayers = plugin.getMaxPlayers();
        int port = plugin.getGamePort();
        String version = getServerVersion();

        // Get player list
        List<HyQueryWorkerState.PlayerInfo> players = new ArrayList<>();
        if (queryConfig.showPlayerList()) {
            for (PlayerRef player : universe.getPlayers()) {
                players.add(new HyQueryWorkerState.PlayerInfo(
                    player.getUsername(),
                    player.getUuid()
                ));
            }
        }

        return HyQueryProtocol.buildStatusPacket(
            config.id(),
            serverName,
            motd,
            onlinePlayers,
            maxPlayers,
            port,
            version,
            players,
            config.key()
        );
    }

    private String getServerVersion() {
        try {
            var manifest = com.hypixel.hytale.common.util.java.ManifestUtil.getImplementationVersion();
            if (manifest != null) {
                return manifest;
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }
}
