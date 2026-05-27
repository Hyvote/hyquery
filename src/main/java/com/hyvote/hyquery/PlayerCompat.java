package com.hyvote.hyquery;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.lang.reflect.Method;

// Reflective shims for Player methods that were public on older HytaleServer
// builds but removed in 0.5.1 (the permissions/messaging surface moved). On
// older servers these dispatch as normal; on newer servers they degrade to a
// safe default so update-notification code paths don't throw NoSuchMethodError.
final class PlayerCompat {

    private PlayerCompat() {
    }

    static boolean hasPermission(Player player, String node) {
        try {
            Method m = player.getClass().getMethod("hasPermission", String.class);
            Object result = m.invoke(player, node);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static String getDisplayName(Player player) {
        try {
            Method m = player.getClass().getMethod("getDisplayName");
            Object result = m.invoke(player);
            return result != null ? result.toString() : player.toString();
        } catch (ReflectiveOperationException e) {
            return player.toString();
        }
    }

    static void sendMessage(Player player, Message message) {
        try {
            Method m = player.getClass().getMethod("sendMessage", Message.class);
            m.invoke(player, message);
        } catch (ReflectiveOperationException ignored) {
            // No-op on newer servers where Player.sendMessage was removed.
        }
    }
}
