package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.config.CoreConfig;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class JoinMeCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TokenManager tokenManager;
    private final JoinMeManager joinMeManager;
    private final CoreConfig config;

    public JoinMeCommand(com.velocitypowered.api.proxy.ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, CoreConfig config) {
        this.tokenManager = tokenManager;
        this.joinMeManager = joinMeManager;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(LEGACY.deserialize(config.get().playersOnly));
            return;
        }
        if (!player.hasPermission("core.joinme.use")) {
            player.sendMessage(LEGACY.deserialize(config.get().joinMeNoPerm));
            return;
        }
        long cooldownLeft = joinMeManager.getRemainingCooldown(player);
        if (cooldownLeft > 0 && !player.hasPermission("core.joinme.cooldown.bypass")) {
            int seconds = (int) (cooldownLeft / 1000);
            player.sendMessage(LEGACY.deserialize(config.get().joinMeCooldown.replace("{seconds}", String.valueOf(seconds))));
            return;
        }
        joinMeManager.sendJoinMe(player, false);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player p && p.hasPermission("core.joinme.use");
    }
}
