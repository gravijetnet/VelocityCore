package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.database.PlayerDataDAO;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class FindCommand implements SimpleCommand {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final ProxyServer proxy;
    private final PlayerDataDAO playerDataDAO;
    private final ConfigManager configManager;

    public FindCommand(ProxyServer proxy, PlayerDataDAO playerDataDAO, ConfigManager configManager) {
        this.proxy = proxy;
        this.playerDataDAO = playerDataDAO;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.find")) {
            CompatibilityHelper.sendMessage(invocation.source(),
                    CompatibilityHelper.colorize(msg("general.no-permission")));
            return;
        }
        if (invocation.arguments().length == 0) {
            CompatibilityHelper.sendMessage(invocation.source(),
                    CompatibilityHelper.colorize(msg("find.usage")));
            return;
        }

        String playerName = invocation.arguments()[0];

        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            String server = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName()).orElse("unknown");
            CompatibilityHelper.sendMessage(invocation.source(), CompatibilityHelper.colorize(
                    msg("find.online"), "player", player.getUsername(), "server", server));
        }, () -> playerDataDAO.getPlayerDataByName(playerName).thenAccept(data -> {
            if (data != null) {
                String time = DATE_FORMAT.format(new Date(data.getLastOnline()));
                CompatibilityHelper.sendMessage(invocation.source(), CompatibilityHelper.colorize(
                        msg("find.offline"),
                        "player", data.getUsername(),
                        "server", data.getLastServer() != null ? data.getLastServer() : "unknown",
                        "time", time));
            } else {
                CompatibilityHelper.sendMessage(invocation.source(), CompatibilityHelper.colorize(
                        msg("general.player-not-found"), "player", playerName));
            }
        }));
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.find")) return Collections.emptyList();
        if (invocation.arguments().length <= 1) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> invocation.arguments().length == 0
                            || name.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
