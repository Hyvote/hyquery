package com.hytalefinder.hyquery;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty handler that intercepts UDP packets on the game port.
 *
 * This handler sits in the channel pipeline before the QUIC codec.
 * It checks if incoming packets are HyQuery requests (HYQUERY\0 magic bytes).
 * If so, it handles the query and responds.
 * Otherwise, it passes the packet to the next handler (QUIC codec).
 */
public class HyQueryHandler extends ChannelInboundHandlerAdapter {

    private final Logger logger;
    private final HyQueryPlugin plugin;

    public HyQueryHandler(HyQueryPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
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

        try {
            byte queryType = HyQueryProtocol.getQueryType(content);
            ByteBuf response;

            if (queryType == HyQueryProtocol.TYPE_FULL) {
                response = HyQueryProtocol.buildFullResponse(plugin);
            } else {
                response = HyQueryProtocol.buildBasicResponse(plugin);
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
