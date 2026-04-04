package net.gravijet.velocity.core.util;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AdvertisingManager {

    private final Main plugin;
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
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

        ConfigurationNode adNode = configManager.getConfig().node("advertising");
        if (adNode.virtual() || !adNode.node("enabled").getBoolean(true)) {
            return;
        }

        int interval = adNode.node("interval_minutes").getInt(5);
        
        // Use a more robust method to read the list to prevent SerializationException
        List<String> messages = adNode.node("messages").childrenList().stream()
                .map(ConfigurationNode::getString)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (messages.isEmpty()) {
            return;
        }

        task = scheduler.scheduleAtFixedRate(() -> {
            if (currentMessageIndex >= messages.size()) {
                currentMessageIndex = 0;
            }
            String message = messages.get(currentMessageIndex++);
            Component component = miniMessage.deserialize(message);
            server.getAllPlayers().forEach(player -> CompatibilityHelper.sendMessage(player, component));
        }, 0, interval, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }
        scheduler.shutdown();
    }
}