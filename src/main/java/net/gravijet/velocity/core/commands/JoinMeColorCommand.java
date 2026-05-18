package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.JoinMeManager;

import java.util.List;

public class JoinMeColorCommand implements SimpleCommand {

    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;

    public JoinMeColorCommand(JoinMeManager joinMeManager, ConfigManager configManager) {
        this.joinMeManager = joinMeManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            CompatibilityHelper.sendMessage(source,
                    CompatibilityHelper.colorize(msg("general.players-only")));
            return;
        }

        if (!player.hasPermission("velocitycore.joinme.color")) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("general.no-permission")));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("joinme-color.usage")));
            return;
        }

        String rawColor = String.join(" ", args).trim();

        if (rawColor.equalsIgnoreCase("reset")) {
            joinMeManager.setPlayerColor(player.getUniqueId(), null);
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("joinme-color.reset")));
            return;
        }

        // Only allow color codes (&0-9, &a-f) and hex (&#rrggbb); reject formatting codes (&k-&o, &r)
        if (!rawColor.matches("&[0-9a-fA-F]") && !rawColor.matches("&#[0-9a-fA-F]{6}")) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("joinme-color.usage")));
            return;
        }

        joinMeManager.setPlayerColor(player.getUniqueId(), rawColor);
        CompatibilityHelper.sendMessage(player,
                CompatibilityHelper.colorize(msg("joinme-color.set")));
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.joinme.color")) return List.of();
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            return List.of("reset", "&c", "&a", "&e", "&b", "&d", "&6", "&f")
                    .stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.joinme.color");
    }
}
