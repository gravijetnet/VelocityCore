package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;

public class SupportChatCommand implements SimpleCommand {

    private final SupportManager manager;
    private final ConfigManager configManager;

    public SupportChatCommand(SupportPlugin plugin) {
        this.manager = plugin.getManager();
        this.configManager = plugin.getConfigManager();
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
            CompatibilityHelper.sendMessage(player,
                    CompatibilityHelper.colorize(msg("support.chat-usage")));
            return;
        }

        manager.handleSupportChat(player, String.join(" ", args));
    }

    private String msg(String path) {
        String val = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return val != null ? val : "";
    }
}
