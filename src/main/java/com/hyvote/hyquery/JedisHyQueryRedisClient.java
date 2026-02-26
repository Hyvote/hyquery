package com.hyvote.hyquery;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Jedis-backed Redis command client.
 */
public class JedisHyQueryRedisClient implements HyQueryRedisClient {

    private final JedisPooled jedis;

    public JedisHyQueryRedisClient(HyQueryRedisConfig config) {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
            .user(config.username() != null && !config.username().isBlank() ? config.username() : null)
            .password(config.password() != null && !config.password().isBlank() ? config.password() : null)
            .database(config.database())
            .ssl(config.tls())
            .connectionTimeoutMillis(config.connectTimeoutMillis())
            .socketTimeoutMillis(config.readTimeoutMillis())
            .build();

        this.jedis = new JedisPooled(new HostAndPort(config.host(), config.port()), clientConfig);
    }

    @Override
    public void connectAndValidate() {
        String response = jedis.ping();
        if (!"PONG".equalsIgnoreCase(response)) {
            throw new IllegalStateException("Redis health check failed: unexpected PING response '" + response + "'");
        }
    }

    @Override
    public void publishSnapshot(
        String serverKey,
        String indexKey,
        long ttlSeconds,
        long updatedAtMillis,
        String serverId,
        String snapshotJson
    ) {
        jedis.setex(serverKey, (int) Math.max(1L, ttlSeconds), snapshotJson);
        jedis.zadd(indexKey, updatedAtMillis, serverId);
    }

    @Override
    public long evictStaleServers(String indexKey, long staleCutoffMillis) {
        return jedis.zremrangeByScore(indexKey, Double.NEGATIVE_INFINITY, staleCutoffMillis);
    }

    @Override
    public List<String> getActiveServerIds(String indexKey, long staleCutoffMillis) {
        return new ArrayList<>(jedis.zrangeByScore(indexKey, staleCutoffMillis, Double.POSITIVE_INFINITY));
    }

    @Override
    public List<String> getSnapshots(List<String> serverKeys) {
        if (serverKeys == null || serverKeys.isEmpty()) {
            return List.of();
        }
        return jedis.mget(serverKeys.toArray(String[]::new));
    }

    @Override
    public void ping() {
        jedis.ping();
    }

    @Override
    public void close() {
        jedis.close();
    }
}
