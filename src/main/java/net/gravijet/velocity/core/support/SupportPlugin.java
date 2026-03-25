package net.gravijet.velocity.core.support;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.Core;
import net.gravijet.velocity.core.support.command.SupportCommand;
import net.gravijet.velocity.core.support.config.SupportConfig;
import net.gravijet.velocity.core.support.discord.DiscordBot;
import net.gravijet.velocity.core.support.manager.SupportManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SupportPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Core core;
    private SupportConfig config;
    private SupportManager manager;
    private DiscordBot discordBot;

    public SupportPlugin(ProxyServer server, Logger logger, Path dataDirectory, Core core) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.core = core;
        initDirectories();
    }

    public void onProxyInit() {
        try {
            config     = new SupportConfig(dataDirectory);
            manager    = new SupportManager(this, config);
            discordBot = new DiscordBot(this, config, manager);

            var meta = server.getCommandManager().metaBuilder("support").aliases("help").build();
            server.getCommandManager().register(meta, new SupportCommand(this));
            discordBot.start();

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
    public Core getCorePlugin()         { return core; }
    public SupportConfig getConfig()    { return config; }
    public SupportManager getManager()  { return manager; }
    public DiscordBot getDiscordBot()   { return discordBot; }
}
