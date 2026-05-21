package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.SimpleCommand;
import net.gravijet.velocity.core.Main;
import net.gravijet.velocity.core.util.CompatibilityHelper;

import java.util.List;

public class ReloadCommand implements SimpleCommand {

    private final Main plugin;

    public ReloadCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.admin")) {
            return;
        }

        try {
            plugin.reload();
            String success = plugin.getConfigManager().getMessages()
                    .node("reload", "success").getString("<green>Configuration reloaded.");
            CompatibilityHelper.sendMessage(invocation.source(), CompatibilityHelper.colorize(success));
        } catch (Exception e) {
            String failure = plugin.getConfigManager().getMessages()
                    .node("reload", "failure").getString("<red>Failed to reload configuration.");
            CompatibilityHelper.sendMessage(invocation.source(), CompatibilityHelper.colorize(failure));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocitycore.admin");
    }
}
