package com.hytalefinder.hyquery;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
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
 * 0x01 = Full (basic + player list + plugin list, based on config)
 */
public final class HyQueryProtocol {

    private static final byte[] REQUEST_MAGIC = "HYQUERY\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE_MAGIC = "HYREPLY\0".getBytes(StandardCharsets.US_ASCII);

    public static final byte TYPE_BASIC = 0x00;
    public static final byte TYPE_FULL = 0x01;

    private static final int MIN_REQUEST_SIZE = REQUEST_MAGIC.length + 1;

    private HyQueryProtocol() {}

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
     * Get the query type from a request buffer.
     * Call only after isQueryRequest() returns true.
     */
    public static byte getQueryType(ByteBuf buf) {
        return buf.getByte(buf.readerIndex() + REQUEST_MAGIC.length);
    }

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

        // Player counts
        Universe universe = Universe.get();
        buf.writeIntLE(universe.getPlayerCount());
        buf.writeIntLE(getMaxPlayers());

        // Port
        buf.writeIntLE(getHostPort());

        // Version
        writeString(buf, getServerVersion());

        return buf;
    }

    /**
     * Build a full response with player list and plugins (based on config).
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

        // Player counts
        Universe universe = Universe.get();
        int playerCount = universe.getPlayerCount();
        buf.writeIntLE(playerCount);
        buf.writeIntLE(getMaxPlayers());

        // Port
        buf.writeIntLE(getHostPort());

        // Version
        writeString(buf, getServerVersion());

        // Player list (if enabled)
        if (config.showPlayerList()) {
            buf.writeIntLE(playerCount);
            for (PlayerRef player : universe.getPlayers()) {
                writeString(buf, player.getUsername());
                writeUUID(buf, player.getUuid());
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

        return buf;
    }

    private static void writeString(ByteBuf buf, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buf.writeShortLE(bytes.length);
        buf.writeBytes(bytes);
    }

    private static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
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
}
