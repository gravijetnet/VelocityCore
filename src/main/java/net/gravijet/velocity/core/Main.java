package net.gravijet.velocity.core;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import net.gravijet.velocity.core.commands.*;
import net.gravijet.velocity.core.database.DatabaseManager;
import net.gravijet.velocity.core.database.PlayerDataDAO;
import net.gravijet.velocity.core.logger.PlayerLogger;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.managers.JoinMeManager;
import net.gravijet.velocity.core.managers.TokenManager;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.util.AdvertisingManager;
import net.gravijet.velocity.core.util.ConfigManager;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "velocitycore",
        name = "VelocityCore",
        version = "1.0.0",
        authors = {"Gravijet"}
)
public class Main {

    private static volatile Main instance;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private ConfigManager configManager;

    private AdvertisingManager advertisingManager;
    private TokenManager tokenManager;
    private JoinMeManager joinMeManager;
    private PlayerDataDAO playerDataDAO;
    private SupportPlugin supportPlugin;
    private PlayerLogger playerLogger;
    private DatabaseManager databaseManager;

    @Inject
    public Main(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        instance = this;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            // Initialize Managers
            this.configManager = new ConfigManager(proxy, dataDirectory);
            this.databaseManager = new DatabaseManager(configManager);
            this.tokenManager = new TokenManager(databaseManager, proxy);
            this.joinMeManager = new JoinMeManager(this, proxy, tokenManager, configManager);
            this.advertisingManager = new AdvertisingManager(this, proxy, configManager);
            this.playerDataDAO = new PlayerDataDAO(databaseManager);
            this.playerLogger = new PlayerLogger(dataDirectory);

            // Start Services
            this.advertisingManager.start();

            // Register Commands
            CommandManager cmd = proxy.getCommandManager();
            cmd.register(cmd.metaBuilder("joinme").build(), new JoinMeCommand(proxy, tokenManager, joinMeManager, configManager));
            cmd.register(cmd.metaBuilder("adminjoinme").aliases("ajm").build(), new AdminJoinMeCommand(proxy, tokenManager, joinMeManager, configManager, playerDataDAO));
            cmd.register(cmd.metaBuilder("tokens").build(), new TokensCommand(proxy, tokenManager, joinMeManager, configManager));
            cmd.register(cmd.metaBuilder("vcore").aliases("velocitycore").build(), new ReloadCommand(this));
            
            // Other commands - can be refactored later if needed
            cmd.register(cmd.metaBuilder("ping").aliases("velocityping").build(), new PingCommand(proxy, configManager));
            cmd.register(cmd.metaBuilder("joinmecolor").aliases("jmc").build(), new JoinMeColorCommand(joinMeManager, configManager));
            cmd.register(cmd.metaBuilder("find").build(), new FindCommand(proxy, playerDataDAO, configManager));
            cmd.register(cmd.metaBuilder("hoster").build(), new HosterCommand(configManager));


            // Initialize Support Plugin
            this.supportPlugin = new SupportPlugin(proxy, logger, dataDirectory, this);
            supportPlugin.onProxyInit();

            logger.info("[VelocityCore] Successfully initialized.");
        } catch (Exception e) {
            logger.error("Failed to initialize VelocityCore — plugin disabled. Fix the config and restart.", e);
            if (tokenManager != null) try { tokenManager.shutdown(); } catch (Exception ignored) {}
            if (playerDataDAO != null) try { playerDataDAO.shutdown(); } catch (Exception ignored) {}
            if (advertisingManager != null) try { advertisingManager.stop(); } catch (Exception ignored) {}
            if (databaseManager != null) try { databaseManager.close(); } catch (Exception ignored) {}
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (supportPlugin != null) supportPlugin.onProxyShutdown();
        if (advertisingManager != null) advertisingManager.stop();
        if (playerLogger != null) playerLogger.shutdown();
        // Await pending DB writes before closing the connection pool.
        if (tokenManager != null) tokenManager.shutdown();
        if (playerDataDAO != null) playerDataDAO.shutdown();
        if (databaseManager != null) databaseManager.close();
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (joinMeManager != null) joinMeManager.loadColorPreference(player.getUniqueId());
        if (supportPlugin != null) supportPlugin.handlePlayerJoin(player);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (playerLogger == null) return;
        Player player = event.getPlayer();
        java.net.InetAddress addr = player.getRemoteAddress().getAddress();
        if (addr == null) return;
        String ip = addr.getHostAddress();
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

        if (joinMeManager != null) joinMeManager.clearColorPreference(player.getUniqueId());

        // Capture server synchronously — by the time the async DB fetch completes,
        // the player is fully disconnected and getCurrentServer() returns empty.
        String lastServer = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(null);

        if (playerDataDAO != null) playerDataDAO.updateLastSeen(
                player.getUniqueId(), player.getUsername(), System.currentTimeMillis(), lastServer);

        java.net.InetAddress disconnectAddr = player.getRemoteAddress().getAddress();
        String ip = disconnectAddr != null ? disconnectAddr.getHostAddress() : "unknown";
        if (playerLogger != null) playerLogger.logLeave(player.getUsername(), ip, lastServer != null ? lastServer : "unknown");

        if (supportPlugin != null) supportPlugin.handlePlayerLeave(player);
    }



    public void shutdown() {
        logger.info("Shutting down...");
        proxy.shutdown();
    }

    public static Main getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PlayerDataDAO getPlayerDataDAO() {
        return playerDataDAO;
    }

    public Logger getLogger() {
        return logger;
    }

    public void reload() {
        configManager.load();
        if (advertisingManager != null) {
            advertisingManager.start();
        }
        logger.info("VelocityCore configuration reloaded.");
    }
}