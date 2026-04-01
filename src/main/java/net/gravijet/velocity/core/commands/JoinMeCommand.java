package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.spongepowered.configurate.ConfigurationNode;

public class JoinMeCommand implements SimpleCommand {

    private final TokenManager tokenManager;
    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public JoinMeCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager configManager) {
        this.tokenManager = tokenManager;
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
        if (!player.hasPermission("velocitycore.joinme.use")) {
            player.sendMessage(getMessage("joinme.no-perm"));
            return;
        }
        long cooldownLeft = joinMeManager.getCooldown(player.getUniqueId());
        if (cooldownLeft > 0 && !player.hasPermission("velocitycore.joinme.cooldown.bypass")) {
            player.sendMessage(getMessage("joinme.on_cooldown", Placeholder.unparsed("cooldown", String.valueOf(cooldownLeft))));
            return;
        }
        joinMeManager.sendJoinMe(player, false);
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
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.joinme.use");
    }
}
