package com.hyvote.hyquery;

import io.netty.channel.socket.DatagramPacket;

/**
 * Coordinator abstraction for network state publication/aggregation.
 */
public interface HyQueryNetworkCoordinator {

    void start();

    void stop();

    /**
     * Whether this coordinator accepts UDP worker status packets.
     */
    default boolean handlesStatusPackets() {
        return false;
    }

    /**
     * Process a UDP status update packet (UDP coordinator only).
     */
    default DatagramPacket processStatusUpdate(DatagramPacket packet) {
        return null;
    }

    /**
     * Read remote network state.
     *
     * @param includePlayers whether remote player lists should be included
     */
    HyQueryNetworkAggregate getAggregate(boolean includePlayers);

    /**
     * Metrics summary for operator logs.
     */
    String getMetricsSummary();
}
