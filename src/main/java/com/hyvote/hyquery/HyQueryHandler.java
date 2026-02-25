package com.hyvote.hyquery;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty handler that intercepts UDP packets on the game port.
 *
 * Handles both legacy V1 (HYQUERY\0) and OneQuery V2 request families,
 * plus network status packets (HYSTATUS) for primary servers.
 */
public class HyQueryHandler extends ChannelInboundHandlerAdapter {

    private static final byte[] MAGIC_V1_REQUEST = "HYQUERY\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC_V1_RESPONSE = "HYREPLY\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC_STATUS = "HYSTATUS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC_ACK = "HYSTATOK".getBytes(StandardCharsets.US_ASCII);

    private final Logger logger;
    private final HyQueryPlugin plugin;
    private final HyQueryRateLimiter rateLimiter;
    private final HyQueryCache cache;
    private final boolean rateLimitEnabled;
    private final boolean cacheEnabled;
    private final boolean isPrimary;
    private final boolean v1Enabled;
    private final boolean v2Enabled;
    private final HyQueryAuthConfig authConfig;
    private final HyQueryChallengeService challengeService;

    public HyQueryHandler(HyQueryPlugin plugin, Logger logger,
                          HyQueryRateLimiter rateLimiter, HyQueryCache cache) {
        this.plugin = plugin;
        this.logger = logger;
        this.rateLimiter = rateLimiter;
        this.cache = cache;

        HyQueryConfig config = plugin.getQueryConfig();
        this.rateLimitEnabled = config.rateLimitEnabled();
        this.cacheEnabled = config.cacheEnabled();
        this.isPrimary = config.isNetworkPrimary();
        this.v1Enabled = config.v1Enabled();
        this.v2Enabled = config.v2Enabled();
        this.authConfig = HyQueryAuthConfig.withDefaults(
            config.authentication(),
            HyQueryAuthPermissions.fromLegacyShowPlayerList(config.showPlayerList())
        );
        this.challengeService = v2Enabled ? HyQueryChallengeService.fromConfig(config) : null;

        if (v2Enabled && (config.challengeSecret() == null || config.challengeSecret().isBlank())) {
            logger.log(Level.INFO, "V2 challenge secret not configured; using ephemeral secret for this runtime");
        }
    }

    @Override
    public boolean isSharable() {
        return true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket packet) {
            ByteBuf content = packet.content();

            if (v2Enabled && HyQueryV2Protocol.isV2Request(content)) {
                handleV2Query(ctx, packet);
                return;
            }

            if (v1Enabled && HyQueryProtocol.isQueryRequest(content)) {
                handleV1Query(ctx, packet);
                return;
            }

            if (isPrimary && HyQueryProtocol.isStatusPacket(content)) {
                handleStatusPacket(ctx, packet);
                return;
            }

            if (isKnownHyQueryPacket(content)) {
                logger.log(Level.FINE, "Dropping unhandled HyQuery packet from " + packet.sender());
                packet.release();
                return;
            }
        }

