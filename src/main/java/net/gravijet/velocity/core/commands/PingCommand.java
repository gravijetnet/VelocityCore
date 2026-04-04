package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;

public class PingCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

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
                CompatibilityHelper.sendMessage(source, getMessage("ping.console"));
            }
            return;
        }

        proxy.getPlayer(args[0]).ifPresentOrElse(
                target -> sendPing(source, target),
                () -> CompatibilityHelper.sendMessage(source, getMessage("general.player-not-found", Placeholder.unparsed("player", args[0])))
        );
    }

    private void sendPing(CommandSource source, Player target) {
        long ping = target.getPing();
        String color = ping <= 70 ? "<green>" : ping <= 200 ? "<yellow>" : "<red>";

        boolean isSelf = source.equals(target);
        String messageKey = isSelf ? "ping.self" : "ping.other";

        CompatibilityHelper.sendMessage(source, getMessage(messageKey,
                Placeholder.unparsed("player", target.getUsername()),
                Placeholder.unparsed("ping", String.valueOf(ping)),
                Placeholder.unparsed("color", color)
        ));
    }

    private Component getMessage(String path, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        String template = getMessageNode(path).getString("");
        return miniMessage.deserialize(template, resolvers);
    }

    private ConfigurationNode getMessageNode(String path) {
        Object[] parts = path.split("\\.");
        return configManager.getMessages().node(parts);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> invocation.arguments().length == 0 || name.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.ping");
    }
}
