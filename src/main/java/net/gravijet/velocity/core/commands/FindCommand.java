package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.database.PlayerDataDAO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.spongepowered.configurate.ConfigurationNode;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class FindCommand implements SimpleCommand {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final ProxyServer proxy;
    private final PlayerDataDAO playerDataDAO;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public FindCommand(ProxyServer proxy, PlayerDataDAO playerDataDAO, ConfigManager configManager) {
        this.proxy = proxy;
        this.playerDataDAO = playerDataDAO;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.find")) {
            CompatibilityHelper.sendMessage(invocation.source(), getMessage("general.no-permission"));
            return;
        }
        if (invocation.arguments().length == 0) {
            CompatibilityHelper.sendMessage(invocation.source(), getMessage("find.usage"));
            return;
        }

        String playerName = invocation.arguments()[0];

        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            String server = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("unknown");
            CompatibilityHelper.sendMessage(invocation.source(), getMessage("find.online",
                    Placeholder.unparsed("player", player.getUsername()),
                    Placeholder.unparsed("server", server)
            ));
        }, () -> {
            playerDataDAO.getPlayerDataByName(playerName).thenAccept(data -> {
                if (data != null) {
                    String time = DATE_FORMAT.format(new Date(data.getLastOnline()));
                    CompatibilityHelper.sendMessage(invocation.source(), getMessage("find.offline",
                            Placeholder.unparsed("player", data.getUsername()),
                            Placeholder.unparsed("server", data.getLastServer() != null ? data.getLastServer() : "unknown"),
                            Placeholder.unparsed("time", time)
                    ));
                } else {
                    CompatibilityHelper.sendMessage(invocation.source(), getMessage("general.player-not-found", Placeholder.unparsed("player", playerName)));
                }
            });
        });
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
        if (!invocation.source().hasPermission("velocitycore.find")) return Collections.emptyList();
        if (invocation.arguments().length <= 1) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> invocation.arguments().length == 0 || name.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
