package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.config.CoreConfig;
import net.gravijet.velocity.core.database.PlayerDataDAO;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class FindCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final ProxyServer proxy;
    private final PlayerDataDAO playerDataDAO;
    private final CoreConfig config;

    public FindCommand(ProxyServer proxy, PlayerDataDAO playerDataDAO, CoreConfig config) {
        this.proxy = proxy;
        this.playerDataDAO = playerDataDAO;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("core.staff.find")) {
            invocation.source().sendMessage(LEGACY.deserialize(config.get().noPermission));
            return;
        }
        if (invocation.arguments().length == 0) {
            invocation.source().sendMessage(LEGACY.deserialize(config.get().findUsage));
            return;
        }

        String playerName = invocation.arguments()[0];
        Optional<Player> online = proxy.getPlayer(playerName);

        if (online.isPresent()) {
            Player p = online.get();
            String server = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("unknown");
            invocation.source().sendMessage(LEGACY.deserialize(
                    config.get().findOnline
                            .replace("{player}", p.getUsername())
                            .replace("{server}", server)));
        } else {
            playerDataDAO.getPlayerDataByName(playerName).thenAccept(data -> {
                if (data != null) {
                    String time = DATE_FORMAT.format(new Date(data.getLastOnline()));
                    invocation.source().sendMessage(LEGACY.deserialize(
                            config.get().findOffline
                                    .replace("{player}", data.getUsername())
                                    .replace("{server}", data.getLastServer() != null ? data.getLastServer() : "unknown")
                                    .replace("{time}", time)));
                } else {
                    invocation.source().sendMessage(LEGACY.deserialize(config.get().playerNotFound.replace("{player}", playerName)));
                }
            });
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission("core.staff.find")) return Collections.emptyList();
        return proxy.getAllPlayers().stream().map(Player::getUsername).toList();
    }
}
