package net.gravijet.velocity.core;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import net.gravijet.velocity.core.logger.PlayerLogger;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.config.CoreConfig;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.commands.*;
import net.gravijet.velocity.core.database.DatabaseManager;
import net.gravijet.velocity.core.database.PlayerDataDAO;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "velocitycore",
        name = "VelocityCore",
        version = "1.0.0",
        authors = {"Gravijet"}
)
public class Core {

    private static Core instance;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private CoreConfig coreConfig;
    private TokenManager tokenManager;
    private JoinMeManager joinMeManager;
    private PlayerDataDAO playerDataDAO;
    private SupportPlugin supportPlugin;
    private PlayerLogger playerLogger;

    @Inject
    public Core(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        instance = this;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            this.coreConfig = new CoreConfig(dataDirectory);
            DatabaseManager databaseManager = new DatabaseManager();
            this.playerDataDAO = new PlayerDataDAO(databaseManager);
            this.tokenManager = new TokenManager(databaseManager, proxy);
            this.joinMeManager = new JoinMeManager(this, proxy, tokenManager, coreConfig);

            // Register commands
            CommandManager cmd = proxy.getCommandManager();
            cmd.register(cmd.metaBuilder("ping").aliases("velocityping").build(), new PingCommand(proxy, coreConfig));
            cmd.register(cmd.metaBuilder("joinme").build(), new JoinMeCommand(proxy, tokenManager, joinMeManager, coreConfig));
            cmd.register(cmd.metaBuilder("adminjoinme").aliases("ajm").build(), new AdminJoinMeCommand(proxy, tokenManager, joinMeManager, coreConfig));
            cmd.register(cmd.metaBuilder("tokens").build(), new TokensCommand(proxy, tokenManager, coreConfig));
            cmd.register(cmd.metaBuilder("joinmecolor").aliases("jmc").build(), new JoinMeColorCommand(joinMeManager, coreConfig));
            cmd.register(cmd.metaBuilder("find").build(), new FindCommand(proxy, playerDataDAO, coreConfig));

            this.playerLogger = new PlayerLogger(dataDirectory);

            // Initialize support subsystem
            this.supportPlugin = new SupportPlugin(proxy, logger, dataDirectory, this);
            supportPlugin.onProxyInit();

            logger.info("[VelocityCore] Successfully initialized.");
        } catch (Exception e) {
            logger.error("Failed to initialize VelocityCore:", e);
            shutdown();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (supportPlugin != null) supportPlugin.onProxyShutdown();
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (supportPlugin != null) supportPlugin.handlePlayerJoin(player);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        String toServer = event.getServer().getServerInfo().getName();

        if (event.getPreviousServer().isPresent()) {
            String fromServer = event.getPreviousServer().get().getServerInfo().getName();
            playerLogger.logSwitch(player.getUsername(), ip, fromServer, toServer);
        } else {
            playerLogger.logJoin(player.getUsername(), ip, toServer);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        playerDataDAO.getPlayerData(player.getUniqueId()).thenAccept(data -> {
            if (data != null) {
                data.setLastOnline(System.currentTimeMillis());
                player.getCurrentServer().ifPresent(server -> data.setLastServer(server.getServerInfo().getName()));
                playerDataDAO.savePlayerData(data);
            }
        });

        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        String lastServer = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("unknown");
        playerLogger.logLeave(player.getUsername(), ip, lastServer);

        if (supportPlugin != null) supportPlugin.handlePlayerLeave(player);
    }

    public void shutdown() {
        proxy.shutdown();
        System.out.println("Shutting down...");
    }

    public static Core getInstance() {
        return instance;
    }
}
