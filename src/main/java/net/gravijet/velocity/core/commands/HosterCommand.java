package net.gravijet.velocity.core.commands;

import com.velocitypowered.api.command.SimpleCommand;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;

public class HosterCommand implements SimpleCommand {

    private final ConfigManager configManager;

    public HosterCommand(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public void execute(Invocation invocation) {
        String text = configManager.getMessages()
                .node("hoster", "message")
                .getString("<red>example.invalid <white>is powered by <red>Index-Hosting.com<white>.\n <gray>Get 10% off with code <dark_purple>GRAVI<white>.");
        for (String line : text.split("\n")) {
            CompatibilityHelper.sendMessage(invocation.source(), CompatibilityHelper.colorize(line));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
