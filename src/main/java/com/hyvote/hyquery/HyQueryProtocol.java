package com.hyvote.hyquery;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HyQuery Protocol implementation.
 *
 * Protocol format:
 * Request: HYQUERY\0 + 1-byte query type
 * Response: HYREPLY\0 + binary response data
 *
 * Query types:
 * 0x00 = Basic (server name, MOTD, player count, max players, version, port)
 * 0x01 = Full (basic + player list + plugin list + network data, based on config)
 *
 * Network packets:
 * HYSTATUS = Worker status update to primary
 * HYSTATOK = Primary acknowledgment to worker
 */
public final class HyQueryProtocol {

    // Query magic bytes
    private static final byte[] REQUEST_MAGIC = "HYQUERY\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE_MAGIC = "HYREPLY\0".getBytes(StandardCharsets.US_ASCII);

    // Network magic bytes
    private static final byte[] STATUS_MAGIC = "HYSTATUS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ACK_MAGIC = "HYSTATOK".getBytes(StandardCharsets.US_ASCII);

    // Query types
    public static final byte TYPE_BASIC = 0x00;
    public static final byte TYPE_FULL = 0x01;

    // Protocol version
    public static final byte PROTOCOL_VERSION = 0x01;

    // Ack status codes
    public static final byte ACK_OK = 0x00;
    public static final byte ACK_UNKNOWN_ID = 0x01;
    public static final byte ACK_BAD_HMAC = 0x02;
    public static final byte ACK_STALE = 0x03;

    // HMAC algorithm
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HMAC_LENGTH = 32;

    private static final int MIN_REQUEST_SIZE = REQUEST_MAGIC.length + 1;
    private static final int MIN_STATUS_SIZE = STATUS_MAGIC.length + 1 + 8 + HMAC_LENGTH;

    private HyQueryProtocol() {}

    // ========== Query Request Detection ==========

