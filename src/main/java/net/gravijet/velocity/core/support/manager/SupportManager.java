package net.gravijet.velocity.core.support.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.model.BanEntry;
import net.gravijet.velocity.core.support.model.RatedSession;
import net.gravijet.velocity.core.support.model.SupportSession;
import net.gravijet.velocity.core.support.util.DurationUtil;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SupportManager {
    private final SupportPlugin plugin;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ObjectMapper mapper = new ObjectMapper();
    private final File bansFile;
    private final DateTimeFormatter logFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private ScheduledTask autoCloseTask;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "support-io");
        t.setDaemon(true);
        return t;
    });

    private final Map<UUID, SupportSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToSession = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> staffToSession = new ConcurrentHashMap<>();
    private final Map<String, UUID> discordStaffToSession = new ConcurrentHashMap<>();
    private final Map<UUID, SupportSession> closedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToLastClosed = new ConcurrentHashMap<>();
    private final Map<UUID, RatedSession> ratedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> sessionLastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, ClosedSessionNotification> pendingNotifications = new ConcurrentHashMap<>();
    private final Map<UUID, BanEntry> bans = new ConcurrentHashMap<>();
    private final Map<String, String> linkedAccounts = new ConcurrentHashMap<>();
    private final File linkedAccountsFile;

    public SupportManager(SupportPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.bansFile = new File(plugin.getDataDirectory().toFile(), "bans.json");
        this.linkedAccountsFile = new File(plugin.getDataDirectory().toFile(), "linked_accounts.json");
        loadBans();
        loadLinkedAccounts();
        startAutoCloseTask();
    }

    private void startAutoCloseTask() {
        try {
            autoCloseTask = plugin.getServer().getScheduler()
                    .buildTask(plugin.getCorePlugin(), this::checkInactiveSessions)
                    .repeat(5, TimeUnit.MINUTES)
                    .schedule();
        } catch (Exception e) {
            plugin.getLogger().warn("Could not schedule auto-close task: {}. Sessions will not be auto-closed.", e.getMessage());
        }
    }

    public void shutdown() {
        if (autoCloseTask != null) autoCloseTask.cancel();
        ioExecutor.shutdown();
    }

    private void checkInactiveSessions() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        pendingNotifications.entrySet().removeIf(e -> e.getValue().closedAt().plusSeconds(3600).isBefore(Instant.now()));

        for (UUID sessionId : new ArrayList<>(activeSessions.keySet())) {
            Instant last = sessionLastActivity.getOrDefault(sessionId, activeSessions.get(sessionId).getCreatedAt());
            if (last.isBefore(cutoff)) {
                plugin.getLogger().info("Auto-closing inactive session: {}", sessionId);
                autoCloseSession(sessionId);
            }
        }
    }

    private void autoCloseSession(UUID sessionId) {
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) return;

        Optional<Player> staffOpt = session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer);
        staffOpt.ifPresent(staff -> {
            staff.sendMessage(getMessage("support.ticket-closed-staff", Placeholder.unparsed("player", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        playerToSession.remove(session.getPlayerId());
        if (playerOpt.isPresent()) {
            playerOpt.get().sendMessage(getMessage("support.ticket-auto-closed"));
            sendRatingPrompt(playerOpt.get(), session);
        } else {
            pendingNotifications.put(session.getPlayerId(), new ClosedSessionNotification(sessionId, Instant.now(), session.getLanguage()));
        }

        finalizeSession(session);
        logSessionAction(session, "AUTO_CLOSED", "Closed due to inactivity");

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().closeSupportChannel(session.getSessionId().toString(), session.getLanguage(), session.getPlayerName(), session.getCreatedAt());
        }
    }

    private void finalizeSession(SupportSession session) {
        session.setClosedAt(Instant.now());
        session.setActive(false);
        UUID sessionId = session.getSessionId();
        activeSessions.remove(sessionId);
        closedSessions.put(sessionId, session);
        playerToLastClosed.put(session.getPlayerId(), sessionId);
        sessionLastActivity.remove(sessionId);
    }

    public boolean canRequestSupport(Player player, String lang) {
        if (hasPermission(player, getStaffPermission())) {
            player.sendMessage(getMessage("support.staff-cannot-request"));
            return false;
        }
        String banMsg = getBanMessage(player.getUniqueId(), lang);
        if (banMsg != null) {
            player.sendMessage(miniMessage.deserialize(banMsg));
            return false;
        }
        if (playerToSession.containsKey(player.getUniqueId())) {
            player.sendMessage(getMessage("support.already-in-session"));
            return false;
        }
        return true;
    }

    public void createSupportRequest(Player player, String language) {
        SupportSession session = new SupportSession(player.getUniqueId(), player.getUsername(), language, Instant.now());
        activeSessions.put(session.getSessionId(), session);
        playerToSession.put(player.getUniqueId(), session.getSessionId());
        sessionLastActivity.put(session.getSessionId(), Instant.now());

        String serverName = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("Unknown");

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().createSupportChannel(player.getUsername(), serverName, language, session.getSessionId().toString());
        }

        Component component = getMessage("support.staff-notification",
                Placeholder.unparsed("player", player.getUsername()),
                Placeholder.unparsed("server", serverName),
                Placeholder.unparsed("language", language.toUpperCase())
        ).clickEvent(ClickEvent.suggestCommand("/support claim " + player.getUsername()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to claim this request")));

        plugin.getServer().getAllPlayers().stream()
                .filter(p -> hasPermission(p, getStaffPermission()))
                .forEach(p -> p.sendMessage(component));

        player.sendMessage(getMessage("support.ticket-created"));
        logSessionAction(session, "CREATED", "Player requested support (" + language.toUpperCase() + ")");
    }

    public boolean claimSupport(Player staff, String playerName, boolean force) {
        Optional<Player> targetOpt = plugin.getServer().getPlayer(playerName);
        if (targetOpt.isEmpty()) {
            staff.sendMessage(getMessage("general.player-not-found", Placeholder.unparsed("player", playerName)));
            return false;
        }

        Player target = targetOpt.get();
        UUID sessionId = playerToSession.get(target.getUniqueId());
        if (sessionId == null) {
            staff.sendMessage(getMessage("support.no-active-session-for-player", Placeholder.unparsed("player", playerName)));
            return false;
        }

        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            staff.sendMessage(getMessage("support.no-active-session-for-player", Placeholder.unparsed("player", playerName)));
            return false;
        }

        UUID existingStaffSession = staffToSession.get(staff.getUniqueId());
        if (existingStaffSession != null && !existingStaffSession.equals(sessionId)) {
            staff.sendMessage(getMessage("support.already-handling-session"));
            return false;
        }

        boolean alreadyClaimed = session.getStaffId() != null || discordStaffToSession.containsValue(sessionId);
        if (alreadyClaimed && !force) {
            String existingStaff = session.getStaffName() != null ? session.getStaffName() : "a Discord team member";
            staff.sendMessage(getMessage("support.already-claimed", Placeholder.unparsed("staff", existingStaff)));
            return false;
        }

        if (force) {
            discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
        }

        session.setStaffId(staff.getUniqueId());
        session.setStaffName(staff.getUsername());
        session.setDiscordOnly(false);
        staffToSession.put(staff.getUniqueId(), sessionId);

        staff.sendMessage(getMessage("support.claimed-by-you", Placeholder.unparsed("player", target.getUsername())));
        target.sendMessage(getMessage("support.claimed-by-staff", Placeholder.unparsed("staff", staff.getUsername())));

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().updateChannelToClaimed(session.getSessionId().toString(), staff.getUsername(), staff.getUniqueId().toString());
        }

        logSessionAction(session, "CLAIMED", "Claimed by " + staff.getUsername());
        return true;
    }

    public void handleSupportChat(Player sender, String message) {
        boolean isStaff = hasPermission(sender, getStaffPermission());
        UUID sessionId = isStaff ? staffToSession.get(sender.getUniqueId()) : playerToSession.get(sender.getUniqueId());

        if (sessionId == null) {
            sender.sendMessage(getMessage("support.no-open-ticket"));
            return;
        }
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            sender.sendMessage(getMessage("support.no-open-ticket"));
            return;
        }

        sessionLastActivity.put(sessionId, Instant.now());
        Optional<Player> staffOpt  = session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer);
        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());

        Component chatMessage = getMessage("support.staff-chat-format",
                Placeholder.unparsed("player", sender.getUsername()),
                Placeholder.unparsed("message", message)
        );

        if (isStaff) {
            playerOpt.ifPresent(p -> p.sendMessage(chatMessage));
            staffOpt.filter(s -> !s.getUniqueId().equals(sender.getUniqueId()))
                    .ifPresent(s -> s.sendMessage(chatMessage));
            sender.sendMessage(chatMessage);
        } else {
            staffOpt.ifPresent(s -> s.sendMessage(chatMessage));
            sender.sendMessage(chatMessage);
        }

        logSessionChat(session, sender.getUsername(), message);
        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().sendMessageToDiscord(session.getSessionId().toString(), message, sender.getUsername(), isStaff);
        }
    }

    private Component getMessage(String path, TagResolver... resolvers) {
        String template = configManager.getMessages().node(path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message for " + path + " not found.").color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return miniMessage.deserialize(template, resolvers);
    }

    private String getStaffPermission() {
        return configManager.getConfig().node("support", "staff-permission").getString("velocitycore.support.staff");
    }

    private boolean hasPermission(Player p, String permission) {
        return p.hasPermission(permission) || p.hasPermission("support.*");
    }
    
    private void logSessionAction(String action, String detail) {
        // Simplified logging, assuming no session context
        String line = String.format("[%s] %s | %s%n",
                logFormatter.format(LocalDateTime.now()),
                action,
                detail);
        appendLog("sessions.log", line);
    }

    private void logSessionAction(SupportSession session, String action, String detail) {
        String line = String.format("[%s] [%s] %s | player=%s | staff=%s | %s%n",
                logFormatter.format(LocalDateTime.now()),
                session.getSessionId().toString().substring(0, 8),
                action,
                session.getPlayerName(),
                session.getStaffName() != null ? session.getStaffName() : "none",
                detail);
        appendLog("sessions.log", line);
    }

    private void logSessionChat(SupportSession session, String author, String message) {
        String line = String.format("[%s] [%s] %s: %s%n",
                logFormatter.format(LocalDateTime.now()),
                session.getSessionId().toString().substring(0, 8),
                author, message);
        appendLog("chat.log", line);
    }

    private void appendLog(String filename, String content) {
        Path dir = plugin.getDataDirectory().resolve("logs");
        ioExecutor.execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(filename), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().warn("Failed to write log file {}: {}", filename, e.getMessage());
            }
        });
    }
    
    // ... other methods like ban, unban, rate, etc. would need similar refactoring

    public record ClosedSessionNotification(UUID sessionId, Instant closedAt, String language) {}
}
