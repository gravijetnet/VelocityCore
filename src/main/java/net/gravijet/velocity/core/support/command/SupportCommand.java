package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SupportCommand implements SimpleCommand {
    private final SupportPlugin plugin;
    private final ConfigManager configManager;
    private final SupportManager manager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SupportCommand(SupportPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.manager = plugin.getManager();
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            inv.source().sendMessage(getMessage("general.players-only"));
            return;
        }

        String[] args = inv.arguments();
        String lang = "en"; // Default language

        if (args.length == 0) {
            showMenu(player);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> showMenu(player);

            case "de", "en" -> {
                if (isHelp(args, 1)) {
                    sendHelp(player, "/support " + args[0], "Open a support request in " + (args[0].equalsIgnoreCase("de") ? "German." : "English."));
                    return;
                }
                if (manager.canRequestSupport(player, args[0])) {
                    manager.createSupportRequest(player, args[0]);
                }
            }

            case "chat" -> {
                if (isHelp(args, 1) || args.length < 2) {
                    sendHelp(player, "/support chat <message>", "Send a message in your session.");
                    return;
                }
                manager.handleSupportChat(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }

            case "rate" -> {
                if (isHelp(args, 1) || args.length < 2) {
                    sendHelp(player, "/support rate <1-5>", "Rate your support session.");
                    return;
                }
                try {
                    // manager.rateSupport(player, Integer.parseInt(args[1])); // This method needs to be refactored in SupportManager
                    player.sendMessage(Component.text("Rating is temporarily disabled."));
                } catch (NumberFormatException e) {
                    player.sendMessage(getMessage("support.invalid-rating"));
                }
            }

            case "claim" -> staff(player, args, "support.claim", 2,
                    "/support claim <player> [--force]", "Claim a support request.", () -> {
                        boolean force = args.length > 2 && "--force".equalsIgnoreCase(args[2]);
                        if (force && !perm(player, "support.force")) {
                            player.sendMessage(getMessage("general.no-permission"));
                            return;
                        }
                        manager.claimSupport(player, args[1], force);
                    });

            case "close" -> staff(player, args, "support.close", 1,
                    "/support close", "Close the current session.",
                    () -> {
                        // manager.closeSupportSession(player); // This method needs to be refactored in SupportManager
                        player.sendMessage(Component.text("Closing is temporarily disabled."));
                    });

            default -> player.sendMessage(getMessage("support.invalid-command"));
        }
    }

    private void staff(Player player, String[] args, String permission, int minArgs,
                       String cmd, String desc, Runnable action) {
        if (isHelp(args, 1)) {
            sendHelp(player, cmd, desc);
            return;
        }
        if (!perm(player, permission)) {
            player.sendMessage(getMessage("general.no-permission"));
            return;
        }
        if (args.length < minArgs) {
            sendHelp(player, cmd, desc);
            return;
        }
        action.run();
    }

    private boolean isHelp(String[] args, int idx) {
        return args.length > idx && "help".equalsIgnoreCase(args[idx]);
    }

    private void sendHelp(Player player, String cmd, String desc) {
        player.sendMessage(miniMessage.deserialize("<#ff0000>● <red>" + cmd + " <dark_gray>» <white>" + desc));
    }

    private boolean perm(Player p, String permission) {
        String basePerm = configManager.getConfig().node("support", "staff-permission").getString("velocitycore.support.staff");
        return p.hasPermission(basePerm + "." + permission) || p.hasPermission(basePerm + ".*");
    }

    private void showMenu(Player player) {
        String key = perm(player, "view") ? "support.staff-help" : "support.player-help";
        ConfigurationNode helpNode = configManager.getMessages().node(key.split("\\."));
        if (helpNode.isList()) {
            helpNode.childrenList().stream()
                    .map(ConfigurationNode::getString)
                    .forEach(line -> player.sendMessage(miniMessage.deserialize(line)));
        }
    }
    
    private Component getMessage(String path, TagResolver... resolvers) {
        String template = configManager.getMessages().node(path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message for " + path + " not found.").color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return miniMessage.deserialize(template, resolvers);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation inv) {
        if (inv.source() instanceof Player p && inv.arguments().length <= 1) {
            if (perm(p, "view")) {
                return CompletableFuture.completedFuture(List.of(
                        "help", "claim", "close", "transfer", "show", "setlanguage", "ban", "unban", "link", "unlink", "de", "en"));
            }
            return CompletableFuture.completedFuture(List.of("help", "de", "en", "chat", "rate"));
        }
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation inv) {
        return true;
    }
}
