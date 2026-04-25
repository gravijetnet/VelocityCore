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
            CompatibilityHelper.sendMessage(player, getMessage("joinme.on_cooldown", "cooldown", String.valueOf(cooldownSeconds)));
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
                CompatibilityHelper.sendMessage(player, getMessage("joinme.no_tokens", new String[0]));
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
                CompatibilityHelper.sendMessage(player,
                        CompatibilityHelper.colorize("<red>Could not use your token. Please try again."));
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
        String template = configManager.getMessages().node("joinme", "broadcast").getString(
                "<red>JoinMe <dark_gray>» {color}{player} <white>wants you to join! <gray>(click)");
        String color = getPlayerColorPreference(player.getUniqueId());
        if (color == null) color = "<white>";

        // Replace placeholders before parsing so centering sees the real text lengths
        String filled = template
                .replace("{color}", color)
                .replace("{player}", player.getUsername())
                .replace("{server}", serverName);

        String[] lines = filled.split("\\n", -1);
        if (lines.length >= 3) {
            lines[1] = centerMinecraftLine(lines[1]);
            lines[2] = centerMinecraftLine(lines[2]);
        }

        Component parsedMessage = CompatibilityHelper.colorize(String.join("\n", lines));
        return parsedMessage.clickEvent(ClickEvent.runCommand("/server " + serverName));
    }

    private static final int CHAT_WIDTH = 320;

    private static int charWidth(char c) {
        return switch (c) {
            case '!', ',', '.', ':', ';', '|' -> 2;
            case '\'', '`', 'i', 'l' -> 3;
            case 'j', 't', ' ' -> 4;
            case 'f', 'k', 'r', '(', ')', '"', '<', '>' -> 5;
            default -> 6;
        };
    }

    private static int plainTextWidth(String text) {
        if (text.isEmpty()) return 0;
        int w = 0;
        for (char c : text.toCharArray()) w += charWidth(c) + 1;
        return w - 1;
    }

    private static String centerMinecraftLine(String miniMessage) {
        String plain = miniMessage.replaceAll("<[^>]+>", "");
        int spaces = Math.max(0, (CHAT_WIDTH - plainTextWidth(plain)) / 2 / 5);
        return " ".repeat(spaces) + miniMessage;
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
        if (colorInput == null) {
            colorPreferences.remove(uuid);
            tokenManager.setPlayerColor(uuid, null);
            return;
        }
        String miniMessage = legacyToMiniMessage(colorInput);
        colorPreferences.put(uuid, miniMessage);
        tokenManager.setPlayerColor(uuid, miniMessage);
    }

    public String getPlayerColorPreference(UUID uuid) {
        String cached = colorPreferences.get(uuid);
        if (cached != null) return cached;
        try {
            PlayerData data = tokenManager.getPlayerData(uuid).join();
            if (data != null && data.getColor() != null) {
                String color = data.getColor();
                // Migrate old §-format (stored before MiniMessage migration)
                if (color.startsWith("§")) {
                    color = sectionSignToMiniMessage(color);
                }
                colorPreferences.put(uuid, color);
                return color;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String legacyToMiniMessage(String code) {
        if (code == null || code.isEmpty()) return "<white>";
        if (code.startsWith("&#") && code.length() == 8) {
            return "<" + code.substring(1) + ">";
        }
        return switch (code.toLowerCase()) {
            case "&0" -> "<black>";
            case "&1" -> "<dark_blue>";
            case "&2" -> "<dark_green>";
            case "&3" -> "<dark_aqua>";
            case "&4" -> "<dark_red>";
            case "&5" -> "<dark_purple>";
            case "&6" -> "<gold>";
            case "&7" -> "<gray>";
            case "&8" -> "<dark_gray>";
            case "&9" -> "<blue>";
            case "&a" -> "<green>";
            case "&b" -> "<aqua>";
            case "&c" -> "<red>";
            case "&d" -> "<light_purple>";
            case "&e" -> "<yellow>";
            case "&f" -> "<white>";
            default -> "<white>";
        };
    }

    private static String sectionSignToMiniMessage(String color) {
        if (color.startsWith("§#") && color.length() == 8) {
            return "<" + color.substring(1) + ">";
        }
        if (color.length() == 2) {
            return switch (Character.toLowerCase(color.charAt(1))) {
                case '0' -> "<black>";
                case '1' -> "<dark_blue>";
                case '2' -> "<dark_green>";
                case '3' -> "<dark_aqua>";
                case '4' -> "<dark_red>";
                case '5' -> "<dark_purple>";
                case '6' -> "<gold>";
                case '7' -> "<gray>";
                case '8' -> "<dark_gray>";
                case '9' -> "<blue>";
                case 'a' -> "<green>";
                case 'b' -> "<aqua>";
                case 'c' -> "<red>";
                case 'd' -> "<light_purple>";
                case 'e' -> "<yellow>";
                case 'f' -> "<white>";
                default -> "<white>";
            };
        }
        return "<white>";
    }

    private Component getMessage(String path, String... pairs) {
        String template = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        return CompatibilityHelper.colorize(template != null ? template : "", pairs);
    }
}