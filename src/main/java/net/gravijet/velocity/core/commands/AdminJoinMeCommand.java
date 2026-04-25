package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.database.PlayerDataDAO;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


public class AdminJoinMeCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final TokenManager tokenManager;
    private final JoinMeManager joinMeManager;
    private final ConfigManager configManager;
    private final PlayerDataDAO playerDataDAO;

    public AdminJoinMeCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager configManager, PlayerDataDAO playerDataDAO) {
        this.proxy = proxy;
        this.tokenManager = tokenManager;
        this.joinMeManager = joinMeManager;
        this.configManager = configManager;
        this.playerDataDAO = playerDataDAO;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPermission(invocation)) {
            send(source, "general.no-permission");
            return;
        }

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "tokens" -> {
                if (args.length < 2) {
                    send(source, "<red>Usage: <white>/adminjoinme tokens <player>");
                    return;
                }
                handleTokens(source, args[1]);
            }
            case "forcejoinme" -> {
                if (args.length < 2) {
                    send(source, "<red>Usage: <white>/adminjoinme forcejoinme <player>");
                    return;
                }
                handleForceJoinMe(source, args[1]);
            }
            case "addtokens" -> {
                if (args.length < 3) {
                    send(source, "<red>Usage: <white>/adminjoinme addtokens <player> <amount>");
                    return;
                }
                handleAddTokens(source, args[1], args[2]);
            }
            case "removetokens" -> {
                if (args.length < 3) {
                    send(source, "<red>Usage: <white>/adminjoinme removetokens <player> <amount>");
                    return;
                }
                handleRemoveTokens(source, args[1], args[2]);
            }
            default -> sendHelp(source);
        }
    }

    private void handleTokens(CommandSource source, String playerName) {
        Player onlinePlayer = proxy.getPlayer(playerName).orElse(null);
        if (onlinePlayer != null) {
            tokenManager.getPlayerData(onlinePlayer.getUniqueId()).thenAccept(data -> {
                if (data == null) {
                    send(source, "admin-joinme.tokens-error");
                    return;
                }
                long cooldownSeconds = joinMeManager.getCooldown(onlinePlayer.getUniqueId());
                String cooldownFormatted = cooldownSeconds > 0 ? formatDuration(cooldownSeconds) : msg("tokens.cooldown.ready");
                String status = cooldownSeconds > 0 ? msg("tokens.status.empty") : msg("tokens.status.available");
                CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(
                        msg("joinme.status_format"),
                        "status", status,
                        "total", String.valueOf(data.getTotalTokens()),
                        "monthly", String.valueOf(data.getMonthlyTokens()),
                        "permanent", String.valueOf(data.getPermanentTokens()),
                        "cooldown", cooldownFormatted
                ));
            });
        } else {
            playerDataDAO.getPlayerDataByName(playerName).thenAccept(offlineData -> {
                if (offlineData == null) {
                    send(source, "general.player-not-found", "player", playerName);
                    return;
                }
                tokenManager.getPlayerData(offlineData.getUuid()).thenAccept(data -> {
                    if (data == null) {
                        send(source, "admin-joinme.tokens-error");
                        return;
                    }
                    String cooldownFormatted = msg("tokens.cooldown.ready");
                    String status = data.getTotalTokens() > 0 ? msg("tokens.status.available") : msg("tokens.status.empty");
                    CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(
                            msg("joinme.status_format"),
                            "status", status,
                            "total", String.valueOf(data.getTotalTokens()),
                            "monthly", String.valueOf(data.getMonthlyTokens()),
                            "permanent", String.valueOf(data.getPermanentTokens()),
                            "cooldown", cooldownFormatted
                    ));
                });
            });
        }
    }

    private void handleForceJoinMe(CommandSource source, String playerName) {
        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            joinMeManager.forceJoinMe(player);
            send(source, "admin-joinme.force-success", "player", player.getUsername());
            send(player, "admin-joinme.force-received");
        }, () -> send(source, "general.player-not-found", "player", playerName));
    }

    private void handleAddTokens(CommandSource source, String playerName, String amountStr) {
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                send(source, "general.invalid-amount");
                return;
            }
            Player onlinePlayer = proxy.getPlayer(playerName).orElse(null);
            if (onlinePlayer != null) {
                tokenManager.addPermanentTokens(onlinePlayer.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        send(source, "admin-joinme.add-success", "amount", String.valueOf(amount), "player", onlinePlayer.getUsername());
                        send(onlinePlayer, "admin-joinme.add-received", "amount", String.valueOf(amount));
                    } else {
                        send(source, "admin-joinme.add-failed");
                    }
                });
            } else {
                playerDataDAO.getPlayerDataByName(playerName).thenAccept(offlineData -> {
                    if (offlineData == null) {
                        send(source, "general.player-not-found", "player", playerName);
                        return;
                    }
                    tokenManager.addPermanentTokens(offlineData.getUuid(), amount).thenAccept(success -> {
                        if (success) {
                            send(source, "admin-joinme.add-success", "amount", String.valueOf(amount), "player", offlineData.getUsername());
                        } else {
                            send(source, "admin-joinme.add-failed");
                        }
                    });
                });
            }
        } catch (NumberFormatException e) {
            send(source, "general.invalid-amount");
        }
    }

    private void handleRemoveTokens(CommandSource source, String playerName, String amountStr) {
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                send(source, "general.invalid-amount");
                return;
            }
            Player onlinePlayer = proxy.getPlayer(playerName).orElse(null);
            if (onlinePlayer != null) {
                tokenManager.removePermanentTokens(onlinePlayer.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        send(source, "admin-joinme.remove-success", "amount", String.valueOf(amount), "player", onlinePlayer.getUsername());
                        send(onlinePlayer, "admin-joinme.remove-received", "amount", String.valueOf(amount));
                    } else {
                        send(source, "admin-joinme.remove-failed");
                    }
                });
            } else {
                playerDataDAO.getPlayerDataByName(playerName).thenAccept(offlineData -> {
                    if (offlineData == null) {
                        send(source, "general.player-not-found", "player", playerName);
                        return;
                    }
                    tokenManager.removePermanentTokens(offlineData.getUuid(), amount).thenAccept(success -> {
                        if (success) {
                            send(source, "admin-joinme.remove-success", "amount", String.valueOf(amount), "player", offlineData.getUsername());
                        } else {
                            send(source, "admin-joinme.remove-failed");
                        }
                    });
                });
            }
        } catch (NumberFormatException e) {
            send(source, "general.invalid-amount");
        }
    }

    private void sendHelp(CommandSource source) {
        configManager.getMessages().node("admin-joinme", "help").childrenList().stream()
                .map(ConfigurationNode::getString)
                .filter(Objects::nonNull)
                .forEach(line -> CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(line)));
    }

    private void send(CommandSource source, String path, String... pairs) {
        CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(msg(path), pairs));
    }

    private void send(Player player, String path, String... pairs) {
        CompatibilityHelper.sendMessage(player, CompatibilityHelper.colorize(msg(path), pairs));
    }

    // For sending raw strings (usage lines) or message keys
    private void send(CommandSource source, String rawOrPath) {
        String text = (rawOrPath.startsWith("&") || rawOrPath.startsWith("<")) ? rawOrPath : msg(rawOrPath);
        CompatibilityHelper.sendMessage(source, CompatibilityHelper.colorize(text));
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    private String formatDuration(long seconds) {
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        long remainingSeconds = seconds - TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasPermission(invocation)) return List.of();
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return Stream.of("tokens", "forcejoinme", "addtokens", "removetokens")
                    .filter(s -> s.startsWith(args.length == 1 ? args[0].toLowerCase() : ""))
                    .toList();
        }
        if (args.length == 2 && Stream.of("tokens", "forcejoinme", "addtokens", "removetokens")
                .anyMatch(s -> s.equalsIgnoreCase(args[0]))) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.admin.joinme");
    }
}
