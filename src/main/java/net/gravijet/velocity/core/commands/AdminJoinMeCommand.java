package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AdminJoinMeCommand(ProxyServer proxy, TokenManager tokenManager, JoinMeManager joinMeManager, ConfigManager configManager) {
        this.proxy = proxy;
        this.tokenManager = tokenManager;
        this.joinMeManager = joinMeManager;
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!hasPermission(invocation)) {
            sendMessage(source, "general.no-permission");
            return;
        }

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "tokens" -> {
                if (args.length < 2) {
                    sendMessage(source, "<red>Usage: /adminjoinme tokens <player>");
                    return;
                }
                handleTokens(source, args[1]);
            }
            case "forcejoinme" -> {
                if (args.length < 2) {
                    sendMessage(source, "<red>Usage: /adminjoinme forcejoinme <player>");
                    return;
                }
                handleForceJoinMe(source, args[1]);
            }
            case "addtokens" -> {
                if (args.length < 3) {
                    sendMessage(source, "<red>Usage: /adminjoinme addtokens <player> <amount>");
                    return;
                }
                handleAddTokens(source, args[1], args[2]);
            }
            case "removetokens" -> {
                if (args.length < 3) {
                    sendMessage(source, "<red>Usage: /adminjoinme removetokens <player> <amount>");
                    return;
                }
                handleRemoveTokens(source, args[1], args[2]);
            }
            default -> sendHelp(source);
        }
    }

    private void handleTokens(CommandSource source, String playerName) {
        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            tokenManager.getPlayerData(player.getUniqueId()).thenAccept(data -> {
                if (data == null) {
                    sendMessage(source, "admin-joinme.tokens-error");
                    return;
                }

                long cooldownSeconds = joinMeManager.getCooldown(player.getUniqueId());
                String cooldownFormatted = cooldownSeconds > 0 ? formatDuration(cooldownSeconds) : "Ready";

                sendMessage(source, getMessage("joinme.status_format",
                        Placeholder.unparsed("status", cooldownSeconds > 0 ? "<red>Cooldown</red>" : "<green>Ready</green>"),
                        Placeholder.unparsed("total", String.valueOf(data.getTotalTokens())),
                        Placeholder.unparsed("monthly", String.valueOf(data.getMonthlyTokens())),
                        Placeholder.unparsed("permanent", String.valueOf(data.getPermanentTokens())),
                        Placeholder.unparsed("cooldown", cooldownFormatted)
                ));
            });
        }, () -> sendMessage(source, "general.player-not-found", Placeholder.unparsed("player", playerName)));
    }

    private void handleForceJoinMe(CommandSource source, String playerName) {
        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            joinMeManager.forceJoinMe(player);
            sendMessage(source, "admin-joinme.force-success", Placeholder.unparsed("player", player.getUsername()));
            sendMessage(player, "admin-joinme.force-received");
        }, () -> sendMessage(source, "general.player-not-found", Placeholder.unparsed("player", playerName)));
    }

    private void handleAddTokens(CommandSource source, String playerName, String amountStr) {
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                sendMessage(source, "general.invalid-amount");
                return;
            }
            proxy.getPlayer(playerName).ifPresentOrElse(player -> {
                tokenManager.addPermanentTokens(player.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        sendMessage(source, "admin-joinme.add-success", Placeholder.unparsed("amount", String.valueOf(amount)), Placeholder.unparsed("player", player.getUsername()));
                        sendMessage(player, "admin-joinme.add-received", Placeholder.unparsed("amount", String.valueOf(amount)));
                    } else {
                        sendMessage(source, "admin-joinme.add-failed");
                    }
                });
            }, () -> sendMessage(source, "general.player-not-found", Placeholder.unparsed("player", playerName)));
        } catch (NumberFormatException e) {
            sendMessage(source, "general.invalid-amount");
        }
    }

    private void handleRemoveTokens(CommandSource source, String playerName, String amountStr) {
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                sendMessage(source, "general.invalid-amount");
                return;
            }
            proxy.getPlayer(playerName).ifPresentOrElse(player -> {
                tokenManager.removePermanentTokens(player.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        sendMessage(source, "admin-joinme.remove-success", Placeholder.unparsed("amount", String.valueOf(amount)), Placeholder.unparsed("player", player.getUsername()));
                        sendMessage(player, "admin-joinme.remove-received", Placeholder.unparsed("amount", String.valueOf(amount)));
                    } else {
                        sendMessage(source, "admin-joinme.remove-failed");
                    }
                });
            }, () -> sendMessage(source, "general.player-not-found", Placeholder.unparsed("player", playerName)));
        } catch (NumberFormatException e) {
            sendMessage(source, "general.invalid-amount");
        }
    }

    private void sendHelp(CommandSource source) {
        configManager.getMessages().node("admin-joinme", "help").childrenList().stream()
                .map(ConfigurationNode::getString)
                .filter(Objects::nonNull)
                .forEach(line -> source.sendMessage(miniMessage.deserialize(line)));
    }

    private void sendMessage(CommandSource source, String message) {
        source.sendMessage(miniMessage.deserialize(message));
    }
    
    private void sendMessage(CommandSource source, Component component) {
        source.sendMessage(component);
    }

    private void sendMessage(CommandSource source, String messagePath, TagResolver... resolvers) {
        source.sendMessage(getMessage(messagePath, resolvers));
    }

    private void sendMessage(Player player, String messagePath, TagResolver... resolvers) {
        player.sendMessage(getMessage(messagePath, resolvers));
    }
    
    private Component getMessage(String path, TagResolver... resolvers) {
        String template = configManager.getMessages().node(path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message not found for path: " + path).color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return miniMessage.deserialize(template, resolvers);
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
                    .filter(s -> s.toLowerCase().startsWith(args.length == 1 ? args[0].toLowerCase() : ""))
                    .toList();
        }
        if (args.length == 2 && Stream.of("tokens", "forcejoinme", "addtokens", "removetokens").anyMatch(s -> s.equalsIgnoreCase(args[0]))) {
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