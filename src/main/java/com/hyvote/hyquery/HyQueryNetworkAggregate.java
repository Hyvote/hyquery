package com.hyvote.hyquery;

import java.util.List;
import java.util.UUID;

/**
 * Aggregated network state consumed by V1/V2 query response builders.
 */
public record HyQueryNetworkAggregate(
    int totalOnlinePlayers,
    int totalMaxPlayers,
    List<RemoteServerSnapshot> remoteServers,
    List<NetworkPlayer> networkPlayers
) {
    public static HyQueryNetworkAggregate empty() {
        return new HyQueryNetworkAggregate(0, 0, List.of(), List.of());
    }

    public record NetworkPlayer(String username, UUID uuid, String serverId) {}

    public record RemoteServerSnapshot(
        String serverId,
        String serverName,
        String motd,
        int onlinePlayers,
        int maxPlayers,
        int port,
        String version,
        byte status,
        long updatedAtMillis,
        List<HyQueryWorkerState.PlayerInfo> players
    ) {}
}
