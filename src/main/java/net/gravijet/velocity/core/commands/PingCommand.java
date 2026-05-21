package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;

import java.util.List;

public class PingCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final ConfigManager configManager;

    public PingCommand(ProxyServer proxy, ConfigManager configManager) {
        this.proxy = proxy;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            if (source instanceof Player player) {
                sendPing(source, player);
            } else {
                CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(msg("ping.console")));
            }
            return;
        }

        proxy.getPlayer(args[0]).ifPresentOrElse(
                target -> sendPing(source, target),
                () -> CompatibilityHelper.sendMessage(source,
                        CompatibilityHelper.colorize(msg("general.player-not-found"), "player", args[0]))
        );
    }

    private void sendPing(CommandSource source, Player target) {
        long ping = target.getPing();
        String color = ping <= 70 ? "<green>" : ping <= 200 ? "<yellow>" : "<red>";

        boolean isSelf = source.equals(target);
        String key = isSelf ? "ping.self" : "ping.other";

        CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(
                msg(key),
                "player", target.getUsername(),
                "ping", String.valueOf(ping),
                "color", color
        ));
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.ping")) return List.of();
        if (invocation.arguments().length <= 1) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> invocation.arguments().length == 0
                            || name.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.ping");
    }
}
