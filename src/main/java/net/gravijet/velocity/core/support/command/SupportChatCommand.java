package net.gravijet.velocity.core.support.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public class SupportChatCommand implements SimpleCommand {

    private final SupportManager manager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SupportChatCommand(SupportPlugin plugin) {
        this.manager = plugin.getManager();
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public void execute(Invocation inv) {
        if (!(inv.source() instanceof Player player)) {
            CompatibilityHelper.sendMessage(inv.source(), getMessage("general.players-only"));
            return;
        }

        String[] args = inv.arguments();
        if (args.length == 0) {
            CompatibilityHelper.sendMessage(player, getMessage("support.chat-usage"));
            return;
        }

        manager.handleSupportChat(player, String.join(" ", args));
    }

    private Component getMessage(String path, TagResolver... resolvers) {
        String template = configManager.getMessages().node(path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message for " + path + " not found.").color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return miniMessage.deserialize(template, resolvers);
    }
}
