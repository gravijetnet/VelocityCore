package net.gravijet.velocity.core.util;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.Main;
import net.kyori.adventure.text.Component;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class AdvertisingManager {

    private final Main plugin;
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> task;
    private final AtomicInteger currentMessageIndex = new AtomicInteger(0);

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

        currentMessageIndex.set(0);
        task = scheduler.scheduleAtFixedRate(() -> {
            int idx = currentMessageIndex.getAndUpdate(i -> (i + 1) % messages.size());
            String message = messages.get(idx);
            Component component = CompatibilityHelper.colorize(message);
            server.getAllPlayers().forEach(player -> CompatibilityHelper.sendMessage(player, component));
        }, interval, interval, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }
        scheduler.shutdown();
    }
}