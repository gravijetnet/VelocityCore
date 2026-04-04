package net.gravijet.velocity.core.support;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.Main;
import net.gravijet.velocity.core.support.command.BugCommand;
import net.gravijet.velocity.core.support.command.SupportChatCommand;
import net.gravijet.velocity.core.support.command.SupportCommand;
import net.gravijet.velocity.core.support.discord.DiscordBot;
import net.gravijet.velocity.core.support.manager.SupportManager;
import net.gravijet.velocity.core.util.ConfigManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SupportPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Main core;
    private final ConfigManager configManager; // Use central ConfigManager
    private SupportManager manager;
    private DiscordBot discordBot;

    public SupportPlugin(ProxyServer server, Logger logger, Path dataDirectory, Main core) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.core = core;
        this.configManager = core.getConfigManager(); // Get ConfigManager from Main
        initDirectories();
    }

    public void onProxyInit() {
        // Check if support system is enabled
        if (!configManager.getConfig().node("support", "enabled").getBoolean(false)) {
            logger.info("Support Plugin is disabled in config.yml.");
            return;
        }

        try {
            manager    = new SupportManager(this, configManager); // Pass ConfigManager
            discordBot = new DiscordBot(this, configManager, manager); // Pass ConfigManager

            var supportMeta = server.getCommandManager().metaBuilder("support").aliases("help").build();
            server.getCommandManager().register(supportMeta, new SupportCommand(this));

            var spcMeta = server.getCommandManager().metaBuilder("spc").build();
            server.getCommandManager().register(spcMeta, new SupportChatCommand(this));

            var bugMeta = server.getCommandManager().metaBuilder("bug").build();
            server.getCommandManager().register(bugMeta, new BugCommand(this));

            // Only start Discord bot if enabled in config
            if (configManager.getConfig().node("support", "discord", "enabled").getBoolean(false)) {
                discordBot.start();
            }

            logger.info("Support Plugin enabled.");
        } catch (Exception e) {
            logger.error("Failed to initialize Support Plugin.", e);
        }
    }

    public void onProxyShutdown() {
        if (manager    != null) manager.shutdown();
        if (discordBot != null) discordBot.stop();
    }

    public void handlePlayerJoin(com.velocitypowered.api.proxy.Player player) {
        if (manager != null) manager.handlePlayerJoin(player);
    }

    public void handlePlayerLeave(com.velocitypowered.api.proxy.Player player) {
        if (player == null) return;
        if (manager != null) manager.handlePlayerLeave(player);
    }

    private void initDirectories() {
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(dataDirectory.resolve("logs"));
        } catch (IOException e) {
            logger.error("Failed to create plugin directories.", e);
        }
    }

    public ProxyServer getServer()      { return server; }
    public Logger getLogger()           { return logger; }
    public Path getDataDirectory()      { return dataDirectory; }
    public Main getCorePlugin()         { return core; }
    public ConfigManager getConfigManager() { return configManager; } // New getter for ConfigManager
    public SupportManager getManager()  { return manager; }
    public DiscordBot getDiscordBot()   { return discordBot; }
}
