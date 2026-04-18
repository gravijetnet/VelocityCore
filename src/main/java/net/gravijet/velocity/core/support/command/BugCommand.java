package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.util.CompatibilityHelper;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BugCommand implements SimpleCommand {
    private final SupportPlugin plugin;

    public BugCommand(SupportPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            CompatibilityHelper.sendMessage(inv.source(),
                    CompatibilityHelper.colorize(msg("general.players-only")));
            return;
        }

        String[] args = inv.arguments();

        if (args.length < 2) {
            CompatibilityHelper.sendMessage(player, CompatibilityHelper.colorize(msg("bug.usage")));
            return;
        }

        String title = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (title.length() > 100) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("bug.title-too-long")));
            return;
        }

        if (message.length() > 1000) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("bug.message-too-long")));
            return;
        }

        if (plugin.getDiscordBot() == null) {
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("bug.discord-not-available")));
            return;
        }

        String serverName = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("Unknown");

        plugin.getServer().getScheduler()
                .buildTask(plugin.getCorePlugin(), () -> {
                    boolean success = plugin.getDiscordBot().sendBugReport(player.getUsername(), serverName, title, message);
                    if (success) {
                        CompatibilityHelper.sendMessage(player,
                                CompatibilityHelper.colorize(msg("bug.report-sent"), "title", title));
                        plugin.getLogger().info("Bug report from {}: {} - {}", player.getUsername(), title, message);
                    } else {
                        CompatibilityHelper.sendMessage(player,
                                CompatibilityHelper.colorize(msg("bug.report-failed")));
                    }
                })
                .schedule();
    }

    private String msg(String path) {
        String val = plugin.getConfigManager().getMessages()
                .node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation inv) {
        String[] args = inv.arguments();
        if (args.length == 1) {
            return CompletableFuture.completedFuture(List.of("<title>"));
        }
        if (args.length == 2) {
            return CompletableFuture.completedFuture(List.of("<description>"));
        }
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation inv) {
        return true;
    }
}
