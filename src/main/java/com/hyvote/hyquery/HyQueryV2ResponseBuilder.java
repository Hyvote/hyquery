package com.hyvote.hyquery;

import com.hypixel.hytale.protocol.ProtocolSettings;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

/**
 * Builds V2 query responses.
 */
public final class HyQueryV2ResponseBuilder {

    public static final short TLV_TYPE_SERVER_INFO = 0x0001;
    public static final short TLV_TYPE_PLAYER_LIST = 0x0002;
    private static final String PROTOCOL_HASH = String.format(Locale.ROOT, "%08x", ProtocolSettings.PROTOCOL_CRC);

    private static final int TLV_HEADER_SIZE = 4;
    private static final int PLAYER_LIST_HEADER_SIZE = 12;
    private static final int MAX_PAYLOAD_SIZE = HyQueryV2Protocol.SAFE_MTU - HyQueryV2Protocol.HEADER_SIZE - 50;

    private HyQueryV2ResponseBuilder() {}

    public static ByteBuf buildChallengeResponse(ByteBufAllocator alloc, byte[] responseMagic, byte[] challengeToken) {
        ByteBuf buf = alloc.buffer(48);
        buf.writeBytes(responseMagic);
        buf.writeByte(HyQueryV2Protocol.TYPE_CHALLENGE);
        buf.writeBytes(challengeToken);
        buf.writeZero(7);
        return buf;
    }

    public static ByteBuf buildBasicResponse(
        ByteBufAllocator alloc,
        byte[] responseMagic,
        int requestId,
        short flags,
        ServerInfo info
    ) {
        ByteBuf payload = alloc.buffer();
        try {
            ByteBuf serverInfoValue = alloc.buffer();
            try {
                writeServerInfo(serverInfoValue, info, hasFlag(flags, HyQueryV2Protocol.FLAG_RESPONSE_HAS_ADDRESS));
                writeTlv(payload, TLV_TYPE_SERVER_INFO, serverInfoValue);
            } finally {
                serverInfoValue.release();
            }

            return buildPacket(alloc, responseMagic, requestId, flags, payload);
        } finally {
            payload.release();
        }
    }

    public static ByteBuf buildPlayersResponse(
        ByteBufAllocator alloc,
        byte[] responseMagic,
        int requestId,
        short baseFlags,
        int requestedOffset,
        List<PlayerInfo> players
    ) {
        ByteBuf listValue = alloc.buffer();
        try {
            short flags = baseFlags;

            int totalPlayers = players.size();
            int startIndex = Math.min(Math.max(0, requestedOffset), totalPlayers);

            listValue.writeIntLE(totalPlayers);
            int countPosition = listValue.writerIndex();
            listValue.writeIntLE(0);
            listValue.writeIntLE(startIndex);

            int countInResponse = 0;
            int remaining = MAX_PAYLOAD_SIZE - TLV_HEADER_SIZE - PLAYER_LIST_HEADER_SIZE;

            for (int i = startIndex; i < totalPlayers; i++) {
                PlayerInfo player = players.get(i);
                byte[] usernameBytes = player.username().getBytes(StandardCharsets.UTF_8);
                int entrySize = 2 + usernameBytes.length + 16;

                if (remaining < entrySize) {
                    flags |= HyQueryV2Protocol.FLAG_RESPONSE_HAS_MORE_PLAYERS;
                    break;
                }

                listValue.writeShortLE(usernameBytes.length);
                listValue.writeBytes(usernameBytes);
                writeUUID(listValue, player.uuid());

                remaining -= entrySize;
                countInResponse++;
            }

            listValue.setIntLE(countPosition, countInResponse);

            ByteBuf payload = alloc.buffer();
            try {
                writeTlv(payload, TLV_TYPE_PLAYER_LIST, listValue);
                return buildPacket(alloc, responseMagic, requestId, flags, payload);
            } finally {
                payload.release();
            }
        } finally {
            listValue.release();
        }
    }

    public static ServerInfo createServerInfo(
        String serverName,
        String motd,
        int playerCount,
        int maxPlayers,
        String version,
        String host,
        Integer port
    ) {
        return new ServerInfo(
            serverName,
            motd,
            playerCount,
            maxPlayers,
            version,
            ProtocolSettings.PROTOCOL_VERSION,
            PROTOCOL_HASH,
            host,
            port
        );
    }

    private static ByteBuf buildPacket(
        ByteBufAllocator alloc,
        byte[] responseMagic,
        int requestId,
        short flags,
        ByteBuf payload
    ) {
        ByteBuf buf = alloc.buffer(HyQueryV2Protocol.HEADER_SIZE + payload.readableBytes());
        buf.writeBytes(responseMagic);
        buf.writeByte(HyQueryV2Protocol.VERSION);
        buf.writeShortLE(flags);
        buf.writeIntLE(requestId);
        buf.writeShortLE(payload.readableBytes());
        buf.writeBytes(payload);
        return buf;
    }

    private static void writeTlv(ByteBuf buf, short type, ByteBuf value) {
        buf.writeShortLE(type);
        buf.writeShortLE(value.readableBytes());
        buf.writeBytes(value);
    }

    private static void writeServerInfo(ByteBuf buf, ServerInfo info, boolean includeAddress) {
        writeString(buf, info.serverName());
        writeString(buf, info.motd());
        buf.writeIntLE(info.playerCount());
        buf.writeIntLE(info.maxPlayers());
        writeString(buf, info.version());
        buf.writeIntLE(info.protocolVersion());
        writeString(buf, info.protocolHash());

        if (includeAddress && info.host() != null && !info.host().isBlank() && info.port() != null) {
            writeString(buf, info.host());
            buf.writeShortLE(info.port());
        }
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buf.writeShortLE(bytes.length);
        buf.writeBytes(bytes);
    }

    private static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    private static boolean hasFlag(short flags, short flag) {
        return (flags & flag) != 0;
    }

    public record ServerInfo(
        String serverName,
        String motd,
        int playerCount,
        int maxPlayers,
        String version,
        int protocolVersion,
        String protocolHash,
        String host,
        Integer port
    ) {}

    public record PlayerInfo(String username, UUID uuid) {}
}
