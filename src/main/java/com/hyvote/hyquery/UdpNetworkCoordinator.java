package com.hyvote.hyquery;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * UDP coordinator implementation (existing P2P worker-primary behavior).
 */
public class UdpNetworkCoordinator implements HyQueryNetworkCoordinator {

    private final HyQueryPlugin plugin;
    private final HyQueryNetworkConfig config;
    private final HyQueryNetworkObservability observability;

    // Primary mode
    private HyQueryWorkerRegistry registry;

    // Worker mode
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updateTask;
    private DatagramChannel udpChannel;
    private List<InetSocketAddress> primaryAddresses;

    public UdpNetworkCoordinator(
        HyQueryPlugin plugin,
        HyQueryNetworkConfig config,
        HyQueryNetworkObservability observability
    ) {
        this.plugin = plugin;
        this.config = config;
        this.observability = observability;
    }

    @Override
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

    @Override
    public void stop() {
        if (config.isWorker()) {
            stopWorker();
        }
    }

    @Override
    public boolean handlesStatusPackets() {
        return config.isPrimary();
    }

    @Override
    public DatagramPacket processStatusUpdate(DatagramPacket packet) {
        if (registry == null) {
            return null;
        }

        ByteBuf content = packet.content();
        InetSocketAddress sender = packet.sender();

        try {
            HyQueryProtocol.StatusPacket status = HyQueryProtocol.parseStatusPacket(content);
            if (status == null) {
                observability.warn("Rejected invalid status packet from " + sender + " (malformed packet)");
                observability.recordStatusRejected();
                return buildAckPacket(sender, HyQueryProtocol.ACK_BAD_HMAC, 0);
            }

            HyQueryWorkerEntry entry = registry.findWorkerEntry(status.workerId());
            if (entry == null) {
                observability.warn("Rejected status from " + sender + " - unknown worker ID: " + status.workerId());
                observability.recordStatusRejected();
                return buildAckPacket(sender, HyQueryProtocol.ACK_UNKNOWN_ID, status.timestamp());
            }

            if (!HyQueryProtocol.verifyStatusHmac(content, entry.key())) {
                observability.warn("Rejected status from worker '" + status.workerId()
                    + "' - invalid HMAC (check keys match)");
                observability.recordStatusRejected();
                return buildAckPacket(sender, HyQueryProtocol.ACK_BAD_HMAC, status.timestamp());
            }

            long now = System.currentTimeMillis();
            if (Math.abs(now - status.timestamp()) > 30_000) {
                observability.warn("Rejected status from worker '" + status.workerId()
                    + "' - stale timestamp (clock sync issue?)");
                observability.recordStatusRejected();
                return buildAckPacket(sender, HyQueryProtocol.ACK_STALE, status.timestamp());
            }

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
            observability.recordStatusAccepted();

            if (config.logStatusUpdates()) {
                int workerTotal = registry.getTotalOnlinePlayers();
                int primaryPlayers = Universe.get().getPlayerCount();
                int networkTotal = primaryPlayers + workerTotal;
                if (isNewWorker) {
                    observability.info("Worker '" + status.workerId() + "' connected ("
                        + status.onlinePlayers() + " players) - Workers: "
                        + workerTotal + ", Network total: " + networkTotal);
                } else {
                    observability.info("Received update from '" + status.workerId() + "' ("
                        + status.onlinePlayers() + "/" + status.maxPlayers() + " players) - Workers: "
                        + workerTotal + ", Network total: " + networkTotal);
                }
            }

            if (plugin.getCache() != null) {
                plugin.getCache().invalidate();
            }

            return buildAckPacket(sender, HyQueryProtocol.ACK_OK, status.timestamp());

        } catch (Exception e) {
            observability.warn("Error processing status from " + sender + ": " + e.getMessage());
            observability.recordStatusRejected();
            return null;
        }
    }

    @Override
    public HyQueryNetworkAggregate getAggregate(boolean includePlayers) {
        if (!config.isPrimary() || registry == null) {
            return HyQueryNetworkAggregate.empty();
        }

        List<HyQueryNetworkAggregate.RemoteServerSnapshot> remoteServers = new ArrayList<>();
        int timeoutSeconds = registry.getTimeoutSeconds();

        for (HyQueryWorkerState worker : registry.getAllWorkers()) {
            List<HyQueryWorkerState.PlayerInfo> players = includePlayers ? worker.getPlayers() : List.of();
            remoteServers.add(new HyQueryNetworkAggregate.RemoteServerSnapshot(
                worker.getId(),
                worker.getServerName(),
                worker.getMotd(),
                worker.getOnlinePlayers(),
                worker.getMaxPlayers(),
                worker.getPort(),
                worker.getVersion(),
                worker.getStatus(timeoutSeconds),
                worker.getLastUpdateMillis(),
                players
            ));
        }

        List<HyQueryNetworkAggregate.NetworkPlayer> players = includePlayers
            ? registry.getAllPlayers().stream()
                .map(p -> new HyQueryNetworkAggregate.NetworkPlayer(p.username(), p.uuid(), p.serverId()))
                .toList()
            : List.of();

        return new HyQueryNetworkAggregate(
            registry.getTotalOnlinePlayers(),
            registry.getTotalMaxPlayers(),
            remoteServers,
            players
        );
    }

