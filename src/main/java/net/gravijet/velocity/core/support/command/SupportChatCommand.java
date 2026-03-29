package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class SupportChatCommand implements SimpleCommand {

    private final SupportManager manager;
    private final LegacyComponentSerializer serial = LegacyComponentSerializer.legacyAmpersand();

    public SupportChatCommand(SupportPlugin plugin) {
        this.manager = plugin.getManager();
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            inv.source().sendMessage(Component.text("This command can only be used by players."));
            return;
        }

        String[] args = inv.arguments();
        if (args.length == 0) {
            player.sendMessage(serial.deserialize("&cUsage: /spc <message>"));
            return;
        }

        manager.handleSupportChat(player, String.join(" ", args));
    }
}
