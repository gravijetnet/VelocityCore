package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;

public class JoinMeCommand implements SimpleCommand {

    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;

    public JoinMeCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager configManager) {
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
        if (!player.hasPermission("velocitycore.joinme.use")) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("joinme.no-perm")));
            return;
        }
        long cooldownLeft = joinMeManager.getCooldown(player.getUniqueId());
        if (cooldownLeft > 0 && !player.hasPermission("velocitycore.joinme.cooldown.bypass")) {
            CompatibilityHelper.sendMessage(player, CompatibilityHelper.colorize(
                    msg("joinme.on_cooldown"), "cooldown", String.valueOf(cooldownLeft)));
            return;
        }
        joinMeManager.sendJoinMe(player, false);
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.joinme.use");
    }
}
