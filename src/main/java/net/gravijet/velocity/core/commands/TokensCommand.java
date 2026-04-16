package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;

import java.util.concurrent.TimeUnit;

public class TokensCommand implements SimpleCommand {

    private final TokenManager tokenManager;
    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;

    public TokensCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager configManager) {
        this.tokenManager = tokenManager;
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
        if (!player.hasPermission("velocitycore.tokens.view")) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("general.no-permission")));
            return;
        }

        tokenManager.getPlayerData(player.getUniqueId()).thenAccept(data -> {
            if (data == null) {
                CompatibilityHelper.sendMessage(player,
                        CompatibilityHelper.colorize(msg("tokens.error")));
                return;
            }

            boolean unlimited = tokenManager.hasUnlimitedTokens(player.getUniqueId());
            long cooldownSeconds = joinMeManager.getCooldown(player.getUniqueId());

            // Get raw strings (not deserialized) so they can be embedded as placeholders
            String status;
            if (unlimited) {
                status = msg("tokens.status.unlimited");
            } else if (data.getTotalTokens() > 0) {
                status = msg("tokens.status.available");
            } else {
                status = msg("tokens.status.empty");
            }

            String total = unlimited ? "\u221e" : String.valueOf(data.getTotalTokens());
            String monthly = unlimited ? "\u221e" : String.valueOf(data.getMonthlyTokens());
            String permanent = unlimited ? "\u221e" : String.valueOf(data.getPermanentTokens());

            String cooldown;
            if (player.hasPermission("velocitycore.joinme.cooldown.bypass")) {
                cooldown = msg("tokens.cooldown.bypassed");
            } else if (cooldownSeconds > 0) {
                cooldown = formatDuration(cooldownSeconds);
            } else {
                cooldown = msg("tokens.cooldown.ready");
            }

            CompatibilityHelper.sendMessage(player, CompatibilityHelper.colorize(
                    msg("tokens.info"),
                    "status", status,
                    "total", total,
                    "monthly", monthly,
                    "permanent", permanent,
                    "cooldown", cooldown
            ));

            String storeLink = msg("tokens.store-link");
            if (!storeLink.isEmpty()) {
                CompatibilityHelper.sendMessage(player, CompatibilityHelper.colorize(storeLink));
            }
        });
    }

    private String formatDuration(long seconds) {
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        long remainingSeconds = seconds - TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.tokens.view");
    }
}