    @Override
    public String getMetricsSummary() {
        return observability.metricsSummary();
    }

    public HyQueryWorkerRegistry getRegistry() {
        return registry;
    }

    private void startPrimary() {
        registry = new HyQueryWorkerRegistry(config);
        observability.info("Network mode: PRIMARY (coordinator=udp)");
        observability.info("  - Worker timeout: " + config.workerTimeoutSeconds() + "s");
        observability.info("  - Authorized workers: " + config.workers().size());
        for (HyQueryWorkerEntry entry : config.workers()) {
            observability.info("    - " + entry.id());
        }
    }

    private DatagramPacket buildAckPacket(InetSocketAddress target, byte status, long timestamp) {
        ByteBuf buf = HyQueryProtocol.buildAckPacket(status, timestamp, config.workers().isEmpty() ? "" :
            config.workers().get(0).key());
        return new DatagramPacket(buf, target);
    }

    private void startWorker() {
        List<HyQueryPrimaryTarget> targets = config.getPrimaryTargets();
        if (targets.isEmpty()) {
            observability.warn("Network mode: WORKER (coordinator=udp) - no primary servers configured");
            return;
        }

        observability.info("Network mode: WORKER (coordinator=udp)");
        observability.info("  - Worker ID: " + config.id());
        observability.info("  - Update interval: " + config.updateIntervalSeconds() + "s");

        primaryAddresses = new ArrayList<>();
        if (targets.size() == 1) {
            observability.info("  - Primary: " + targets.get(0));
        } else {
            observability.info("  - Hub clustering: sending to " + targets.size() + " primaries");
        }

        for (HyQueryPrimaryTarget target : targets) {
            primaryAddresses.add(new InetSocketAddress(target.host(), target.port()));
            if (targets.size() > 1) {
                observability.info("    - " + target);
            }
        }

        try {
            udpChannel = DatagramChannel.open();
            udpChannel.configureBlocking(false);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HyQuery-UDP-Worker");
                t.setDaemon(true);
                return t;
            });

            updateTask = scheduler.scheduleAtFixedRate(
                this::sendStatusUpdate,
                config.updateIntervalSeconds(),
                config.updateIntervalSeconds(),
                TimeUnit.SECONDS
            );

            observability.info("UDP worker status updates started");

        } catch (Exception e) {
            observability.warn("Failed to start UDP worker mode: " + e.getMessage());
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
            } catch (Exception ignored) {
            }
            udpChannel = null;
        }
        primaryAddresses = null;
    }

    private void sendStatusUpdate() {
        if (primaryAddresses == null || primaryAddresses.isEmpty()) {
            return;
        }

        long startMillis = System.currentTimeMillis();
        observability.recordPublishAttempt();

        try {
            Universe universe = Universe.get();
            int playerCount = universe.getPlayerCount();
            int maxPlayers = plugin.getMaxPlayers();

            ByteBuf buf = buildStatusPacket();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();

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
                        observability.warn("Failed to send status update to " + primaryAddress + " - no bytes sent");
                    }
                } catch (Exception e) {
                    failCount++;
                    observability.warn("Failed to send status update to " + primaryAddress + " - " + e.getMessage());
                }
            }

            if (successCount > 0) {
                observability.recordPublishSuccess(System.currentTimeMillis() - startMillis);
            }
            if (failCount > 0) {
                observability.recordPublishFailure();
            }

            if (config.logStatusUpdates()) {
                if (primaryAddresses.size() == 1 && successCount > 0) {
                    observability.info("Sent status update to " + primaryAddresses.get(0)
                        + " (" + playerCount + "/" + maxPlayers + " players)");
                } else {
                    observability.info("Sent status update to " + successCount + "/" + primaryAddresses.size()
                        + " primaries (" + playerCount + "/" + maxPlayers + " players)");
                }
            }

        } catch (Exception e) {
            observability.recordPublishFailure();
            observability.warn("Failed to build UDP status update: " + e.getMessage());
        }
    }

    private ByteBuf buildStatusPacket() {
        Universe universe = Universe.get();

        List<HyQueryWorkerState.PlayerInfo> players = new ArrayList<>();
        for (PlayerRef player : universe.getPlayers()) {
            players.add(new HyQueryWorkerState.PlayerInfo(player.getUsername(), player.getUuid()));
        }

        HyQueryConfig queryConfig = plugin.getQueryConfig();
        String motd = queryConfig.useCustomMotd() ? queryConfig.customMotd() : plugin.getMotd();

        return HyQueryProtocol.buildStatusPacket(
            config.id(),
            plugin.getServerName(),
            motd,
            universe.getPlayerCount(),
            plugin.getMaxPlayers(),
            plugin.getGamePort(),
            plugin.getPluginVersion(),
            players,
            config.key()
        );
    }
}
