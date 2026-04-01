package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.concurrent.TimeUnit;

public class TokensCommand implements SimpleCommand {

    private final TokenManager tokenManager;
    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TokensCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager configManager) {
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
        if (!player.hasPermission("velocitycore.tokens.view")) {
            player.sendMessage(getMessage("general.no-permission"));
            return;
        }

        tokenManager.getPlayerData(player.getUniqueId()).thenAccept(data -> {
            if (data == null) {
                player.sendMessage(getMessage("tokens.error"));
                return;
            }

            boolean unlimited = tokenManager.hasUnlimitedTokens(player.getUniqueId());
            long cooldownSeconds = joinMeManager.getCooldown(player.getUniqueId());

            String status;
            if (unlimited) {
                status = getMessage("tokens.status.unlimited").toString();
            } else if (data.getTotalTokens() > 0) {
                status = getMessage("tokens.status.available").toString();
            } else {
                status = getMessage("tokens.status.empty").toString();
            }

            String total = unlimited ? "∞" : String.valueOf(data.getTotalTokens());
            String monthly = unlimited ? "∞" : String.valueOf(data.getMonthlyTokens());
            String permanent = unlimited ? "∞" : String.valueOf(data.getPermanentTokens());
            
            String cooldown;
            if (player.hasPermission("velocitycore.joinme.cooldown.bypass")) {
                cooldown = getMessage("tokens.cooldown.bypassed").toString();
            } else if (cooldownSeconds > 0) {
                cooldown = formatDuration(cooldownSeconds);
            } else {
                cooldown = getMessage("tokens.cooldown.ready").toString();
            }

            player.sendMessage(getMessage("tokens.info",
                    Placeholder.unparsed("status", status),
                    Placeholder.unparsed("total", total),
                    Placeholder.unparsed("monthly", monthly),
                    Placeholder.unparsed("permanent", permanent),
                    Placeholder.unparsed("cooldown", cooldown)
            ));

            String storeLink = configManager.getMessages().node("tokens", "store-link").getString();
            if (storeLink != null && !storeLink.isEmpty()) {
                player.sendMessage(miniMessage.deserialize(storeLink));
            }
        });
    }

    private String formatDuration(long seconds) {
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        long remainingSeconds = seconds - TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private Component getMessage(String path, TagResolver... resolvers) {
        String template = configManager.getMessages().node(path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message for " + path + " not found.").color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return miniMessage.deserialize(template, resolvers);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.tokens.view");
    }
}
