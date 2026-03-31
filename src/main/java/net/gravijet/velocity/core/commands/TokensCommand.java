package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public class TokensCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final TokenManager tokenManager;
    private final ConfigManager config;

    public TokensCommand(ProxyServer proxy, TokenManager tokenManager, ConfigManager config) {
        this.tokenManager = tokenManager;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(LEGACY.deserialize(config.getString("messages.players_only", "&cThis command can only be used by players.")));
            return;
        }
        if (!player.hasPermission("core.joinme.tokens.view")) {
            player.sendMessage(LEGACY.deserialize(config.getString("messages.no_permission", "&cYou do not have permission to use this command.")));
            return;
        }

        tokenManager.getPlayerData(player.getUniqueId()).thenAccept(data -> {
            if (data == null) {
                player.sendMessage(LEGACY.deserialize(config.getString("messages.tokens.error", "&cCould not retrieve token data.")));
                return;
            }

            boolean unlimited = tokenManager.hasUnlimitedTokens(player.getUniqueId());

            String status    = unlimited ? config.getString("messages.tokens.status_unlimited", "&aUnlimited")
                             : data.getTotalTokens() > 0 ? config.getString("messages.tokens.status_available", "&aAvailable")
                             : config.getString("messages.tokens.status_empty", "&cEmpty");
            String monthly   = unlimited ? config.getString("messages.tokens.unlimited_symbol", "∞") : String.valueOf(data.getMonthlyTokens());
            String total     = unlimited ? config.getString("messages.tokens.unlimited_symbol", "∞") : String.valueOf(data.getTotalTokens());
            String permanent = unlimited ? config.getString("messages.tokens.unlimited_symbol", "∞") : String.valueOf(data.getPermanentTokens());
            String cooldown  = player.hasPermission("core.joinme.cooldown.bypass")
                             ? config.getString("messages.tokens.cooldown_bypassed", "&aBypassed") : config.getString("messages.tokens.cooldown_default", "5 minutes");

            List<String> lines = config.getStringList("messages.tokens.info", List.of(
                "&c&lGraviJet &7» &f&lJoinMe Tokens",
                "&cStatus&8:    &f{status}",
                "&cTotal&8:     &f{total}",
                "&cMonthly&8:   &f{monthly} &8(&7resets monthly&8)",
                "&cPermanent&8: &f{permanent} &8(&7never expires&8)",
                "&cCooldown&8:  &f{cooldown}"
            ));

            Component built = Component.empty();
            for (String line : lines) {
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

            String storeLinkMessage = config.getString("messages.tokens.store_link_text", "&a&l[STORE] &fClick here to get more tokens!");
            String storeLinkUrl = config.getString("messages.tokens.store_link_url", "https://store.example.com");
            Component link = LEGACY.deserialize(storeLinkMessage)
                    .clickEvent(ClickEvent.openUrl(storeLinkUrl));
            player.sendMessage(link);
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player p && p.hasPermission("core.joinme.tokens.view");
    }
}