        ctx.fireChannelRead(msg);
    }

    private void handleV1Query(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf content = packet.content();
        InetAddress senderAddress = packet.sender().getAddress();

        try {
            if (rateLimitEnabled && !rateLimiter.tryAcquire(senderAddress)) {
                logger.log(Level.FINE, "Rate limited V1 query from " + packet.sender());
                return;
            }

            byte queryType = HyQueryProtocol.getQueryType(content);
            ByteBuf response;

            if (cacheEnabled) {
                if (queryType == HyQueryProtocol.TYPE_FULL) {
                    response = cache.getFullResponse();
                } else {
                    response = cache.getBasicResponse();
                }
            } else {
                if (queryType == HyQueryProtocol.TYPE_FULL) {
                    response = HyQueryProtocol.buildFullResponse(plugin);
                } else {
                    response = HyQueryProtocol.buildBasicResponse(plugin);
                }
            }

            ctx.writeAndFlush(new DatagramPacket(response, packet.sender()));
            logger.log(Level.FINE, "Handled V1 query from " + packet.sender());

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling V1 query from " + packet.sender() + ": " + e.getMessage());
        } finally {
            packet.release();
        }
    }

    private void handleV2Query(ChannelHandlerContext ctx, DatagramPacket packet) {
        InetSocketAddress sender = packet.sender();
        ByteBuf content = packet.content();

        try {
            HyQueryV2Protocol.ParsedRequest request = HyQueryV2Protocol.parseRequest(content);
            if (!request.valid()) {
                logger.log(Level.WARNING, "Dropping malformed V2 request from " + sender + ": " + request.error());
                return;
            }

            if (request.queryType() == HyQueryV2Protocol.QueryType.CHALLENGE) {
                if (rateLimitEnabled && !rateLimiter.tryAcquire(sender.getAddress())) {
                    logger.log(Level.FINE, "Rate limited V2 challenge request from " + sender);
                    return;
                }

                byte[] token = challengeService.generateToken(sender.getAddress());
                ByteBuf response = HyQueryV2ResponseBuilder.buildChallengeResponse(
                    ctx.alloc(),
                    request.family().responseMagic(),
                    token
                );
                ctx.writeAndFlush(new DatagramPacket(response, sender));
                logger.log(Level.FINE, "Handled V2 challenge request from " + sender);
                return;
            }

            if (rateLimitEnabled && !rateLimiter.tryAcquire(sender.getAddress())) {
                logger.log(Level.FINE, "Rate limited V2 query from " + sender);
                return;
            }

            if (!challengeService.validateToken(request.challengeToken(), sender.getAddress())) {
                logger.log(Level.WARNING, "Dropping V2 request with invalid challenge token from " + sender);
                return;
            }

            HyQueryV2Protocol.QueryType effectiveType = request.queryType();
            if (effectiveType == HyQueryV2Protocol.QueryType.UNKNOWN) {
                effectiveType = HyQueryV2Protocol.QueryType.BASIC;
            }

            if (!authConfig.isAccessAllowed(effectiveType, request.authToken())) {
                ByteBuf response = buildV2AuthRequiredResponse(ctx, request);
                ctx.writeAndFlush(new DatagramPacket(response, sender));
                logger.log(Level.FINE, "V2 auth required for " + effectiveType + " from " + sender);
                return;
            }

            ByteBuf response;
            if (effectiveType == HyQueryV2Protocol.QueryType.PLAYERS) {
                response = buildV2PlayersResponse(ctx, request);
            } else {
                response = buildV2BasicResponse(ctx, request);
            }

            ctx.writeAndFlush(new DatagramPacket(response, sender));
            logger.log(Level.FINE, "Handled V2 query from " + sender + " (type=" + effectiveType + ")");

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling V2 query from " + sender + ": " + e.getMessage());
        } finally {
            packet.release();
        }
    }

    private ByteBuf buildV2BasicResponse(ChannelHandlerContext ctx, HyQueryV2Protocol.ParsedRequest request) {
        return buildV2BasicResponse(ctx, request, (short) 0);
    }

    private ByteBuf buildV2AuthRequiredResponse(ChannelHandlerContext ctx, HyQueryV2Protocol.ParsedRequest request) {
        return buildV2BasicResponse(ctx, request, HyQueryV2Protocol.FLAG_RESPONSE_AUTH_REQUIRED);
    }

    private ByteBuf buildV2BasicResponse(
        ChannelHandlerContext ctx,
        HyQueryV2Protocol.ParsedRequest request,
        short baseFlags
    ) {
        HyQueryConfig config = plugin.getQueryConfig();
        HyQueryWorkerRegistry registry = getPrimaryRegistry();

        int onlinePlayers = Universe.get().getPlayerCount();
        int maxPlayers = plugin.getMaxPlayers();

        short flags = baseFlags;
        if (registry != null) {
            onlinePlayers += registry.getTotalOnlinePlayers();
            maxPlayers += registry.getTotalMaxPlayers();
            flags |= HyQueryV2Protocol.FLAG_RESPONSE_IS_NETWORK;
        }

        String motd = config.useCustomMotd() ? config.customMotd() : plugin.getMotd();
        String version = getServerVersion();

        HyQueryV2ResponseBuilder.ServerInfo info = HyQueryV2ResponseBuilder.createServerInfo(
            plugin.getServerName(),
            motd,
            onlinePlayers,
            maxPlayers,
            version,
            null,
            null
        );

        return HyQueryV2ResponseBuilder.buildBasicResponse(
            ctx.alloc(),
            request.family().responseMagic(),
            request.requestId(),
            flags,
            info
        );
    }

    private ByteBuf buildV2PlayersResponse(ChannelHandlerContext ctx, HyQueryV2Protocol.ParsedRequest request) {
        HyQueryWorkerRegistry registry = getPrimaryRegistry();

        List<HyQueryV2ResponseBuilder.PlayerInfo> players = new ArrayList<>();
        for (PlayerRef player : Universe.get().getPlayers()) {
            players.add(new HyQueryV2ResponseBuilder.PlayerInfo(player.getUsername(), player.getUuid()));
        }

        short flags = 0;
        if (registry != null) {
            flags |= HyQueryV2Protocol.FLAG_RESPONSE_IS_NETWORK;
            for (HyQueryWorkerRegistry.NetworkPlayer networkPlayer : registry.getAllPlayers()) {
                players.add(new HyQueryV2ResponseBuilder.PlayerInfo(networkPlayer.username(), networkPlayer.uuid()));
            }
        }

        players.sort(Comparator
            .comparing(HyQueryV2ResponseBuilder.PlayerInfo::username)
            .thenComparing(p -> uuidSortKey(p.uuid())));

        return HyQueryV2ResponseBuilder.buildPlayersResponse(
            ctx.alloc(),
            request.family().responseMagic(),
            request.requestId(),
            flags,
            request.offset(),
            players
        );
    }

    private String uuidSortKey(UUID uuid) {
        return uuid.toString();
    }

    private HyQueryWorkerRegistry getPrimaryRegistry() {
        HyQueryNetworkManager networkManager = plugin.getNetworkManager();
        if (plugin.getQueryConfig().isNetworkPrimary() && networkManager != null) {
            return networkManager.getRegistry();
        }
        return null;
    }

    private String getServerVersion() {
        try {
            var manifest = com.hypixel.hytale.common.util.java.ManifestUtil.getImplementationVersion();
            if (manifest != null) {
                return manifest;
            }
        } catch (Exception ignored) {
        }
        return "Unknown";
    }

    private void handleStatusPacket(ChannelHandlerContext ctx, DatagramPacket packet) {
        InetAddress senderAddress = packet.sender().getAddress();

        try {
            if (rateLimitEnabled && !rateLimiter.tryAcquire(senderAddress)) {
                logger.log(Level.FINE, "Rate limited status packet from " + packet.sender());
                return;
            }

            HyQueryNetworkManager networkManager = plugin.getNetworkManager();
            if (networkManager == null) {
                logger.log(Level.FINE, "Received status packet but network manager not initialized");
                return;
            }

            DatagramPacket response = networkManager.processStatusUpdate(packet);
            if (response != null) {
                ctx.writeAndFlush(response);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling status packet from " + packet.sender() + ": " + e.getMessage());
        } finally {
            packet.release();
        }
    }

    private boolean isKnownHyQueryPacket(ByteBuf buf) {
        return matchesMagic(buf, MAGIC_V1_REQUEST)
            || matchesMagic(buf, MAGIC_V1_RESPONSE)
            || matchesMagic(buf, MAGIC_STATUS)
            || matchesMagic(buf, MAGIC_ACK)
            || HyQueryV2Protocol.isKnownV2Packet(buf);
    }

    private boolean matchesMagic(ByteBuf buf, byte[] magic) {
        if (buf.readableBytes() < magic.length) {
            return false;
        }

        int readerIndex = buf.readerIndex();
        for (int i = 0; i < magic.length; i++) {
            if (buf.getByte(readerIndex + i) != magic[i]) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.log(Level.WARNING, "Exception in HyQuery handler: " + cause.getMessage());
    }
}
