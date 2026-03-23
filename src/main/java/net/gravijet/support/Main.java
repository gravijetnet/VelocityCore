package net.gravijet.support;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.support.command.SupportCommand;
import net.gravijet.support.config.Config;
import net.gravijet.support.discord.DiscordBot;
import net.gravijet.support.manager.SupportManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Plugin(id = "support-plugin", name = "Support Plugin", version = "1.0.0", authors = {"Gravijet"})
public class Main {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Config config;
    private SupportManager manager;
    private DiscordBot discordBot;

    @Inject
    public Main(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        initDirectories();
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        try {
            config     = new Config(dataDirectory);
            manager    = new SupportManager(this, config);
            discordBot = new DiscordBot(this, config, manager);

            server.getCommandManager().register("support", new SupportCommand(this));
            discordBot.start();

            logger.info("Support Plugin enabled.");
        } catch (Exception e) {
            logger.error("Failed to initialize Support Plugin.", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (manager    != null) manager.shutdown();
        if (discordBot != null) discordBot.stop();
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        logConnection(event.getPlayer(), "JOINED");
        if (manager != null) manager.handlePlayerJoin(event.getPlayer());
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        if (event.getPlayer() == null) return;
        logConnection(event.getPlayer(), "LEFT");
        if (manager != null) manager.handlePlayerLeave(event.getPlayer());
    }

    private void logConnection(com.velocitypowered.api.proxy.Player player, String action) {
        if (player == null) return;
        try {
            String ip   = player.getRemoteAddress().getAddress().getHostAddress();
            String line = String.format("[%s] %s %s (%s)%n", formatter.format(LocalDateTime.now()), player.getUsername(), action, ip);
            Path globalLog = dataDirectory.resolve("player_ips.log");
            Path playerLog = dataDirectory.resolve("player_logs").resolve(player.getUsername() + ".log");
            writeAppend(globalLog, line);
            writeAppend(playerLog, line);
        } catch (Exception e) {
            logger.warn("Failed to log player connection: {}", e.getMessage());
        }
    }

    private void writeAppend(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.error("Failed to write to {}: {}", file, e.getMessage());
        }
    }

    private void initDirectories() {
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(dataDirectory.resolve("player_logs"));
            Files.createDirectories(dataDirectory.resolve("logs"));
        } catch (IOException e) {
            logger.error("Failed to create plugin directories.", e);
        }
    }

    public ProxyServer getServer()     { return server; }
    public Logger getLogger()          { return logger; }
    public Path getDataDirectory()     { return dataDirectory; }
    public Config getConfig()          { return config; }
    public SupportManager getManager() { return manager; }
    public DiscordBot getDiscordBot()  { return discordBot; }
}
