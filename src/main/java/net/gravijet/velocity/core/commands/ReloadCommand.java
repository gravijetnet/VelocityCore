package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.SimpleCommand;
import net.gravijet.velocity.core.Main;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class ReloadCommand implements SimpleCommand {

    private final Main plugin;

    public ReloadCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.admin")) {
            CompatibilityHelper.sendMessage(invocation.source(), MiniMessage.miniMessage().deserialize("<red>You do not have permission to use this command.</red>"));
            return;
        }

        try {
            plugin.reload(); // Changed to call reload()
            Component successMessage = MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessages().node("reload", "success").getString());
            CompatibilityHelper.sendMessage(invocation.source(), successMessage);
        } catch (Exception e) {
            Component failureMessage = MiniMessage.miniMessage().deserialize(plugin.getConfigManager().getMessages().node("reload", "failure").getString());
            CompatibilityHelper.sendMessage(invocation.source(), failureMessage);
        }
    }
}