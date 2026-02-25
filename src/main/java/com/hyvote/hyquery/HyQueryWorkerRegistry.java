package com.hyvote.hyquery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of worker servers on the primary.
 *
 * Thread-safe storage for worker states received via HYSTATUS packets.
 * Provides aggregated statistics for network-wide queries.
 */
public class HyQueryWorkerRegistry {

    private final Map<String, HyQueryWorkerState> workers = new ConcurrentHashMap<>();
    private final HyQueryNetworkConfig config;
    private final int timeoutSeconds;

    public HyQueryWorkerRegistry(HyQueryNetworkConfig config) {
        this.config = config;
        this.timeoutSeconds = config.workerTimeoutSeconds();
    }

    /**
     * Find the authorized worker entry for a given worker ID.
     *
     * @param workerId The worker ID to look up
     * @return The matching worker entry, or null if not authorized
     */
    public HyQueryWorkerEntry findWorkerEntry(String workerId) {
        if (config.workers() == null) {
            return null;
        }
        for (HyQueryWorkerEntry entry : config.workers()) {
            if (entry.matches(workerId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Update the state for a worker.
     *
     * @param state The new worker state
     */
    public void updateWorker(HyQueryWorkerState state) {
        workers.put(state.getId(), state);
    }

    /**
     * Get the state for a specific worker.
     *
     * @param workerId The worker ID
     * @return The worker state, or null if not found
     */
    public HyQueryWorkerState getWorker(String workerId) {
        return workers.get(workerId);
    }

    /**
     * Get all worker states.
     *
     * @return Collection of all worker states
     */
    public Collection<HyQueryWorkerState> getAllWorkers() {
        return workers.values();
    }

    /**
     * Get all online worker states (not stale).
     *
     * @return List of online worker states
     */
    public List<HyQueryWorkerState> getOnlineWorkers() {
        List<HyQueryWorkerState> online = new ArrayList<>();
        for (HyQueryWorkerState state : workers.values()) {
            if (!state.isStale(timeoutSeconds)) {
                online.add(state);
            }
        }
        return online;
    }

    /**
     * Get total online players across all online workers.
     *
     * @return Total player count
     */
    public int getTotalOnlinePlayers() {
        int total = 0;
        for (HyQueryWorkerState state : workers.values()) {
            if (!state.isStale(timeoutSeconds)) {
                total += state.getOnlinePlayers();
            }
        }
        return total;
    }

    /**
     * Get total max players across all online workers.
     *
     * @return Total max players
     */
    public int getTotalMaxPlayers() {
        int total = 0;
        for (HyQueryWorkerState state : workers.values()) {
            if (!state.isStale(timeoutSeconds)) {
                total += state.getMaxPlayers();
            }
        }
        return total;
    }

    /**
     * Get all players from all online workers.
     *
     * @return List of all players with their source server ID
     */
    public List<NetworkPlayer> getAllPlayers() {
        List<NetworkPlayer> allPlayers = new ArrayList<>();
        for (HyQueryWorkerState state : workers.values()) {
            if (!state.isStale(timeoutSeconds)) {
                for (HyQueryWorkerState.PlayerInfo player : state.getPlayers()) {
                    allPlayers.add(new NetworkPlayer(
                        player.username(),
                        player.uuid(),
                        state.getId()
                    ));
                }
            }
        }
        return allPlayers;
    }

    /**
     * Get the number of registered workers.
     *
     * @return Worker count
     */
    public int getWorkerCount() {
        return workers.size();
    }

    /**
     * Get the number of online workers.
     *
     * @return Online worker count
     */
    public int getOnlineWorkerCount() {
        int count = 0;
        for (HyQueryWorkerState state : workers.values()) {
            if (!state.isStale(timeoutSeconds)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the timeout in seconds.
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Player info with source server ID for network-wide player lists.
     */
    public record NetworkPlayer(String username, java.util.UUID uuid, String serverId) {}
}
