package com.hyvote.hyquery;

import java.util.List;

/**
 * Small Redis command abstraction used by Redis coordinator.
 */
public interface HyQueryRedisClient extends AutoCloseable {

    void connectAndValidate();

    void publishSnapshot(
        String serverKey,
        String indexKey,
        long ttlSeconds,
        long updatedAtMillis,
        String serverId,
        String snapshotJson
    );

    long evictStaleServers(String indexKey, long staleCutoffMillis);

    List<String> getActiveServerIds(String indexKey, long staleCutoffMillis);

    List<String> getSnapshots(List<String> serverKeys);

    void ping();

    @Override
    void close();
}
