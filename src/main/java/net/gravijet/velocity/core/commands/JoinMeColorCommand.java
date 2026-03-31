package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public class JoinMeColorCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JoinMeManager joinMeManager;
    private final ConfigManager config;

    public JoinMeColorCommand(JoinMeManager joinMeManager, ConfigManager config) {
        this.joinMeManager = joinMeManager;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(LEGACY.deserialize(config.getString("messages.players_only", "&cThis command can only be used by players.")));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            player.sendMessage(LEGACY.deserialize(config.getString("messages.joinme.color.usage", "&cUsage: /joinmecolor <color|reset>")));
            return;
        }

        String raw = String.join(" ", args).trim();

        if (raw.equalsIgnoreCase("reset")) {
            joinMeManager.setPlayerColor(player.getUniqueId(), null);
            player.sendMessage(LEGACY.deserialize(config.getString("messages.joinme.color.reset", "&aYour JoinMe color has been reset.")));
            return;
        }

        String normalized = raw.replace('&', '\u00a7');
        joinMeManager.setPlayerColor(player.getUniqueId(), normalized);

        String sectionSeq = joinMeManager.formatToSectionColor(normalized);
        String visible = stripFormatting(normalized);
        if (visible.isEmpty()) visible = "[preview]";

        String preview = sectionSeq + visible;
        player.sendMessage(LEGACY.deserialize(config.getString("messages.joinme.color.set", "&aYour JoinMe color has been set to: {preview}").replace("{preview}", preview)));
    }

    private String stripFormatting(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = input.replace('&', '\u00a7');
        s = s.replaceAll("(?i)\u00a7x(\u00a7[0-9A-Fa-f]){6}", "");
        s = s.replaceAll("(?i)\u00a7#[0-9A-Fa-f]{6}", "");
        s = s.replaceAll("(?i)#[0-9A-Fa-f]{6}", "");
        s = s.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "");
        s = s.replace("\u00a7", "");
        return s;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission("core.joinme.use")) return List.of();
        String[] args = invocation.arguments();
        if (args.length == 1 && "reset".startsWith(args[0].toLowerCase())) return List.of("reset");
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("core.joinme.use");
    }
}
