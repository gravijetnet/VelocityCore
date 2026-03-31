package net.gravijet.velocity.core.util;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AdvertisingManager {

    private final Main plugin;
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> task;
    private int currentMessageIndex = 0;

    public AdvertisingManager(Main plugin, ProxyServer server, ConfigManager configManager) {
        this.plugin = plugin;
        this.server = server;
        this.configManager = configManager;
    }

    public void start() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }

        boolean enabled = configManager.getBoolean("advertising.enabled", true);
        if (!enabled) {
            return;
        }

        int interval = configManager.getInt("advertising.interval_minutes", 5);
        List<String> messages = configManager.getStringList("advertising.messages", List.of());

        if (messages.isEmpty()) {
            return;
        }

        task = scheduler.scheduleAtFixedRate(() -> {
            if (currentMessageIndex >= messages.size()) {
                currentMessageIndex = 0;
            }
            String message = messages.get(currentMessageIndex++);
            Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
            server.getAllPlayers().forEach(player -> player.sendMessage(component));
        }, 0, interval, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }
        scheduler.shutdown();
    }
}
