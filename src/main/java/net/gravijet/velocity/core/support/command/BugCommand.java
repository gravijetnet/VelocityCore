package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BugCommand implements SimpleCommand {
    private final SupportPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public BugCommand(SupportPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            CompatibilityHelper.sendMessage(inv.source(), getMessage("general.players-only"));
            return;
        }

        String[] args = inv.arguments();
        
        if (args.length < 2) {
            CompatibilityHelper.sendMessage(player, getMessage("bug.usage"));
            return;
        }

        String title = args[0];
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        
        // Validate title length
        if (title.length() > 100) {
            CompatibilityHelper.sendMessage(player, getMessage("bug.title-too-long"));
            return;
        }
        
        // Validate message length
        if (message.length() > 1000) {
            CompatibilityHelper.sendMessage(player, getMessage("bug.message-too-long"));
            return;
        }
        
        // Check if Discord bot is available
        if (plugin.getDiscordBot() == null) {
            CompatibilityHelper.sendMessage(player, getMessage("bug.discord-not-available"));
            return;
        }
        
        // Get player's current server
        String serverName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse("Unknown");

        // Send bug report to Discord
        plugin.getDiscordBot().sendBugReport(player.getUsername(), serverName, title, message);

        // Confirm to player
        CompatibilityHelper.sendMessage(player, getMessage("bug.report-sent", 
            Placeholder.unparsed("title", title)));
        
        // Log
        plugin.getLogger().info("Bug report from {}: {} - {}", 
            player.getUsername(), title, message);
    }
    
    private Component getMessage(String path, TagResolver... resolvers) {
        String template = plugin.getConfigManager().getMessages().node(path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message for " + path + " not found.").color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return miniMessage.deserialize(template, resolvers);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation inv) {
        // No suggestions needed for bug command
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation inv) {
        // Everyone can report bugs
        return true;
    }
}