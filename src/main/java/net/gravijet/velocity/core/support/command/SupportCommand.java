package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class SupportCommand implements SimpleCommand {
    private final SupportPlugin plugin;
    private final ConfigManager configManager;
    private final SupportManager manager;

    public SupportCommand(SupportPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.manager = plugin.getManager();
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            CompatibilityHelper.sendMessage(inv.source(),
                    CompatibilityHelper.colorize(msg("general.players-only")));
            return;
        }

        String[] args = inv.arguments();

        if (args.length == 0) {
            showMenu(player);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> showMenu(player);

            case "de", "en" -> {
                if (isHelp(args, 1)) {
                    sendHelp(player, "/support " + args[0],
                            "Open a support request in " + (args[0].equalsIgnoreCase("de") ? "German." : "English."));
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
                    int rating = Integer.parseInt(args[1]);
                    if (rating < 1 || rating > 5) {
                        CompatibilityHelper.sendMessage(player,
                                CompatibilityHelper.colorize(msg("support.invalid-rating")));
                        return;
                    }
                    CompatibilityHelper.sendMessage(player,
                            CompatibilityHelper.colorize("&cRating is temporarily disabled."));
                } catch (NumberFormatException e) {
                    CompatibilityHelper.sendMessage(player,
                            CompatibilityHelper.colorize(msg("support.invalid-rating")));
                }
            }

            case "claim" -> staff(player, args, "support.claim", 2,
                    "/support claim <player> [--force]", "Claim a support request.", () -> {
                        boolean force = args.length > 2 && "--force".equalsIgnoreCase(args[2]);
                        if (force && !perm(player, "support.force")) {
                            CompatibilityHelper.sendMessage(player,
                                    CompatibilityHelper.colorize(msg("general.no-permission")));
                            return;
                        }
                        manager.claimSupport(player, args[1], force);
                    });

            case "close" -> staff(player, args, "support.close", 1,
                    "/support close", "Close the current session.",
                    () -> CompatibilityHelper.sendMessage(player,
                            CompatibilityHelper.colorize("&cClosing is temporarily disabled.")));

            default -> CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("support.invalid-command")));
        }
    }

    private void staff(Player player, String[] args, String permission, int minArgs,
                       String cmd, String desc, Runnable action) {
        if (isHelp(args, 1)) {
            sendHelp(player, cmd, desc);
            return;
        }
        if (!perm(player, permission)) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("general.no-permission")));
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
        CompatibilityHelper.sendMessage(player,
                CompatibilityHelper.colorize("&c" + cmd + " &8\u00bb &f" + desc));
    }

    private boolean perm(Player p, String permission) {
        String basePerm = configManager.getConfig()
                .node("support", "staff-permission").getString("velocitycore.support.staff");
        return p.hasPermission(basePerm + "." + permission) || p.hasPermission(basePerm + ".*");
    }

    private void showMenu(Player player) {
        String key = perm(player, "view") ? "support.staff-help" : "support.player-help";
        ConfigurationNode helpNode = configManager.getMessages().node((Object[]) key.split("\\."));
        if (helpNode.isList()) {
            helpNode.childrenList().stream()
                    .map(ConfigurationNode::getString)
                    .filter(Objects::nonNull)
                    .forEach(line -> CompatibilityHelper.sendMessage(player,
                            CompatibilityHelper.colorize(line)));
        }
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation inv) {
        if (inv.source() instanceof Player p && inv.arguments().length <= 1) {
            if (perm(p, "view")) {
                return CompletableFuture.completedFuture(List.of(
                        "help", "claim", "close", "transfer", "de", "en", "chat", "rate"));
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
