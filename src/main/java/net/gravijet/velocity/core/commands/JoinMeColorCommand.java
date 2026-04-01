package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;

public class JoinMeColorCommand implements SimpleCommand {

    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public JoinMeColorCommand(JoinMeManager joinMeManager, ConfigManager configManager) {
        this.joinMeManager = joinMeManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(getMessage("general.players-only"));
            return;
        }

        if (!player.hasPermission("velocitycore.joinme.color")) {
            player.sendMessage(getMessage("general.no-permission"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            player.sendMessage(getMessage("joinme-color.usage"));
            return;
        }

        String rawColor = String.join(" ", args).trim();

        if (rawColor.equalsIgnoreCase("reset")) {
            joinMeManager.setPlayerColor(player.getUniqueId(), null);
            player.sendMessage(getMessage("joinme-color.reset"));
            return;
        }

        // Basic validation for MiniMessage tags
        if (!rawColor.matches("<#[0-9a-fA-F]{6}>") && !rawColor.matches("<[a-zA-Z_]+>")) {
             player.sendMessage(getMessage("joinme-color.usage"));
             return;
        }

        joinMeManager.setPlayerColor(player.getUniqueId(), rawColor);

        Component preview = miniMessage.deserialize(rawColor + "preview");
        player.sendMessage(getMessage("joinme-color.set", Placeholder.component("preview", preview)));
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
        if (!invocation.source().hasPermission("velocitycore.joinme.color")) return List.of();
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            if ("reset".startsWith(args.length == 1 ? args[0].toLowerCase() : "")) {
                return List.of("reset");
            }
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.joinme.color");
    }
}
