package com.hyvote.hyquery;

import com.google.gson.Gson;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis coordinator implementation.
 *
 * Role behavior in v1:
 * - worker: publishes local snapshots only
 * - primary: reads snapshots and serves aggregated network responses
 */
public class RedisNetworkCoordinator implements HyQueryNetworkCoordinator {

    private static final String KEY_PREFIX = "hyquery";
    private static final long AGGREGATE_CACHE_TTL_MILLIS = 1_000L;
    private static final long MAX_PUBLISH_BACKOFF_MILLIS = 60_000L;
    private static final String RANDOM_ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int RANDOM_ID_LENGTH = 8;

    private final HyQueryPlugin plugin;
    private final HyQueryNetworkConfig config;
    private final HyQueryNetworkObservability observability;
    private final HyQueryRedisClient redisClient;
    private final boolean closeRedisClient;
    private final Gson gson = new Gson();

    private final List<String> readNamespaces;
    private final String publishNamespace;
    private final long staleAfterMillis;
    private final long snapshotTtlSeconds;
    private final long publishIntervalSeconds;
    private final long publishIntervalMillis;
    private final String workerServerId;
    private final boolean workerServerIdGenerated;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> publishTask;

    private volatile boolean healthy;
    private volatile CachedAggregate cachedWithoutPlayers;
    private volatile CachedAggregate cachedWithPlayers;
    private volatile long nextPublishAttemptAtMillis;
    private volatile int consecutivePublishFailures;

    public RedisNetworkCoordinator(
        HyQueryPlugin plugin,
        HyQueryNetworkConfig config,
        HyQueryNetworkObservability observability
    ) {
        this(plugin, config, observability, new JedisHyQueryRedisClient(config.redis()), true);
    }

    RedisNetworkCoordinator(
        HyQueryPlugin plugin,
        HyQueryNetworkConfig config,
        HyQueryNetworkObservability observability,
        HyQueryRedisClient redisClient,
        boolean closeRedisClient
    ) {
        this.plugin = plugin;
        this.config = config;
        this.observability = observability;
        this.redisClient = redisClient;
        this.closeRedisClient = closeRedisClient;

        this.readNamespaces = config.getReadNamespaces();
        this.publishNamespace = HyQueryNetworkConfig.normalizedNamespace(config.namespace());
        this.staleAfterMillis = Math.max(1, config.staleAfterSeconds()) * 1_000L;
        this.publishIntervalSeconds = Math.max(1, config.redis().publishIntervalSeconds());
        this.publishIntervalMillis = publishIntervalSeconds * 1_000L;
        this.snapshotTtlSeconds = Math.max(
            1L,
            Math.max(config.staleAfterSeconds() * 2L, publishIntervalSeconds * 3L)
        );

        String configuredWorkerId = config.id() != null ? config.id().trim() : "";
        if (configuredWorkerId.isEmpty()) {
            this.workerServerId = generateRandomWorkerId();
            this.workerServerIdGenerated = true;
        } else {
            this.workerServerId = configuredWorkerId;
            this.workerServerIdGenerated = false;
        }
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            return;
        }

        if (!config.redis().requireAvailableOrDefault()) {
            observability.warn("network.redis.requireAvailable=false is ignored in v1; hard-fail is always enforced");
        }

        try {
            redisClient.connectAndValidate();
            healthy = true;
        } catch (Exception e) {
            throw new IllegalStateException("Redis coordinator startup failed: " + e.getMessage(), e);
        }

        observability.info("Network mode: " + (config.isPrimary() ? "PRIMARY" : "WORKER") + " (coordinator=redis)");
        observability.info("  - Namespace: " + publishNamespace);
        observability.info("  - Read namespaces: " + String.join(", ", readNamespaces));
        observability.info("  - staleAfterSeconds: " + config.staleAfterSeconds());
        observability.info("  - Redis endpoint: " + config.redis().host() + ":" + config.redis().port());
        observability.info("  - Redis TLS: " + config.redis().tls());
        observability.info("  - Redis ACL username configured: "
            + (config.redis().username() != null && !config.redis().username().isBlank()));
        if (config.isWorker() && workerServerIdGenerated) {
            observability.warn("network.id is missing/blank; generated worker ID for this runtime: " + workerServerId);
        }

