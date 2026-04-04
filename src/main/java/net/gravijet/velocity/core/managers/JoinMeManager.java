package net.gravijet.velocity.core.managers;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.gravijet.velocity.core.Main;
import net.gravijet.velocity.core.database.models.PlayerData;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class JoinMeManager {

    private static final Sound PING_SOUND = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.2f);

    private final Main plugin;
    private final ProxyServer proxy;
    private final TokenManager tokenManager;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> colorPreferences = new ConcurrentHashMap<>();

    public JoinMeManager(Main plugin, ProxyServer proxy, TokenManager tokenManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.tokenManager = tokenManager;
        this.configManager = configManager;
    }

    public CompletableFuture<Boolean> sendJoinMe(Player player, boolean isAdmin) {
        long cooldownSeconds = getCooldown(player.getUniqueId());
        if (!isAdmin && !player.hasPermission("velocitycore.joinme.cooldown.bypass") && cooldownSeconds > 0) {
            CompatibilityHelper.sendMessage(player, getMessage("joinme.on_cooldown", Placeholder.unparsed("cooldown", String.valueOf(cooldownSeconds))));
            return CompletableFuture.completedFuture(false);
        }

        if (isAdmin) {
            broadcastJoinMe(player, true);
            return CompletableFuture.completedFuture(true);
        }

        return tokenManager.getPlayerData(player.getUniqueId()).thenCompose(data -> {
            if (tokenManager.hasUnlimitedTokens(player.getUniqueId())) {
                broadcastJoinMe(player, false);
                return CompletableFuture.completedFuture(true);
            }
            if (data == null || data.getTotalTokens() <= 0) {
                CompatibilityHelper.sendMessage(player, getMessage("joinme.no_tokens"));
                return CompletableFuture.completedFuture(false);
            }
            return tokenManager.useToken(player.getUniqueId()).thenApply(success -> {
                if (success) {
                    if (!player.hasPermission("velocitycore.joinme.cooldown.bypass")) {
                        long cooldownDuration = configManager.getConfig().node("joinme", "cooldown").getLong(60);
                        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cooldownDuration));
                    }
                    broadcastJoinMe(player, false);
                    return true;
                }
                // Assuming a generic error message if token use fails
                CompatibilityHelper.sendMessage(player, miniMessage.deserialize("<red>Could not use your token. Please try again.</red>"));
                return false;
            });
        });
    }

    private void broadcastJoinMe(Player player, boolean isAdmin) {
        RegisteredServer server = player.getCurrentServer().map(ServerConnection::getServer).orElse(null);
        if (server == null) return;

        String serverName = server.getServerInfo().getName();
        Component message = buildBroadcast(player, serverName, isAdmin);
        proxy.getAllPlayers().forEach(p -> {
            CompatibilityHelper.sendMessage(p, message);
            p.playSound(PING_SOUND);
        });
    }

    private Component buildBroadcast(Player player, String serverName, boolean isAdmin) {
        String template = configManager.getMessages().node("joinme", "broadcast").getString("");

        // Create a resolver for the player's name
        TagResolver playerResolver = Placeholder.unparsed("player", player.getUsername());

        // Deserialize the message using the resolver
        Component parsedMessage = miniMessage.deserialize(template, playerResolver);

        // Add the click event to the entire component
        return parsedMessage.clickEvent(ClickEvent.runCommand("/server " + serverName));
    }


    public void forceJoinMe(Player player) {
        sendJoinMe(player, true);
    }

    public long getCooldown(UUID uuid) {
        Long expiry = cooldowns.get(uuid);
        if (expiry == null || expiry < System.currentTimeMillis()) {
            return 0;
        }
        return TimeUnit.MILLISECONDS.toSeconds(expiry - System.currentTimeMillis());
    }

    public void setPlayerColor(UUID uuid, String colorInput) {
        String normalized = colorInput != null ? colorInput.replace('&', '§') : null;
        if (normalized == null) {
            colorPreferences.remove(uuid);
        } else {
            colorPreferences.put(uuid, normalized);
        }
        tokenManager.setPlayerColor(uuid, normalized);
    }

    public String getPlayerColorPreference(UUID uuid) {
        String cached = colorPreferences.get(uuid);
        if (cached != null) return cached;
        try {
            PlayerData data = tokenManager.getPlayerData(uuid).join();
            if (data != null && data.getColor() != null) {
                String normalized = data.getColor().replace('&', '§');
                colorPreferences.put(uuid, normalized);
                return normalized;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Component getMessage(String path, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        String template = configManager.getMessages().node(path.split("\\.")).getString("");
        return miniMessage.deserialize(template, resolvers);
    }
}