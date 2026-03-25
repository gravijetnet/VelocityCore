package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.config.CoreConfig;
import net.gravijet.velocity.core.managers.TokenManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class TokensCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TokenManager tokenManager;
    private final CoreConfig config;

    public TokensCommand(ProxyServer proxy, TokenManager tokenManager, CoreConfig config) {
        this.tokenManager = tokenManager;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(LEGACY.deserialize(config.get().playersOnly));
            return;
        }
        if (!player.hasPermission("core.joinme.tokens.view")) {
            player.sendMessage(LEGACY.deserialize(config.get().noPermission));
            return;
        }

        tokenManager.getPlayerData(player.getUniqueId()).thenAccept(data -> {
            if (data == null) {
                player.sendMessage(LEGACY.deserialize(config.get().tokensError));
                return;
            }

            CoreConfig.Messages msg = config.get();
            boolean unlimited = tokenManager.hasUnlimitedTokens(player.getUniqueId());

            String status    = unlimited ? msg.tokensStatusUnlimited
                             : data.getTotalTokens() > 0 ? msg.tokensStatusAvailable
                             : msg.tokensStatusEmpty;
            String monthly   = unlimited ? msg.tokensStatusUnlimited : String.valueOf(data.getMonthlyTokens());
            String total     = unlimited ? msg.tokensStatusUnlimited : String.valueOf(data.getTotalTokens());
            String permanent = unlimited ? msg.tokensStatusUnlimited : String.valueOf(data.getPermanentTokens());
            String cooldown  = player.hasPermission("core.joinme.cooldown.bypass")
                             ? msg.tokensCooldownBypassed : msg.tokensCooldownDefault;

            // Build message from config lines
            Component built = Component.empty();
            for (String line : msg.tokensInfo) {
                String resolved = line
                        .replace("{status}",    status)
                        .replace("{total}",     total)
                        .replace("{monthly}",   monthly)
                        .replace("{permanent}", permanent)
                        .replace("{cooldown}",  cooldown);
                if (!built.equals(Component.empty())) built = built.append(Component.newline());
                built = built.append(LEGACY.deserialize(resolved));
            }
            player.sendMessage(built);

            // Clickable store link
            Component link = LEGACY.deserialize(msg.tokensStoreLink)
                    .clickEvent(ClickEvent.openUrl("https://store.hexalon.net"));
            player.sendMessage(link);
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player p && p.hasPermission("core.joinme.tokens.view");
    }
}
