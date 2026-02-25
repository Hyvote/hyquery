package com.hyvote.hyquery;

import java.util.List;
import java.util.UUID;

/**
 * Cached state of a worker server, stored on the primary.
 *
 * Thread-safe: all fields are final and set on construction.
 * A new instance is created each time the worker sends an update.
 */
public final class HyQueryWorkerState {

    public static final byte STATUS_OFFLINE = 0x00;
    public static final byte STATUS_ONLINE = 0x01;

    private final String id;
    private final String serverName;
    private final String motd;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final int port;
    private final String version;
    private final List<PlayerInfo> players;
    private final long lastUpdateNanos;
    private final long lastUpdateMillis;

    /**
     * Create a new worker state from received status data.
     */
    public HyQueryWorkerState(
        String id,
        String serverName,
        String motd,
        int onlinePlayers,
        int maxPlayers,
        int port,
        String version,
        List<PlayerInfo> players
    ) {
        this.id = id;
        this.serverName = serverName;
        this.motd = motd;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.port = port;
        this.version = version;
        this.players = players != null ? List.copyOf(players) : List.of();
        this.lastUpdateNanos = System.nanoTime();
        this.lastUpdateMillis = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getServerName() {
        return serverName;
    }

    public String getMotd() {
        return motd;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getPort() {
        return port;
    }

    public String getVersion() {
        return version;
    }

    public List<PlayerInfo> getPlayers() {
        return players;
    }

    public long getLastUpdateNanos() {
        return lastUpdateNanos;
    }

    public long getLastUpdateMillis() {
        return lastUpdateMillis;
    }

    /**
     * Check if this worker state is stale (no update within timeout).
     *
     * @param timeoutSeconds Timeout in seconds
     * @return true if stale
     */
    public boolean isStale(int timeoutSeconds) {
        long elapsedNanos = System.nanoTime() - lastUpdateNanos;
        return elapsedNanos > timeoutSeconds * 1_000_000_000L;
    }

    /**
     * Get the status code for this worker.
     *
     * @param timeoutSeconds Timeout to consider worker offline
     * @return STATUS_ONLINE or STATUS_OFFLINE
     */
    public byte getStatus(int timeoutSeconds) {
        return isStale(timeoutSeconds) ? STATUS_OFFLINE : STATUS_ONLINE;
    }

    /**
     * Player information stored in worker state.
     */
    public record PlayerInfo(String username, UUID uuid) {}
}
