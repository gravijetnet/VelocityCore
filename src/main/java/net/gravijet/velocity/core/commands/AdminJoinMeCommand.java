package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class AdminJoinMeCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer proxy;
    private final TokenManager tokenManager;
    private final JoinMeManager joinMeManager;
    private final ConfigManager config;

    public AdminJoinMeCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager config) {
        this.proxy = proxy;
        this.tokenManager = tokenManager;
        this.joinMeManager = joinMeManager;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "tokens" -> {
                if (args.length >= 2) handleTokens(source, args[1]);
                else send(source, "&cUsage: /adminjoinme tokens <player>");
            }
            case "forcejoinme" -> {
                if (args.length >= 2) {
                    if (!source.hasPermission("core.joinme.admin.force")) { send(source, config.getString("messages.no_permission", "&cYou do not have permission.")); return; }
                    handleForceJoinMe(source, args[1]);
                } else {
                    send(source, "&cUsage: /adminjoinme forcejoinme <player>");
                }
            }
            case "addtokens" -> {
                if (args.length >= 3) {
                    if (!source.hasPermission("core.joinme.admin.tokens.manage")) { send(source, config.getString("messages.no_permission", "&cYou do not have permission.")); return; }
                    handleAddTokens(source, args[1], args[2]);
                } else {
                    send(source, "&cUsage: /adminjoinme addtokens <player> <amount>");
                }
            }
            case "removetokens" -> {
                if (args.length >= 3) {
                    if (!source.hasPermission("core.joinme.admin.tokens.manage")) { send(source, config.getString("messages.no_permission", "&cYou do not have permission.")); return; }
                    handleRemoveTokens(source, args[1], args[2]);
                } else {
                    send(source, "&cUsage: /adminjoinme removetokens <player> <amount>");
                }
            }
            default -> sendHelp(source);
        }
    }

    private void handleTokens(CommandSource source, String playerName) {
        if (!source.hasPermission("core.joinme.admin.tokens.view")) {
            send(source, config.getString("messages.no_permission", "&cYou do not have permission."));
            return;
        }
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) {
            send(source, config.getString("messages.player_not_found", "&cPlayer {player} not found.").replace("{player}", playerName));
            return;
        }
        tokenManager.getPlayerData(opt.get().getUniqueId()).thenAccept(data -> {
            if (data == null) { send(source, config.getString("messages.tokens.error", "&cCould not retrieve token data.")); return; }
            boolean unlimited = tokenManager.hasUnlimitedTokens(opt.get().getUniqueId());
            String header = config.getString("messages.tokens.admin_header", "&c&l{player}'s Tokens").replace("{player}", playerName);
            String info = "&7Monthly&8:    &f" + data.getMonthlyTokens() + "\n" +
                          "&7Permanent&8:  &f" + data.getPermanentTokens() + "\n" +
                          "&7Total&8:      &f" + data.getTotalTokens() + "\n" +
                          "&7Unlimited&8:  &f" + (unlimited ? "Yes" : "No");
            source.sendMessage(LEGACY.deserialize(header + "\n" + info));
        });
    }

    private void handleForceJoinMe(CommandSource source, String playerName) {
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) { send(source, config.getString("messages.player_not_found", "&cPlayer {player} not found.").replace("{player}", playerName)); return; }
        joinMeManager.forceJoinMe(opt.get());
        send(source, config.getString("messages.joinme.admin.force_success", "&aForced a JoinMe for {player}.").replace("{player}", playerName));
        send(opt.get(), config.getString("messages.joinme.admin.force_received", "&aAn admin has created a JoinMe for you."));
    }

    private void handleAddTokens(CommandSource source, String playerName, String amountStr) {
        int amount = parseAmount(source, amountStr);
        if (amount < 0) return;
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) { send(source, config.getString("messages.player_not_found", "&cPlayer {player} not found.").replace("{player}", playerName)); return; }
        tokenManager.addPermanentTokens(opt.get().getUniqueId(), amount).thenAccept(success -> {
            if (success) {
                send(source, config.getString("messages.tokens.admin.add_success", "&aAdded {amount} tokens to {player}.").replace("{amount}", String.valueOf(amount)).replace("{player}", playerName));
                send(opt.get(), config.getString("messages.tokens.admin.add_received", "&aYou received {amount} tokens.").replace("{amount}", String.valueOf(amount)));
            } else {
                send(source, config.getString("messages.tokens.admin.add_failed", "&cFailed to add tokens."));
            }
        });
    }

    private void handleRemoveTokens(CommandSource source, String playerName, String amountStr) {
        int amount = parseAmount(source, amountStr);
        if (amount < 0) return;
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) { send(source, config.getString("messages.player_not_found", "&cPlayer {player} not found.").replace("{player}", playerName)); return; }
        tokenManager.removePermanentTokens(opt.get().getUniqueId(), amount).thenAccept(success -> {
            if (success) {
                send(source, config.getString("messages.tokens.admin.remove_success", "&aRemoved {amount} tokens from {player}.").replace("{amount}", String.valueOf(amount)).replace("{player}", playerName));
                send(opt.get(), config.getString("messages.tokens.admin.remove_received", "&a{amount} tokens were removed from your account.").replace("{amount}", String.valueOf(amount)));
            } else {
                send(source, config.getString("messages.tokens.admin.remove_failed", "&cFailed to remove tokens."));
            }
        });
    }

    /** Returns the parsed amount, or -1 on error (error message already sent). */
    private int parseAmount(CommandSource source, String raw) {
        try {
            long val = Long.parseLong(raw);
            if (val <= 0) { send(source, config.getString("messages.tokens.invalid_amount", "&cInvalid amount.")); return -1; }
            if (val > Integer.MAX_VALUE) {
                send(source, config.getString("messages.tokens.amount_clamped", "&cAmount too large, setting to max."));
                return Integer.MAX_VALUE;
            }
            return (int) val;
        } catch (NumberFormatException e) {
            send(source, config.getString("messages.tokens.invalid_amount", "&cInvalid amount."));
            return -1;
        }
    }

    private void sendHelp(CommandSource source) {
        List<String> helpLines = config.getStringList("messages.joinme.admin.help", List.of("&cAdminJoinMe Help:", "/ajm tokens <player>", "/ajm forcejoinme <player>", "/ajm addtokens <player> <amount>", "/ajm removetokens <player> <amount>"));
        for (String line : helpLines) {
            source.sendMessage(LEGACY.deserialize(line));
        }
    }

    private void send(CommandSource source, String message) {
        source.sendMessage(LEGACY.deserialize(message));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission("core.joinme.admin")) return List.of();
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return Stream.of("tokens", "forcejoinme", "addtokens", "removetokens")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        } else if (args.length == 2) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("core.joinme.admin");
    }
}