        if (config.isWorker()) {
            startPublisher();
        }
    }

    @Override
    public void stop() {
        if (publishTask != null) {
            publishTask.cancel(false);
            publishTask = null;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }

        if (closeRedisClient) {
            try {
                redisClient.close();
            } catch (Exception ignored) {
            }
        }

        consecutivePublishFailures = 0;
        nextPublishAttemptAtMillis = 0L;
    }

    @Override
    public HyQueryNetworkAggregate getAggregate(boolean includePlayers) {
        if (!config.isPrimary()) {
            return HyQueryNetworkAggregate.empty();
        }

        CachedAggregate cached = includePlayers ? cachedWithPlayers : cachedWithoutPlayers;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis <= AGGREGATE_CACHE_TTL_MILLIS) {
            observability.recordCacheHit();
            return cached.aggregate;
        }

        observability.recordCacheMiss();
        HyQueryNetworkAggregate aggregate = fetchAggregateFromRedis(includePlayers);
        CachedAggregate fresh = new CachedAggregate(aggregate, now);
        if (includePlayers) {
            cachedWithPlayers = fresh;
        } else {
            cachedWithoutPlayers = fresh;
        }
        return aggregate;
    }

    @Override
    public String getMetricsSummary() {
        return observability.metricsSummary();
    }

    private void startPublisher() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HyQuery-Redis-Worker");
            t.setDaemon(true);
            return t;
        });

        publishTask = scheduler.scheduleAtFixedRate(
            this::publishLocalSnapshot,
            0,
            publishIntervalMillis,
            TimeUnit.MILLISECONDS
        );

        observability.info("Redis worker snapshot publishing started (interval=" + publishIntervalSeconds + "s)");
    }

    private void publishLocalSnapshot() {
        long now = System.currentTimeMillis();
        if (now < nextPublishAttemptAtMillis) {
            return;
        }

        long startMillis = now;
        observability.recordPublishAttempt();

        try {
            RedisSnapshotPayload payload = buildLocalSnapshotPayload();
            String json = gson.toJson(payload);
            String serverKey = serverKey(publishNamespace, payload.serverId);
            String indexKey = indexKey(publishNamespace);

            redisClient.publishSnapshot(
                serverKey,
                indexKey,
                snapshotTtlSeconds,
                payload.updatedAtMillis,
                payload.serverId,
                json
            );

            healthy = true;
            if (consecutivePublishFailures > 0) {
                observability.warn("Redis publish recovered after " + consecutivePublishFailures
                    + " consecutive failure(s)");
            }
            consecutivePublishFailures = 0;
            nextPublishAttemptAtMillis = 0L;
            observability.recordPublishSuccess(System.currentTimeMillis() - startMillis);

            if (config.logStatusUpdates()) {
                observability.info("Published Redis snapshot for worker '" + payload.serverId + "' ("
                    + payload.onlinePlayers + "/" + payload.maxPlayers + " players)");
            }

        } catch (Exception e) {
            observability.recordPublishFailure();
            handlePublishFailure(e);
        }
    }

    private HyQueryNetworkAggregate fetchAggregateFromRedis(boolean includePlayers) {
        long startMillis = System.currentTimeMillis();
        observability.recordReadAttempt();

        try {
            long now = System.currentTimeMillis();
            long staleCutoffMillis = now - staleAfterMillis;
            Map<String, RedisSnapshotPayload> snapshotsByServerId = new HashMap<>();

            for (String namespace : readNamespaces) {
                String indexKey = indexKey(namespace);
                long evicted = redisClient.evictStaleServers(indexKey, staleCutoffMillis);
                observability.recordStaleEvictions(evicted);

                List<String> activeIds = redisClient.getActiveServerIds(indexKey, staleCutoffMillis);
                if (activeIds.isEmpty()) {
                    continue;
                }

                List<String> keys = activeIds.stream().map(id -> serverKey(namespace, id)).toList();
                List<String> rawSnapshots = redisClient.getSnapshots(keys);

                for (int i = 0; i < activeIds.size(); i++) {
                    String raw = i < rawSnapshots.size() ? rawSnapshots.get(i) : null;
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }

                    RedisSnapshotPayload payload = parseSnapshot(raw, activeIds.get(i));
                    if (payload == null || payload.updatedAtMillis <= staleCutoffMillis) {
                        continue;
                    }

                    RedisSnapshotPayload existing = snapshotsByServerId.get(payload.serverId);
                    if (existing == null || payload.updatedAtMillis > existing.updatedAtMillis) {
                        snapshotsByServerId.put(payload.serverId, payload);
                    }
                }
            }

            List<HyQueryNetworkAggregate.RemoteServerSnapshot> remoteServers = new ArrayList<>();
            List<HyQueryNetworkAggregate.NetworkPlayer> allPlayers = new ArrayList<>();
            int totalOnlinePlayers = 0;
            int totalMaxPlayers = 0;

            for (RedisSnapshotPayload payload : snapshotsByServerId.values()) {
                List<HyQueryWorkerState.PlayerInfo> snapshotPlayers = includePlayers
                    ? payload.toWorkerPlayers()
                    : List.of();

                remoteServers.add(new HyQueryNetworkAggregate.RemoteServerSnapshot(
                    payload.serverId,
                    payload.serverName,
                    payload.motd,
                    payload.onlinePlayers,
                    payload.maxPlayers,
                    payload.port,
                    payload.version,
                    HyQueryWorkerState.STATUS_ONLINE,
                    payload.updatedAtMillis,
                    snapshotPlayers
                ));

                totalOnlinePlayers += payload.onlinePlayers;
                totalMaxPlayers += payload.maxPlayers;

                if (includePlayers) {
                    for (HyQueryWorkerState.PlayerInfo player : snapshotPlayers) {
                        allPlayers.add(new HyQueryNetworkAggregate.NetworkPlayer(
                            player.username(),
                            player.uuid(),
                            payload.serverId
                        ));
                    }
                }
            }

            remoteServers.sort(Comparator.comparing(HyQueryNetworkAggregate.RemoteServerSnapshot::serverId));

            HyQueryNetworkAggregate aggregate = new HyQueryNetworkAggregate(
                totalOnlinePlayers,
                totalMaxPlayers,
                remoteServers,
                allPlayers
            );

            healthy = true;
            observability.recordReadSuccess(remoteServers.size(), System.currentTimeMillis() - startMillis);
            return aggregate;

        } catch (Exception e) {
            observability.recordReadFailure();
            return handleRuntimeFailure("read", e);
        }
    }

    private RedisSnapshotPayload buildLocalSnapshotPayload() {
        Universe universe = Universe.get();
        HyQueryConfig queryConfig = plugin.getQueryConfig();

        RedisSnapshotPayload payload = new RedisSnapshotPayload();
        payload.serverId = workerServerId;
        payload.serverName = plugin.getServerName();
        payload.motd = queryConfig.useCustomMotd() ? queryConfig.customMotd() : plugin.getMotd();
        payload.onlinePlayers = universe.getPlayerCount();
        payload.maxPlayers = plugin.getMaxPlayers();
        payload.port = plugin.getGamePort();
        payload.version = plugin.getPluginVersion();
        payload.updatedAtMillis = System.currentTimeMillis();

        List<RedisPlayerPayload> players = new ArrayList<>();
        for (PlayerRef player : universe.getPlayers()) {
            RedisPlayerPayload redisPlayer = new RedisPlayerPayload();
            redisPlayer.username = player.getUsername();
            redisPlayer.uuid = player.getUuid().toString();
            players.add(redisPlayer);
        }
        payload.players = players;
        return payload;
    }

    private RedisSnapshotPayload parseSnapshot(String rawJson, String fallbackServerId) {
        try {
            RedisSnapshotPayload payload = gson.fromJson(rawJson, RedisSnapshotPayload.class);
            if (payload == null) {
                return null;
            }

            if (payload.serverId == null || payload.serverId.isBlank()) {
                payload.serverId = fallbackServerId;
            }
            if (payload.serverName == null) {
                payload.serverName = "";
            }
            if (payload.motd == null) {
                payload.motd = "";
            }
            if (payload.version == null) {
                payload.version = "";
            }
            if (payload.players == null) {
                payload.players = List.of();
            }

            if (payload.serverId == null || payload.serverId.isBlank()) {
                return null;
            }

            return payload;
        } catch (Exception e) {
            observability.warn("Failed to parse Redis snapshot JSON: " + e.getMessage());
            return null;
        }
    }

    private HyQueryNetworkAggregate handleRuntimeFailure(String operation, Exception e) {
        healthy = false;
        String message = "Redis coordinator " + operation + " failed (hard-fail enforced): " + e.getMessage();
        observability.error(message);
        throw new IllegalStateException(message, e);
    }

    private void handlePublishFailure(Exception e) {
        healthy = false;
        consecutivePublishFailures++;

        long backoffMillis = computePublishBackoffMillis(consecutivePublishFailures);
        nextPublishAttemptAtMillis = System.currentTimeMillis() + backoffMillis;

        observability.warn("Redis publish failed (" + consecutivePublishFailures + " consecutive failure(s)): "
            + e.getMessage() + ". Backing off for " + backoffMillis + "ms.");
    }

    private long computePublishBackoffMillis(int failures) {
        long backoffMillis = publishIntervalMillis;
        int shifts = Math.max(0, failures - 1);
        for (int i = 0; i < shifts && backoffMillis < MAX_PUBLISH_BACKOFF_MILLIS; i++) {
            backoffMillis = Math.min(MAX_PUBLISH_BACKOFF_MILLIS, backoffMillis * 2L);
        }
        return Math.max(publishIntervalMillis, backoffMillis);
    }

    private String generateRandomWorkerId() {
        StringBuilder id = new StringBuilder(RANDOM_ID_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < RANDOM_ID_LENGTH; i++) {
            id.append(RANDOM_ID_CHARS.charAt(random.nextInt(RANDOM_ID_CHARS.length())));
        }
        return id.toString();
    }

    private String indexKey(String namespace) {
        return KEY_PREFIX + ":{" + namespace + "}:index";
    }

    private String serverKey(String namespace, String serverId) {
        return KEY_PREFIX + ":{" + namespace + "}:server:" + serverId;
    }

    private record CachedAggregate(HyQueryNetworkAggregate aggregate, long loadedAtMillis) {
    }

    private static final class RedisSnapshotPayload {
        private String serverId;
        private String serverName;
        private String motd;
        private int onlinePlayers;
        private int maxPlayers;
        private int port;
        private String version;
        private List<RedisPlayerPayload> players;
        private long updatedAtMillis;

        private List<HyQueryWorkerState.PlayerInfo> toWorkerPlayers() {
            if (players == null || players.isEmpty()) {
                return List.of();
            }

            List<HyQueryWorkerState.PlayerInfo> list = new ArrayList<>(players.size());
            for (RedisPlayerPayload payload : players) {
                if (payload == null || payload.uuid == null || payload.uuid.isBlank()) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(payload.uuid);
                    String username = payload.username != null ? payload.username : "";
                    list.add(new HyQueryWorkerState.PlayerInfo(username, uuid));
                } catch (Exception ignored) {
                }
            }
            return list;
        }
    }

    private static final class RedisPlayerPayload {
        private String username;
        private String uuid;
    }
}
