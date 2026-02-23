package com.hytalefinder.hyquery;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.logging.Level;

/**
 * Utility class for sending update notifications.
 */
public final class UpdateNotificationUtil {

    private UpdateNotificationUtil() {
    }

    public static void sendUpdateNotification(HyQueryPlugin plugin, Player player, String latestVersion) {
        Message updateLine = Message.raw(String.format(
                "[HyQuery] A new update is available (v%s).",
                latestVersion));
        Message downloadPrefix = Message.raw("[HyQuery] Download: ");
        Message curseForgeLink = Message.raw("[CurseForge]")
                .color("blue")
                .link(UpdateChecker.getCurseForgeUrl());
        Message separator = Message.raw(" ");
        Message githubLink = Message.raw("[GitHub]")
                .color("blue")
                .link(UpdateChecker.getGitHubReleasesUrl());

        player.sendMessage(updateLine);
        player.sendMessage(Message.join(downloadPrefix, curseForgeLink, separator, githubLink));
        plugin.getLogger().at(Level.FINE).log(
                "Sent update notification to player %s", player.getDisplayName());
    }

    public static void logUpdateAvailable(HyQueryPlugin plugin, String latestVersion) {
        plugin.getLogger().at(Level.INFO).log(
                "[HyQuery] A new update is available: v%s", latestVersion);
        plugin.getLogger().at(Level.INFO).log(
                "[HyQuery] Download from CurseForge: %s", UpdateChecker.getCurseForgeUrl());
        plugin.getLogger().at(Level.INFO).log(
                "[HyQuery] Download from GitHub: %s", UpdateChecker.getGitHubReleasesUrl());
    }
}