    /**
     * Check if the buffer contains a valid query request.
     */
    public static boolean isQueryRequest(ByteBuf buf) {
        if (buf.readableBytes() < MIN_REQUEST_SIZE) {
            return false;
        }

        int readerIndex = buf.readerIndex();
        for (int i = 0; i < REQUEST_MAGIC.length; i++) {
            if (buf.getByte(readerIndex + i) != REQUEST_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the buffer contains a status update packet.
     */
    public static boolean isStatusPacket(ByteBuf buf) {
        if (buf.readableBytes() < MIN_STATUS_SIZE) {
            return false;
        }

        int readerIndex = buf.readerIndex();
        for (int i = 0; i < STATUS_MAGIC.length; i++) {
            if (buf.getByte(readerIndex + i) != STATUS_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the query type from a request buffer.
     * Call only after isQueryRequest() returns true.
     */
    public static byte getQueryType(ByteBuf buf) {
        return buf.getByte(buf.readerIndex() + REQUEST_MAGIC.length);
    }

    // ========== Basic Response Building ==========

    /**
     * Build a basic response containing server info only.
     */
    public static ByteBuf buildBasicResponse(HyQueryPlugin plugin) {
        ByteBuf buf = Unpooled.buffer();

        // Write magic bytes
        buf.writeBytes(RESPONSE_MAGIC);

        // Write response type
        buf.writeByte(TYPE_BASIC);

        // Server name
        writeString(buf, getServerName());

        // MOTD (custom or from server config)
        writeString(buf, getMotd(plugin));

        // Player counts - use network totals if primary, otherwise local
        Universe universe = Universe.get();
        int onlinePlayers = universe.getPlayerCount();
        int maxPlayers = getMaxPlayers();

        if (plugin.getQueryConfig().isNetworkPrimary() && plugin.getNetworkManager() != null) {
            HyQueryNetworkAggregate aggregate = plugin.getNetworkManager().getAggregate(false);
            onlinePlayers += aggregate.totalOnlinePlayers();
            maxPlayers += aggregate.totalMaxPlayers();
        }

        buf.writeIntLE(onlinePlayers);
        buf.writeIntLE(maxPlayers);

        // Port
        buf.writeIntLE(getHostPort());

        // Version
        writeString(buf, getServerVersion());

        return buf;
    }

    /**
     * Build a full response with player list, plugins, and network data (based on config).
     */
    public static ByteBuf buildFullResponse(HyQueryPlugin plugin) {
        ByteBuf buf = Unpooled.buffer();
        HyQueryConfig config = plugin.getQueryConfig();

        // Write magic bytes
        buf.writeBytes(RESPONSE_MAGIC);

        // Write response type
        buf.writeByte(TYPE_FULL);

        // Server name
        writeString(buf, getServerName());

        // MOTD (custom or from server config)
        writeString(buf, getMotd(plugin));

        // Player counts - use network totals if primary, otherwise local
        Universe universe = Universe.get();
        int onlinePlayers = universe.getPlayerCount();
        int maxPlayers = getMaxPlayers();

        HyQueryNetworkAggregate aggregate = HyQueryNetworkAggregate.empty();
        if (config.isNetworkPrimary() && plugin.getNetworkManager() != null) {
            aggregate = plugin.getNetworkManager().getAggregate(true);
            onlinePlayers += aggregate.totalOnlinePlayers();
            maxPlayers += aggregate.totalMaxPlayers();
        }

        buf.writeIntLE(onlinePlayers);
        buf.writeIntLE(maxPlayers);

        // Port
        buf.writeIntLE(getHostPort());

        // Version
        writeString(buf, getServerVersion());

        // Player list (local + network if primary and enabled)
        if (config.showPlayerList()) {
            List<PlayerData> allPlayers = new ArrayList<>();

            // Local players
            for (PlayerRef player : universe.getPlayers()) {
                allPlayers.add(new PlayerData(player.getUsername(), player.getUuid(), null));
            }

            // Network players (if primary)
            if (config.isNetworkPrimary() && plugin.getNetworkManager() != null) {
                for (HyQueryNetworkAggregate.NetworkPlayer np : aggregate.networkPlayers()) {
                    allPlayers.add(new PlayerData(np.username(), np.uuid(), np.serverId()));
                }
            }

            buf.writeIntLE(allPlayers.size());
            for (PlayerData player : allPlayers) {
                writeString(buf, player.username);
                writeUUID(buf, player.uuid);
                // Include server ID for network players (null for local)
                writeString(buf, player.serverId != null ? player.serverId : "");
            }
        } else {
            buf.writeIntLE(0);
        }

        // Plugin list (if enabled)
        if (config.showPlugins()) {
            var plugins = PluginManager.get().getPlugins();
            buf.writeIntLE(plugins.size());
            for (var p : plugins) {
                var id = p.getIdentifier();
                writeString(buf, id.getGroup() + ":" + id.getName());
            }
        } else {
            buf.writeIntLE(0);
        }

        // Network server list (if primary)
        if (config.isNetworkPrimary() && plugin.getNetworkManager() != null) {
            var workers = aggregate.remoteServers();
            buf.writeIntLE(workers.size());

            for (HyQueryNetworkAggregate.RemoteServerSnapshot worker : workers) {
                writeString(buf, worker.serverId());
                writeString(buf, worker.serverName());
                writeString(buf, worker.motd());
                buf.writeIntLE(worker.onlinePlayers());
                buf.writeIntLE(worker.maxPlayers());
                buf.writeByte(worker.status());
                buf.writeLongLE(worker.updatedAtMillis());

                List<HyQueryWorkerState.PlayerInfo> workerPlayers = worker.players();
                buf.writeIntLE(workerPlayers.size());
                for (HyQueryWorkerState.PlayerInfo player : workerPlayers) {
                    writeString(buf, player.username());
                    writeUUID(buf, player.uuid());
                }
            }
        } else {
            buf.writeIntLE(0);
        }

        return buf;
    }

    // ========== Status Packet Building (Worker -> Primary) ==========

    /**
     * Build a status update packet for sending to the primary.
     */
    public static ByteBuf buildStatusPacket(
        String workerId,
        String serverName,
        String motd,
        int onlinePlayers,
        int maxPlayers,
        int port,
        String version,
        List<HyQueryWorkerState.PlayerInfo> players,
        String key
    ) {
        ByteBuf buf = Unpooled.buffer();

        // Magic
        buf.writeBytes(STATUS_MAGIC);

        // Version
        buf.writeByte(PROTOCOL_VERSION);

        // Timestamp (milliseconds)
        long timestamp = System.currentTimeMillis();
        buf.writeLongLE(timestamp);

        // Mark position for HMAC calculation
        int payloadStart = buf.writerIndex();

        // Payload
        writeString(buf, workerId);
        writeString(buf, serverName);
        writeString(buf, motd);
        buf.writeIntLE(onlinePlayers);
        buf.writeIntLE(maxPlayers);
        buf.writeIntLE(port);
        writeString(buf, version);

        // Player list
        buf.writeIntLE(players.size());
        for (HyQueryWorkerState.PlayerInfo player : players) {
            writeString(buf, player.username());
            writeUUID(buf, player.uuid());
        }

        // Calculate HMAC over magic + version + timestamp + payload
        byte[] packetData = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), packetData);
        byte[] hmac = calculateHmac(packetData, key);

        // Insert HMAC after timestamp (rewrite buffer)
        ByteBuf finalBuf = Unpooled.buffer();
        finalBuf.writeBytes(STATUS_MAGIC);
        finalBuf.writeByte(PROTOCOL_VERSION);
        finalBuf.writeLongLE(timestamp);
        finalBuf.writeBytes(hmac);

        // Copy payload
        byte[] payload = new byte[buf.readableBytes() - payloadStart + buf.readerIndex()];
        buf.getBytes(payloadStart, payload);
        finalBuf.writeBytes(payload);

        buf.release();
        return finalBuf;
    }

    // ========== Status Packet Parsing (Primary receives) ==========

    /**
     * Parse a status packet from a worker.
     * Returns null if parsing fails.
     */
    public static StatusPacket parseStatusPacket(ByteBuf buf) {
        try {
            int readerIndex = buf.readerIndex();

            // Skip magic (already verified)
            buf.readerIndex(readerIndex + STATUS_MAGIC.length);

            // Version
            byte version = buf.readByte();
            if (version != PROTOCOL_VERSION) {
                return null;
            }

            // Timestamp
            long timestamp = buf.readLongLE();

            // Skip HMAC (verified separately)
            buf.skipBytes(HMAC_LENGTH);

            // Payload
            String workerId = readString(buf);
            String serverName = readString(buf);
            String motd = readString(buf);
            int onlinePlayers = buf.readIntLE();
            int maxPlayers = buf.readIntLE();
            int port = buf.readIntLE();
            String serverVersion = readString(buf);

            // Player list
            int playerCount = buf.readIntLE();
            List<HyQueryWorkerState.PlayerInfo> players = new ArrayList<>(playerCount);
            for (int i = 0; i < playerCount; i++) {
                String username = readString(buf);
                UUID uuid = readUUID(buf);
                players.add(new HyQueryWorkerState.PlayerInfo(username, uuid));
            }

            // Reset reader index
            buf.readerIndex(readerIndex);

            return new StatusPacket(workerId, serverName, motd, onlinePlayers, maxPlayers,
                port, serverVersion, players, timestamp);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verify the HMAC of a status packet.
     */
    public static boolean verifyStatusHmac(ByteBuf buf, String key) {
        try {
            int readerIndex = buf.readerIndex();

            // Read everything except HMAC
            byte[] magicVersionTimestamp = new byte[STATUS_MAGIC.length + 1 + 8];
            buf.getBytes(readerIndex, magicVersionTimestamp);

            // Read HMAC from packet
            byte[] receivedHmac = new byte[HMAC_LENGTH];
            buf.getBytes(readerIndex + STATUS_MAGIC.length + 1 + 8, receivedHmac);

            // Read payload (everything after HMAC)
            int payloadStart = readerIndex + STATUS_MAGIC.length + 1 + 8 + HMAC_LENGTH;
            int payloadLength = buf.readableBytes() - (payloadStart - readerIndex);
            byte[] payload = new byte[payloadLength];
            buf.getBytes(payloadStart, payload);

            // Reconstruct data for HMAC verification (without HMAC field)
            byte[] dataToVerify = new byte[magicVersionTimestamp.length + payload.length];
            System.arraycopy(magicVersionTimestamp, 0, dataToVerify, 0, magicVersionTimestamp.length);
            System.arraycopy(payload, 0, dataToVerify, magicVersionTimestamp.length, payload.length);

            // Calculate expected HMAC
            byte[] expectedHmac = calculateHmac(dataToVerify, key);

            // Constant-time comparison
            return constantTimeEquals(receivedHmac, expectedHmac);

        } catch (Exception e) {
            return false;
        }
    }

    // ========== Ack Packet Building (Primary -> Worker) ==========

    /**
     * Build an acknowledgment packet.
     */
    public static ByteBuf buildAckPacket(byte status, long timestamp, String key) {
        ByteBuf buf = Unpooled.buffer();

        // Magic
        buf.writeBytes(ACK_MAGIC);

        // Status
        buf.writeByte(status);

        // Timestamp (echo back)
        buf.writeLongLE(timestamp);

        // Calculate HMAC
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        byte[] hmac = calculateHmac(data, key);
        buf.writeBytes(hmac);

        return buf;
    }

    // ========== Helper Methods ==========

    private static void writeString(ByteBuf buf, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buf.writeShortLE(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int length = buf.readShortLE() & 0xFFFF;
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUUID(ByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        return new UUID(msb, lsb);
    }

    private static byte[] calculateHmac(byte[] data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKey);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Return zeros on error (will fail verification)
            return new byte[HMAC_LENGTH];
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static String getServerName() {
        try {
            return HytaleServer.get().getConfig().getServerName();
        } catch (Exception e) {
            return "Hytale Server";
        }
    }

    private static String getMotd(HyQueryPlugin plugin) {
        HyQueryConfig config = plugin.getQueryConfig();

        // Use custom MOTD if enabled
        if (config.useCustomMotd()) {
            return config.customMotd();
        }

        // Otherwise use server config MOTD
        try {
            return HytaleServer.get().getConfig().getMotd();
        } catch (Exception e) {
            return "";
        }
    }

    private static int getMaxPlayers() {
        try {
            return HytaleServer.get().getConfig().getMaxPlayers();
        } catch (Exception e) {
            return 100;
        }
    }

    private static int getHostPort() {
        try {
            return Options.getOptionSet().valueOf(Options.BIND).getPort();
        } catch (Exception e) {
            return 5520;
        }
    }

    private static String getServerVersion() {
        try {
            var manifest = com.hypixel.hytale.common.util.java.ManifestUtil.getImplementationVersion();
            if (manifest != null) {
                return manifest;
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // ========== Data Classes ==========

    private record PlayerData(String username, UUID uuid, String serverId) {}

    /**
     * Parsed status packet from a worker.
     */
    public record StatusPacket(
        String workerId,
        String serverName,
        String motd,
        int onlinePlayers,
        int maxPlayers,
        int port,
        String version,
        List<HyQueryWorkerState.PlayerInfo> players,
        long timestamp
    ) {}
}
