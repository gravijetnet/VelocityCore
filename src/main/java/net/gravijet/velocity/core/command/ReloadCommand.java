package net.gravijet.velocity.core.command;

import com.velocitypowered.api.command.SimpleCommand;
import net.gravijet.velocity.core.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class ReloadCommand implements SimpleCommand {

    private final Main plugin;

    public ReloadCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("velocitycore.reload")) {
            String noPermMessage = plugin.getConfigManager().getString("no_permission_message", "&cYou do not have permission to use this command.");
            invocation.source().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(noPermMessage));
            return;
        }

        plugin.reloadConfig();
        String reloadMessage = plugin.getConfigManager().getString("reload_message", "&aVelocityCore has been reloaded!");
        invocation.source().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(reloadMessage));
    }
}
