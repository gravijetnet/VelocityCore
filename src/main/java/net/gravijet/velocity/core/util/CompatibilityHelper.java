package net.gravijet.velocity.core.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class CompatibilityHelper {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /**
     * Parses a string with legacy &amp; color codes into a Component.
     */
    public static Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return LEGACY.deserialize(text);
    }

    /**
     * Replaces {key} placeholders in the text, then parses legacy &amp; color codes.
     * Pass key-value pairs: colorize(template, "player", "Steve", "server", "lobby")
     */
    public static Component colorize(String text, String... pairs) {
        if (text == null || text.isEmpty()) return Component.empty();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1] != null ? pairs[i + 1] : "");
        }
        return LEGACY.deserialize(text);
    }

    public static void sendMessage(Player player, Component component) {
        if (player == null || component == null) return;
        player.sendMessage(component);
    }

    public static void sendMessage(CommandSource source, Component component) {
        if (source == null || component == null) return;
        source.sendMessage(component);
    }
}