package net.gravijet.velocity.core.support.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.model.BanEntry;
import net.gravijet.velocity.core.support.model.RatedSession;
import net.gravijet.velocity.core.support.model.SupportSession;
import net.gravijet.velocity.core.support.util.DurationUtil;
import net.gravijet.velocity.core.util.CompatibilityHelper;
import net.gravijet.velocity.core.util.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
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
            CompatibilityHelper.sendMessage(staff, getMessage("support.ticket-closed-staff", Placeholder.unparsed("player", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        playerToSession.remove(session.getPlayerId());
        if (playerOpt.isPresent()) {
            CompatibilityHelper.sendMessage(playerOpt.get(), getMessage("support.ticket-auto-closed"));
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
            CompatibilityHelper.sendMessage(player, Component.text("Staff cannot request support"));
            return false;
        }
        String banMsg = getBanMessage(player.getUniqueId(), lang);
        if (banMsg != null) {
            CompatibilityHelper.sendMessage(player, Component.text(banMsg));
            return false;
        }
        if (playerToSession.containsKey(player.getUniqueId())) {
            CompatibilityHelper.sendMessage(player, Component.text("You already have an active support session"));
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
                .forEach(p -> CompatibilityHelper.sendMessage(p, component));

        CompatibilityHelper.sendMessage(player, getMessage("support.ticket-created"));
        logSessionAction(session, "CREATED", "Player requested support (" + language.toUpperCase() + ")");
    }

    public boolean claimSupport(Player staff, String playerName, boolean force) {
        Optional<Player> targetOpt = plugin.getServer().getPlayer(playerName);
        if (targetOpt.isEmpty()) {
            CompatibilityHelper.sendMessage(staff, getMessage("general.player-not-found", Placeholder.unparsed("player", playerName)));
            return false;
        }

        Player target = targetOpt.get();
        UUID sessionId = playerToSession.get(target.getUniqueId());
        if (sessionId == null) {
            CompatibilityHelper.sendMessage(staff, getMessage("support.no-active-session-for-player", Placeholder.unparsed("player", playerName)));
            return false;
        }

        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            CompatibilityHelper.sendMessage(staff, getMessage("support.no-active-session-for-player", Placeholder.unparsed("player", playerName)));
            return false;
        }

        UUID existingStaffSession = staffToSession.get(staff.getUniqueId());
        if (existingStaffSession != null && !existingStaffSession.equals(sessionId)) {
            CompatibilityHelper.sendMessage(staff, getMessage("support.already-handling-session"));
            return false;
        }

        boolean alreadyClaimed = session.getStaffId() != null || discordStaffToSession.containsValue(sessionId);
        if (alreadyClaimed && !force) {
            String existingStaff = session.getStaffName() != null ? session.getStaffName() : "a Discord team member";
            CompatibilityHelper.sendMessage(staff, getMessage("support.already-claimed", Placeholder.unparsed("staff", existingStaff)));
            return false;
        }

        if (force) {
            discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
        }

        session.setStaffId(staff.getUniqueId());
        session.setStaffName(staff.getUsername());
        session.setDiscordOnly(false);
        staffToSession.put(staff.getUniqueId(), sessionId);

        CompatibilityHelper.sendMessage(staff, getMessage("support.claimed-by-you", Placeholder.unparsed("player", target.getUsername())));
        CompatibilityHelper.sendMessage(target, getMessage("support.claimed-by-staff", Placeholder.unparsed("staff", staff.getUsername())));

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().updateChannelToClaimed(session.getSessionId().toString(), staff.getUsername(), staff.getUniqueId().toString());
        }

        logSessionAction(session, "CLAIMED", "Claimed by " + staff.getUsername());
        return true;
    }

    public boolean claimSupportFromDiscord(String discordStaffId, String staffName, String playerName, boolean force) {
        Optional<Player> targetOpt = plugin.getServer().getPlayer(playerName);
        if (targetOpt.isEmpty()) return false;

        UUID sessionId = playerToSession.get(targetOpt.get().getUniqueId());
        if (sessionId == null) return false;

        SupportSession session = activeSessions.get(sessionId);
        if (session == null) return false;

        UUID existingDiscordSession = discordStaffToSession.get(discordStaffId);
        if (existingDiscordSession != null && !existingDiscordSession.equals(sessionId)) return false;

        boolean alreadyClaimed = session.getStaffId() != null || discordStaffToSession.containsValue(sessionId);
        if (alreadyClaimed && !force) return false;

        session.setStaffName(staffName);
        session.setDiscordOnly(true);
        discordStaffToSession.put(discordStaffId, sessionId);
        sessionLastActivity.put(sessionId, Instant.now());

        CompatibilityHelper.sendMessage(targetOpt.get(), getMessage("support.claimed-by-staff", Placeholder.unparsed("staff", staffName)));
        logSessionAction(session, "CLAIMED_DISCORD", "Claimed by Discord user " + staffName);
        return true;
    }

    public void closeSupportSession(Player closer) {
        boolean isStaff = hasPermission(closer, getStaffPermission());
        UUID sessionId = isStaff ? staffToSession.get(closer.getUniqueId()) : playerToSession.get(closer.getUniqueId());

        if (sessionId == null) {
            CompatibilityHelper.sendMessage(closer, getMessage("support.no-open-ticket"));
            return;
        }
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            CompatibilityHelper.sendMessage(closer, getMessage("support.no-open-ticket"));
            return;
        }

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
            CompatibilityHelper.sendMessage(staff, getMessage("support.ticket-closed-staff", Placeholder.unparsed("player", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
        playerToSession.remove(session.getPlayerId());

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        if (playerOpt.isPresent()) {
            CompatibilityHelper.sendMessage(playerOpt.get(), getMessage("support.ticket-closed"));
            sendRatingPrompt(playerOpt.get(), session);
        } else {
            pendingNotifications.put(session.getPlayerId(), new ClosedSessionNotification(sessionId, Instant.now(), session.getLanguage()));
        }

        logSessionAction(session, "CLOSED", "Closed by " + closer.getUsername());
        finalizeSession(session);

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().closeSupportChannel(session.getSessionId().toString(), session.getLanguage(), session.getPlayerName(), session.getCreatedAt());
        }
    }

    public boolean closeSupportSessionFromDiscord(String discordStaffId) {
        UUID sessionId = discordStaffToSession.get(discordStaffId);
        if (sessionId == null) return false;

        SupportSession session = activeSessions.get(sessionId);
        if (session == null) return false;

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
            CompatibilityHelper.sendMessage(staff, getMessage("support.ticket-closed-staff", Placeholder.unparsed("player", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.remove(discordStaffId);
        playerToSession.remove(session.getPlayerId());

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        if (playerOpt.isPresent()) {
            CompatibilityHelper.sendMessage(playerOpt.get(), getMessage("support.ticket-closed"));
            sendRatingPrompt(playerOpt.get(), session);
        } else {
            pendingNotifications.put(session.getPlayerId(), new ClosedSessionNotification(sessionId, Instant.now(), session.getLanguage()));
        }

        logSessionAction(session, "CLOSED_DISCORD", "Closed by Discord team member");
        finalizeSession(session);

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().closeSupportChannel(session.getSessionId().toString(), session.getLanguage(), session.getPlayerName(), session.getCreatedAt());
        }
        return true;
    }

    public void handleSupportChat(Player sender, String message) {
        boolean isStaff = hasPermission(sender, getStaffPermission());
        UUID sessionId = isStaff ? staffToSession.get(sender.getUniqueId()) : playerToSession.get(sender.getUniqueId());

        if (sessionId == null) {
            CompatibilityHelper.sendMessage(sender, getMessage("support.no-open-ticket"));
            return;
        }
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            CompatibilityHelper.sendMessage(sender, getMessage("support.no-open-ticket"));
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
            playerOpt.ifPresent(p -> CompatibilityHelper.sendMessage(p, chatMessage));
            staffOpt.filter(s -> !s.getUniqueId().equals(sender.getUniqueId()))
                    .ifPresent(s -> CompatibilityHelper.sendMessage(s, chatMessage));
            CompatibilityHelper.sendMessage(sender, chatMessage);
        } else {
            staffOpt.ifPresent(s -> CompatibilityHelper.sendMessage(s, chatMessage));
            CompatibilityHelper.sendMessage(sender, chatMessage);
        }

        logSessionChat(session, sender.getUsername(), message);
        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().sendMessageToDiscord(session.getSessionId().toString(), message, sender.getUsername(), isStaff);
        }
    }

    public void handleSupportChatFromDiscord(String discordStaffId, String message) {
        UUID sessionId = discordStaffToSession.get(discordStaffId);
        if (sessionId == null) return;
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) return;

        sessionLastActivity.put(sessionId, Instant.now());

        Component chatMessage = getMessage("support.staff-chat-format",
                Placeholder.unparsed("player", "Discord"),
                Placeholder.unparsed("message", message)
        );

        plugin.getServer().getPlayer(session.getPlayerId())
                .ifPresent(p -> CompatibilityHelper.sendMessage(p, chatMessage));

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer)
                .ifPresent(s -> CompatibilityHelper.sendMessage(s, chatMessage));

        logSessionChat(session, "Discord/" + discordStaffId, message);
    }

    private void sendRatingPrompt(Player player, SupportSession session) {
        CompatibilityHelper.sendMessage(player, getMessage("support.rating-prompt"));
        Component row = Component.empty();
        for (int i = 1; i <= 5; i++) {
            Component btn = Component.text("[" + i + "]")
                    .color(net.kyori.adventure.text.format.TextColor.color(0x5865F2))
                    .hoverEvent(HoverEvent.showText(Component.text(i + "/5")))
                    .clickEvent(ClickEvent.runCommand("/support rate " + i));
            row = row.append(btn).append(Component.text(" "));
        }
        CompatibilityHelper.sendMessage(player, row);
    }

    public void rateSupport(Player player, int rating) {
        if (rating < 1 || rating > 5) {
            CompatibilityHelper.sendMessage(player, getMessage("support.invalid-rating"));
            return;
        }

        UUID lastSessionId = playerToLastClosed.get(player.getUniqueId());
        SupportSession session = lastSessionId != null ? closedSessions.get(lastSessionId) : null;

        if (session == null) {
            CompatibilityHelper.sendMessage(player, getMessage("support.not-session-player"));
            return;
        }

        if (ratedSessions.containsKey(session.getSessionId())) {
            CompatibilityHelper.sendMessage(player, getMessage("support.already-rated"));
            return;
        }
        if (session.getClosedAt() != null && session.getClosedAt().plusSeconds(3600).isBefore(Instant.now())) {
            CompatibilityHelper.sendMessage(player, getMessage("support.rating-expired"));
            return;
        }

        String staffName = session.getStaffName() != null ? session.getStaffName() : "Unknown";
        ratedSessions.put(session.getSessionId(), new RatedSession(session.getSessionId(), rating, Instant.now(), staffName));
        CompatibilityHelper.sendMessage(player, getMessage("support.rating-received", Placeholder.unparsed("rating", String.valueOf(rating))));

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff ->
                CompatibilityHelper.sendMessage(staff, miniMessage.deserialize("<gray>" + player.getUsername() + " rated this session " + rating + "/5.</gray>")));

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().sendRatingEmbedToChannel(session.getSessionId().toString(), rating, player.getUsername(), staffName);
        }

        logSessionAction(session, "RATED", player.getUsername() + " rated " + rating + "/5");
    }

    public void handlePlayerJoin(Player player) {
        ClosedSessionNotification note = pendingNotifications.remove(player.getUniqueId());
        if (note != null && note.closedAt().plusSeconds(3600).isAfter(Instant.now())) {
            CompatibilityHelper.sendMessage(player, getMessage("support.ticket-auto-closed"));
            SupportSession dummy = new SupportSession(player.getUniqueId(), player.getUsername(), note.language(), Instant.now());
            sendRatingPrompt(player, dummy);
        }

        UUID sessionId = playerToSession.get(player.getUniqueId());
        if (sessionId != null) {
            SupportSession session = activeSessions.get(sessionId);
            if (session != null) {
                session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
                    CompatibilityHelper.sendMessage(staff, getMessage("support.player-online-status", Placeholder.unparsed("player", player.getUsername())));
                    if (plugin.getDiscordBot() != null) {
                        plugin.getDiscordBot().sendStatusMessageToChannel(sessionId.toString(), player.getUsername() + " is online");
                    }
                });
            }
        }
    }

    public void handlePlayerLeave(Player player) {
        UUID sessionId = playerToSession.get(player.getUniqueId());
        if (sessionId != null) {
            SupportSession session = activeSessions.get(sessionId);
            if (session != null) {
                session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
                    CompatibilityHelper.sendMessage(staff, getMessage("support.player-offline-status", Placeholder.unparsed("player", player.getUsername())));
                    if (plugin.getDiscordBot() != null) {
                        plugin.getDiscordBot().sendStatusMessageToChannel(sessionId.toString(), player.getUsername() + " went offline");
                    }
                });
            }
        }
    }

    public String getBanMessage(UUID playerId, String lang) {
        BanEntry ban = bans.get(playerId);
        if (ban == null) return null;
        if (ban.isExpired()) {
            bans.remove(playerId);
            saveBans();
            return null;
        }
        String timeLeft = ban.getDuration() == Long.MAX_VALUE ? "permanent" : DurationUtil.format(ban.getRemainingMillis());
        return configManager.getMessages().node("support", "you-are-banned").getString("<red>You are banned from support. Remaining: <duration></red>").replace("<duration>", timeLeft);
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

    private void loadBans() {
        if (!bansFile.exists()) return;
        try {
            BanEntry[] entries = mapper.readValue(bansFile, BanEntry[].class);
            int loaded = 0;
            for (BanEntry ban : entries) {
                if (!ban.isExpired()) {
                    bans.put(ban.getPlayerId(), ban);
                    loaded++;
                }
            }
            plugin.getLogger().info("Loaded {} active ban(s).", loaded);
        } catch (IOException e) {
            plugin.getLogger().error("Failed to load bans.json", e);
        }
    }

    private void saveBans() {
        BanEntry[] snapshot = bans.values().toArray(new BanEntry[0]);
        ioExecutor.execute(() -> {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(bansFile, snapshot);
            } catch (IOException e) {
                plugin.getLogger().error("Failed to save bans.json", e);
            }
        });
    }

    private void loadLinkedAccounts() {
        if (!linkedAccountsFile.exists()) return;
        try {
            Map<String, String> loaded = mapper.readValue(linkedAccountsFile,
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            linkedAccounts.putAll(loaded);
            plugin.getLogger().info("Loaded {} linked account(s).", linkedAccounts.size());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to load linked_accounts.json", e);
        }
    }

    private void saveLinkedAccounts() {
        Map<String, String> snapshot = new HashMap<>(linkedAccounts);
        ioExecutor.execute(() -> {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(linkedAccountsFile, snapshot);
            } catch (IOException e) {
                plugin.getLogger().error("Failed to save linked_accounts.json", e);
            }
        });
    }

    public static class StaffStats {
        public final String staffName;
        public int totalSessions;
        private double ratingSum;
        public final int[] distribution = new int[6];

        public StaffStats(String staffName) { this.staffName = staffName; }

        public void add(int rating) {
            totalSessions++;
            ratingSum += rating;
            if (rating >= 1 && rating <= 5) distribution[rating]++;
        }

        public double getAverage() { return totalSessions == 0 ? 0.0 : ratingSum / totalSessions; }
    }

    public Map<String, StaffStats> buildStaffStats() {
        Map<String, StaffStats> result = new java.util.LinkedHashMap<>();
        for (RatedSession r : ratedSessions.values()) {
            String name = r.getStaffName() != null && !r.getStaffName().isEmpty() ? r.getStaffName() : "Unknown";
            result.computeIfAbsent(name, StaffStats::new).add(r.getRating());
        }
        return result;
    }

    public int getTotalRatedCount() {
        return ratedSessions.size();
    }

    public double getGlobalAverageRating() {
        if (ratedSessions.isEmpty()) return 0.0;
        return ratedSessions.values().stream().mapToInt(RatedSession::getRating).average().orElse(0.0);
    }

    public record ClosedSessionNotification(UUID sessionId, Instant closedAt, String language) {}
}
