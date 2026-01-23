package com.hytalefinder.hyquery;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty handler that intercepts UDP packets on the game port.
 *
 * This handler sits in the channel pipeline before the QUIC codec.
 * It checks if incoming packets are HyQuery requests (HYQUERY\0 magic bytes).
 * If so, it handles the query and responds.
 * Otherwise, it passes the packet to the next handler (QUIC codec).
 *
 * Security features:
 * - Per-IP rate limiting to mitigate reflection/amplification attacks
 * - Response caching to reduce CPU usage under load
 */
public class HyQueryHandler extends ChannelInboundHandlerAdapter {

    private final Logger logger;
    private final HyQueryPlugin plugin;
    private final HyQueryRateLimiter rateLimiter;
    private final HyQueryCache cache;
    private final boolean rateLimitEnabled;
    private final boolean cacheEnabled;

    public HyQueryHandler(HyQueryPlugin plugin, Logger logger,
                          HyQueryRateLimiter rateLimiter, HyQueryCache cache) {
        this.plugin = plugin;
        this.logger = logger;
        this.rateLimiter = rateLimiter;
        this.cache = cache;

        HyQueryConfig config = plugin.getQueryConfig();
        this.rateLimitEnabled = config.rateLimitEnabled();
        this.cacheEnabled = config.cacheEnabled();
    }

    @Override
    public boolean isSharable() {
        return true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket packet) {
            ByteBuf content = packet.content();

            if (HyQueryProtocol.isQueryRequest(content)) {
                handleQuery(ctx, packet);
                return;
            }
        }

        // Not a query request, pass to next handler (QUIC codec)
        ctx.fireChannelRead(msg);
    }

    private void handleQuery(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf content = packet.content();
        InetAddress senderAddress = packet.sender().getAddress();

        try {
            // Check rate limit before processing
            if (rateLimitEnabled && !rateLimiter.tryAcquire(senderAddress)) {
                logger.log(Level.FINE, "Rate limited query from " + packet.sender());
                return;
            }

            byte queryType = HyQueryProtocol.getQueryType(content);
            ByteBuf response;

            // Use cached response if caching is enabled
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

            // Send response back to sender
            DatagramPacket responsePacket = new DatagramPacket(response, packet.sender());
            ctx.writeAndFlush(responsePacket);

            logger.log(Level.FINE, "Handled query from " + packet.sender());

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling query from " + packet.sender() + ": " + e.getMessage());
        } finally {
            packet.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.log(Level.WARNING, "Exception in HyQuery handler: " + cause.getMessage());
    }
}
