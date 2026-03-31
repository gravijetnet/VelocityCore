package net.gravijet.velocity.core.managers;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.gravijet.velocity.core.Main;
import net.gravijet.velocity.core.util.ConfigManager;
import net.gravijet.velocity.core.database.models.PlayerData;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class JoinMeManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long COOLDOWN_MS = 5 * 60 * 1000L;
    private static final Sound PING_SOUND = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.2f);

    private final Main plugin;
    private final ProxyServer proxy;
    private final TokenManager tokenManager;
    private final ConfigManager config;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> colorPreferences = new ConcurrentHashMap<>();

    public JoinMeManager(Main plugin, ProxyServer proxy, TokenManager tokenManager, ConfigManager config) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.tokenManager = tokenManager;
        this.config = config;
    }

    public CompletableFuture<Boolean> sendJoinMe(Player player, boolean isAdmin) {
        if (!isAdmin && !player.hasPermission("core.joinme.cooldown.bypass")) {
            Long lastUsed = cooldowns.get(player.getUniqueId());
            if (lastUsed != null) {
                long timeLeft = (lastUsed + COOLDOWN_MS) - System.currentTimeMillis();
                if (timeLeft > 0) {
                    int seconds = (int) (timeLeft / 1000);
                    player.sendMessage(LEGACY.deserialize(config.getString("messages.joinme.cooldown", "&cYou are on cooldown for {seconds} seconds.").replace("{seconds}", String.valueOf(seconds))));
                    return CompletableFuture.completedFuture(false);
                }
            }
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
                player.sendMessage(LEGACY.deserialize(config.getString("messages.joinme.no_tokens", "&cYou do not have any JoinMe tokens.")));
                return CompletableFuture.completedFuture(false);
            }
            return tokenManager.useToken(player.getUniqueId()).thenApply(success -> {
                if (success) {
                    if (!player.hasPermission("core.joinme.cooldown.bypass")) {
                        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                    }
                    broadcastJoinMe(player, false);
                    return true;
                }
                player.sendMessage(LEGACY.deserialize(config.getString("messages.joinme.token_error", "&cCould not use your token. Please try again.")));
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
            p.sendMessage(message);
            p.playSound(PING_SOUND);
        });
    }

    private Component buildBroadcast(Player player, String serverName, boolean isAdmin) {
        String accent = isAdmin ? "&a" : getPlayerColorPreference(player.getUniqueId());
        if (accent == null) accent = "&c";

        String text = config.getString("messages.joinme.broadcast", "{player} is on {server}! Click to join them!")
                .replace("{player}", accent + player.getUsername() + "&7")
                .replace("{server}", serverName);
        String hover = config.getString("messages.joinme.hover", "&aClick to join {server}").replace("{server}", serverName);

        return LEGACY.deserialize(text)
                .hoverEvent(HoverEvent.showText(LEGACY.deserialize(hover)))
                .clickEvent(ClickEvent.runCommand("/server " + serverName));
    }

    public void forceJoinMe(Player player) {
        sendJoinMe(player, true);
    }

    public long getRemainingCooldown(Player player) {
        Long lastUsed = cooldowns.get(player.getUniqueId());
        if (lastUsed == null) return 0;
        return Math.max(0, (lastUsed + COOLDOWN_MS) - System.currentTimeMillis());
    }

    /** Converts §#RRGGBB hex notation to §x§R§R§G§G§B§B section-sequence. */
    public String formatToSectionColor(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length() * 2);
        int i = 0;
        while (i < input.length()) {
            if (i + 7 < input.length() && input.charAt(i) == '\u00a7' && input.charAt(i + 1) == '#') {
                String hex = input.substring(i + 2, i + 8);
                sb.append('\u00a7').append('x');
                for (char c : hex.toCharArray()) sb.append('\u00a7').append(c);
                i += 8;
            } else {
                sb.append(input.charAt(i++));
            }
        }
        return sb.toString();
    }

    public void setPlayerColor(UUID uuid, String colorInput) {
        String normalized = colorInput != null ? colorInput.replace('&', '\u00a7') : null;
        if (normalized == null) colorPreferences.remove(uuid);
        else colorPreferences.put(uuid, normalized);
        tokenManager.setPlayerColor(uuid, normalized);
    }

    public String getPlayerColorPreference(UUID uuid) {
        String cached = colorPreferences.get(uuid);
        if (cached != null) return cached;
        try {
            PlayerData data = tokenManager.getPlayerData(uuid).join();
            if (data != null && data.getColor() != null) {
                String normalized = data.getColor().replace('&', '\u00a7');
                colorPreferences.put(uuid, normalized);
                return normalized;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
