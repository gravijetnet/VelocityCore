package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Optional;

public class PingCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer proxy;
    private final ConfigManager config;

    public PingCommand(ProxyServer proxy, ConfigManager config) {
        this.proxy = proxy;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        Player target;
        if (args.length > 0) {
            Optional<Player> opt = proxy.getPlayer(args[0]);
            if (opt.isEmpty()) {
                source.sendMessage(LEGACY.deserialize(config.getString("messages.player_not_found", "&cPlayer {player} not found.").replace("{player}", args[0])));
                return;
            }
            target = opt.get();
        } else {
            if (!(source instanceof Player p)) {
                source.sendMessage(LEGACY.deserialize(config.getString("messages.ping.console", "&cYou must specify a player.")));
                return;
            }
            target = p;
        }

        int ping = (int) Math.max(0, target.getPing());
        String color = ping <= 70 ? "&a" : ping <= 200 ? "&6" : "&c";

        boolean isSelf = source instanceof Player p && p.getUniqueId().equals(target.getUniqueId());
        String template = isSelf ? config.getString("messages.ping.self", "&fYour ping is {color}{ping}ms&f.") : config.getString("messages.ping.other", "&f{player}''s ping is {color}{ping}ms&f.");
        String msg = template
                .replace("{color}", color)
                .replace("{ping}", String.valueOf(ping))
                .replace("{player}", target.getUsername());

        source.sendMessage(LEGACY.deserialize(msg));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return proxy.getAllPlayers().stream().map(Player::getUsername).toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource src = invocation.source();
        if (!(src instanceof Player)) return true;
        return src.hasPermission("core.ping") || src.hasPermission("core.*");
    }
}
