package net.gravijet.velocity.core.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class CompatibilityHelper {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return MM.deserialize(text);
    }

    public static Component colorize(String text, String... pairs) {
        if (text == null || text.isEmpty()) return Component.empty();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1] != null ? pairs[i + 1] : "");
        }
        return MM.deserialize(text);
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
