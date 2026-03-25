package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.config.CoreConfig;
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
    private final CoreConfig config;

    public AdminJoinMeCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, CoreConfig config) {
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
                    if (!source.hasPermission("core.joinme.admin.force")) { send(source, config.get().noPermission); return; }
                    handleForceJoinMe(source, args[1]);
                } else {
                    send(source, "&cUsage: /adminjoinme forcejoinme <player>");
                }
            }
            case "addtokens" -> {
                if (args.length >= 3) {
                    if (!source.hasPermission("core.joinme.admin.tokens.manage")) { send(source, config.get().noPermission); return; }
                    handleAddTokens(source, args[1], args[2]);
                } else {
                    send(source, "&cUsage: /adminjoinme addtokens <player> <amount>");
                }
            }
            case "removetokens" -> {
                if (args.length >= 3) {
                    if (!source.hasPermission("core.joinme.admin.tokens.manage")) { send(source, config.get().noPermission); return; }
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
            send(source, config.get().noPermission);
            return;
        }
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) {
            send(source, config.get().playerNotFound.replace("{player}", playerName));
            return;
        }
        tokenManager.getPlayerData(opt.get().getUniqueId()).thenAccept(data -> {
            if (data == null) { send(source, config.get().tokensError); return; }
            boolean unlimited = tokenManager.hasUnlimitedTokens(opt.get().getUniqueId());
            String header = config.get().adminTokensHeader.replace("{player}", playerName);
            String info = "&7Monthly&8:    &f" + data.getMonthlyTokens() + "\n" +
                          "&7Permanent&8:  &f" + data.getPermanentTokens() + "\n" +
                          "&7Total&8:      &f" + data.getTotalTokens() + "\n" +
                          "&7Unlimited&8:  &f" + (unlimited ? "Yes" : "No");
            source.sendMessage(LEGACY.deserialize(header + "\n" + info));
        });
    }

    private void handleForceJoinMe(CommandSource source, String playerName) {
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) { send(source, config.get().playerNotFound.replace("{player}", playerName)); return; }
        joinMeManager.forceJoinMe(opt.get());
        send(source, config.get().adminForceSuccess.replace("{player}", playerName));
        send(opt.get(), config.get().adminForceReceived);
    }

    private void handleAddTokens(CommandSource source, String playerName, String amountStr) {
        int amount = parseAmount(source, amountStr);
        if (amount < 0) return;
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) { send(source, config.get().playerNotFound.replace("{player}", playerName)); return; }
        tokenManager.addPermanentTokens(opt.get().getUniqueId(), amount).thenAccept(success -> {
            if (success) {
                send(source, config.get().adminAddSuccess.replace("{amount}", String.valueOf(amount)).replace("{player}", playerName));
                send(opt.get(), config.get().adminAddReceived.replace("{amount}", String.valueOf(amount)));
            } else {
                send(source, config.get().adminAddFailed);
            }
        });
    }

    private void handleRemoveTokens(CommandSource source, String playerName, String amountStr) {
        int amount = parseAmount(source, amountStr);
        if (amount < 0) return;
        Optional<Player> opt = proxy.getPlayer(playerName);
        if (opt.isEmpty()) { send(source, config.get().playerNotFound.replace("{player}", playerName)); return; }
        tokenManager.removePermanentTokens(opt.get().getUniqueId(), amount).thenAccept(success -> {
            if (success) {
                send(source, config.get().adminRemoveSuccess.replace("{amount}", String.valueOf(amount)).replace("{player}", playerName));
                send(opt.get(), config.get().adminRemoveReceived.replace("{amount}", String.valueOf(amount)));
            } else {
                send(source, config.get().adminRemoveFailed);
            }
        });
    }

    /** Returns the parsed amount, or -1 on error (error message already sent). */
    private int parseAmount(CommandSource source, String raw) {
        try {
            long val = Long.parseLong(raw);
            if (val <= 0) { send(source, config.get().invalidAmount); return -1; }
            if (val > Integer.MAX_VALUE) {
                send(source, config.get().adminAmountClamped);
                return Integer.MAX_VALUE;
            }
            return (int) val;
        } catch (NumberFormatException e) {
            send(source, config.get().invalidAmount);
            return -1;
        }
    }

    private void sendHelp(CommandSource source) {
        StringBuilder sb = new StringBuilder();
        for (String line : config.get().adminJoinMeHelp) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line);
        }
        source.sendMessage(LEGACY.deserialize(sb.toString()));
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
