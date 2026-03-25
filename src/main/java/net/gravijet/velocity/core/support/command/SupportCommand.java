package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.config.SupportConfig;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SupportCommand implements SimpleCommand {
    private final SupportPlugin plugin;
    private final SupportConfig config;
    private final SupportManager manager;
    private final LegacyComponentSerializer serial = LegacyComponentSerializer.legacyAmpersand();

    public SupportCommand(SupportPlugin plugin) {
        this.plugin  = plugin;
        this.config  = plugin.getConfig();
        this.manager = plugin.getManager();
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            inv.source().sendMessage(Component.text("This command can only be used by players."));
            return;
        }

        String[] args = inv.arguments();
        String lang = manager.getPlayerLanguage(player);

        if (args.length == 0) { showMenu(player); return; }

        switch (args[0].toLowerCase()) {
            case "help" -> showMenu(player);

            case "de", "en" -> {
                if (isHelp(args, 1)) {
                    sendHelp(player, "/support " + args[0],
                            args[0].equalsIgnoreCase("de") ? "Open a support request in German."
                                                           : "Open a support request in English.");
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
                    manager.rateSupport(player, Integer.parseInt(args[1]));
                } catch (NumberFormatException e) {
                    player.sendMessage(serial.deserialize(config.getInvalidRating(lang)));
                }
            }

            case "claim" -> staff(player, args, config.getPermissions().supportClaim, 2,
                    "/support claim <player> [--force]", "Claim a support request.", () -> {
                        boolean force = args.length > 2 && "--force".equalsIgnoreCase(args[2]);
                        if (force && !perm(player, config.getPermissions().supportForce)) {
                            player.sendMessage(serial.deserialize(config.getNoPermission("en")));
                            return;
                        }
                        manager.claimSupport(player, args[1], force);
                    });

            case "close" -> staff(player, args, config.getPermissions().supportClose, 1,
                    "/support close", "Close the current session.",
                    () -> manager.closeSupportSession(player));

            case "transfer" -> staff(player, args, config.getPermissions().supportTransfer, 2,
                    "/support transfer <staff>", "Transfer the session to another staff member.",
                    () -> manager.transferSupport(player, args[1]));

            case "show" -> staff(player, args, config.getPermissions().supportShow, 2,
                    "/support show <player>", "View session details.",
                    () -> manager.showSupportSession(player, args[1]));

            case "setlanguage" -> staff(player, args, config.getPermissions().supportSetLanguage, 2,
                    "/support setlanguage <de|en>", "Change the session language.",
                    () -> manager.setSessionLanguage(player, args[1]));

            case "ban" -> staff(player, args, config.getPermissions().supportBan, 2,
                    "/support ban <player> [duration]", "Ban a player from support.",
                    () -> manager.banPlayer(player, args[1], args.length > 2 ? args[2] : "perm"));

            case "unban" -> staff(player, args, config.getPermissions().supportUnban, 2,
                    "/support unban <player>", "Unban a player from support.",
                    () -> manager.unbanPlayer(player, args[1]));

            case "link" -> staff(player, args, config.getPermissions().supportLink, 3,
                    "/support link <player> <discordId>", "Link a Minecraft player to their Discord account.",
                    () -> manager.linkAccount(args[1], args[2], player));

            case "unlink" -> staff(player, args, config.getPermissions().supportLink, 2,
                    "/support unlink <player>", "Remove a Discord link from a Minecraft player.",
                    () -> manager.unlinkAccount(args[1], player));

            default -> player.sendMessage(serial.deserialize(config.getInvalidLanguage()));
        }
    }

    private void staff(Player player, String[] args, String permission, int minArgs,
                       String cmd, String desc, Runnable action) {
        if (isHelp(args, 1)) { sendHelp(player, cmd, desc); return; }
        if (!perm(player, permission)) {
            player.sendMessage(serial.deserialize(config.getNoPermission("en")));
            return;
        }
        if (args.length < minArgs) { sendHelp(player, cmd, desc); return; }
        action.run();
    }

    private boolean isHelp(String[] args, int idx) {
        return args.length > idx && "help".equalsIgnoreCase(args[idx]);
    }

    private void sendHelp(Player player, String cmd, String desc) {
        player.sendMessage(serial.deserialize("&4● &c" + cmd + " &7» &f" + desc));
    }

    private boolean perm(Player p, String permission) {
        return p.hasPermission(permission) || p.hasPermission("support.*");
    }

    private void showMenu(Player player) {
        if (perm(player, config.getPermissions().supportAll)) showStaffHelp(player);
        else config.getMainCommandMessage().forEach(l -> player.sendMessage(serial.deserialize(l)));
    }

    private void showStaffHelp(Player player) {
        config.getStaffHelpMessage().forEach(l -> player.sendMessage(serial.deserialize(l)));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation inv) {
        if (inv.source() instanceof Player p && inv.arguments().length <= 1) {
            if (perm(p, config.getPermissions().supportAll)) {
                return CompletableFuture.completedFuture(List.of(
                        "help", "claim", "close", "transfer", "show", "setlanguage", "ban", "unban", "link", "unlink", "de", "en"));
            }
            return CompletableFuture.completedFuture(List.of("help", "de", "en", "chat", "rate"));
        }
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation inv) { return true; }
}
