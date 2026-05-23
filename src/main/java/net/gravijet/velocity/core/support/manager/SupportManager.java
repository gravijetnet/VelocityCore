package net.gravijet.velocity.core.support.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.gravijet.velocity.core.database.PlayerDataDAO;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SupportManager {
    private final SupportPlugin plugin;
    private final ConfigManager configManager;
    private final PlayerDataDAO playerDataDAO;
    private final ObjectMapper mapper = new ObjectMapper();
    private final File bansFile;
    private final DateTimeFormatter logFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC);
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
    // Reverse of discordStaffToSession: sessionId -> discordStaffId, kept in sync to avoid O(n) containsValue scans.
    private final Map<UUID, String> sessionToDiscordStaff = new ConcurrentHashMap<>();
    private final Map<UUID, SupportSession> closedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerToLastClosed = new ConcurrentHashMap<>();
    private final Map<UUID, RatedSession> ratedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> sessionLastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, ClosedSessionNotification> pendingNotifications = new ConcurrentHashMap<>();
    private final Map<UUID, BanEntry> bans = new ConcurrentHashMap<>();

    public SupportManager(SupportPlugin plugin, ConfigManager configManager, PlayerDataDAO playerDataDAO) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.playerDataDAO = playerDataDAO;
        this.bansFile = new File(plugin.getDataDirectory().toFile(), "bans.json");
        loadBans();
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
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void checkInactiveSessions() {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(3600);
        Instant pruneCutoff = now.minusSeconds(86400);

        pendingNotifications.entrySet().removeIf(e -> e.getValue().closedAt().plusSeconds(3600).isBefore(now));

        // Prune closed sessions and their ratings older than 24 hours to prevent unbounded memory growth
        closedSessions.entrySet().removeIf(e -> {
            Instant closedAt = e.getValue().getClosedAt();
            if (closedAt != null && closedAt.isBefore(pruneCutoff)) {
                UUID prunedId = e.getKey();
                ratedSessions.remove(prunedId);
                playerToLastClosed.values().removeIf(v -> v.equals(prunedId));
                return true;
            }
            return false;
        });

        for (UUID sessionId : new ArrayList<>(activeSessions.keySet())) {
            SupportSession session = activeSessions.get(sessionId);
            if (session == null) continue;
            Instant last = sessionLastActivity.getOrDefault(sessionId, session.getCreatedAt());
            if (last.isBefore(cutoff)) {
                plugin.getLogger().info("Auto-closing inactive session: {}", sessionId);
                autoCloseSession(sessionId);
            }
        }
    }

    private void autoCloseSession(UUID sessionId) {
        // Atomically claim the session: whichever close path removes it first
        // owns the shutdown. Prevents a double transcript / rating prompt /
        // Discord channel delete when the auto-close task races a manual close.
        SupportSession session = activeSessions.remove(sessionId);
        if (session == null) return;

        Optional<Player> staffOpt = session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer);
        staffOpt.ifPresent(staff ->
            CompatibilityHelper.sendMessage(staff, getMessage("support.ticket-closed-staff", "player", session.getPlayerName())));

        String prevDiscord = sessionToDiscordStaff.remove(sessionId);
        if (prevDiscord != null) discordStaffToSession.remove(prevDiscord);

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        playerToSession.remove(session.getPlayerId());
        if (playerOpt.isPresent()) {
            CompatibilityHelper.sendMessage(playerOpt.get(), getMessage("support.ticket-auto-closed"));
            sendRatingPrompt(playerOpt.get());
        } else {
            pendingNotifications.put(session.getPlayerId(), new ClosedSessionNotification(sessionId, Instant.now(), session.getLanguage()));
        }

        finalizeSession(session);
        logSessionAction(session, "AUTO_CLOSED", "Closed due to inactivity");

        if (plugin.getDiscordBot() != null) {
            final String sid = session.getSessionId().toString();
            final String lang = session.getLanguage();
            final String pname = session.getPlayerName();
            final Instant createdAt = session.getCreatedAt();
            ioExecutor.execute(() -> plugin.getDiscordBot().closeSupportChannel(sid, lang, pname, createdAt));
        }
    }

    private void finalizeSession(SupportSession session) {
        session.setClosedAt(Instant.now());
        session.setActive(false);
        UUID sessionId = session.getSessionId();
        // activeSessions was already atomically removed by the caller (autoCloseSession /
        // closeSupportSession / closeSessionCore). Do not remove again here.
        closedSessions.put(sessionId, session);
        playerToLastClosed.put(session.getPlayerId(), sessionId);
        sessionLastActivity.remove(sessionId);
        // Always clean staffToSession regardless of whether the staff is online;
        // the per-close lambdas only fire for online staff, leaving stale entries
        // that block staff from claiming new sessions after reconnecting.
        if (session.getStaffId() != null) {
            staffToSession.remove(session.getStaffId());
        }
    }

    public boolean canRequestSupport(Player player, String lang) {
        if (hasPermission(player, getStaffPermission())) {
            CompatibilityHelper.sendMessage(player, getMessage("support.staff-cannot-request"));
            return false;
        }
        String banMsg = getBanMessage(player.getUniqueId(), lang);
        if (banMsg != null) {
            CompatibilityHelper.sendMessage(player, CompatibilityHelper.colorize(banMsg));
            return false;
        }
        if (playerToSession.containsKey(player.getUniqueId())) {
            CompatibilityHelper.sendMessage(player, getMessage("support.already-in-session"));
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
            final String playerName0 = player.getUsername();
            final String sessionIdStr = session.getSessionId().toString();
            ioExecutor.execute(() -> plugin.getDiscordBot().createSupportChannel(playerName0, serverName, language, sessionIdStr));
        }

        Component component = getMessage("support.staff-notification",
                "player", player.getUsername(),
                "server", serverName,
                "language", language.toUpperCase()
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
            CompatibilityHelper.sendMessage(staff, getMessage("general.player-not-found", "player", playerName));
            return false;
        }

        Player target = targetOpt.get();
        UUID sessionId = playerToSession.get(target.getUniqueId());
        if (sessionId == null) {
            CompatibilityHelper.sendMessage(staff, getMessage("support.no-active-session-for-player", "player", playerName));
            return false;
        }

        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            CompatibilityHelper.sendMessage(staff, getMessage("support.no-active-session-for-player", "player", playerName));
            return false;
        }

        synchronized (session) {
            // Re-verify the session is still active — it may have been auto-closed between
            // the activeSessions.get() above and now.
            if (activeSessions.get(sessionId) != session) {
                CompatibilityHelper.sendMessage(staff, getMessage("support.no-active-session-for-player", "player", playerName));
                return false;
            }

            UUID existingStaffSession = staffToSession.get(staff.getUniqueId());
            if (existingStaffSession != null && !existingStaffSession.equals(sessionId)) {
                CompatibilityHelper.sendMessage(staff, getMessage("support.already-handling-session"));
                return false;
            }

            boolean alreadyClaimed = session.getStaffId() != null || sessionToDiscordStaff.containsKey(sessionId);
            if (alreadyClaimed && !force) {
                String existingStaff = session.getStaffName() != null ? session.getStaffName() : "a Discord team member";
                CompatibilityHelper.sendMessage(staff, getMessage("support.already-claimed", "staff", existingStaff));
                return false;
            }

            if (force) {
                String prevDiscordStaff = sessionToDiscordStaff.remove(sessionId);
                if (prevDiscordStaff != null) discordStaffToSession.remove(prevDiscordStaff);
                // Remove the displaced in-game staff's claim so they can no longer
                // /spc or /support close a ticket they no longer own.
                if (session.getStaffId() != null) {
                    staffToSession.remove(session.getStaffId());
                }
            }

            session.setStaffId(staff.getUniqueId());
            session.setStaffName(staff.getUsername());
            session.setDiscordOnly(false);
            staffToSession.put(staff.getUniqueId(), sessionId);
            // Mirror the Discord claim path: a fresh claim counts as activity so the
            // ticket isn't auto-closed 1h after creation just because chat is quiet.
            sessionLastActivity.put(sessionId, Instant.now());
        }

        CompatibilityHelper.sendMessage(staff, getMessage("support.claimed-by-you", "player", target.getUsername()));
        CompatibilityHelper.sendMessage(target, getMessage("support.claimed-by-staff", "staff", staff.getUsername()));

        if (plugin.getDiscordBot() != null) {
            final String sid = session.getSessionId().toString();
            final String sName = staff.getUsername();
            final String sId = staff.getUniqueId().toString();
            ioExecutor.execute(() -> plugin.getDiscordBot().updateChannelToClaimed(sid, sName, sId));
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

        synchronized (session) {
            if (activeSessions.get(sessionId) != session) return false;

            UUID existingDiscordSession = discordStaffToSession.get(discordStaffId);
            if (existingDiscordSession != null && !existingDiscordSession.equals(sessionId)) return false;

            boolean alreadyClaimed = session.getStaffId() != null || sessionToDiscordStaff.containsKey(sessionId);
            if (alreadyClaimed && !force) return false;

            if (force) {
                // Remove the displaced in-game staff's claim so they can no longer
                // /spc or /support close a ticket they no longer own.
                if (session.getStaffId() != null) {
                    staffToSession.remove(session.getStaffId());
                }
                // Remove any other Discord staff claim on this session.
                String prevDiscordStaff = sessionToDiscordStaff.remove(sessionId);
                if (prevDiscordStaff != null) discordStaffToSession.remove(prevDiscordStaff);
                session.setStaffId(null);
            }

            session.setStaffName(staffName);
            session.setDiscordOnly(true);
            discordStaffToSession.put(discordStaffId, sessionId);
            sessionToDiscordStaff.put(sessionId, discordStaffId);
            sessionLastActivity.put(sessionId, Instant.now());
        }

        CompatibilityHelper.sendMessage(targetOpt.get(), getMessage("support.claimed-by-staff", "staff", staffName));
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
        // Atomic claim — see autoCloseSession.
        SupportSession session = activeSessions.remove(sessionId);
        if (session == null) {
            CompatibilityHelper.sendMessage(closer, getMessage("support.no-open-ticket"));
            return;
        }

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
            if (!staff.getUniqueId().equals(closer.getUniqueId())) {
                CompatibilityHelper.sendMessage(staff, getMessage("support.ticket-closed-staff", "player", session.getPlayerName()));
            }
        });

        String prevDiscord2 = sessionToDiscordStaff.remove(sessionId);
        if (prevDiscord2 != null) discordStaffToSession.remove(prevDiscord2);
        playerToSession.remove(session.getPlayerId());

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        if (playerOpt.isPresent()) {
            CompatibilityHelper.sendMessage(playerOpt.get(), getMessage("support.ticket-closed"));
            sendRatingPrompt(playerOpt.get());
        } else {
            pendingNotifications.put(session.getPlayerId(), new ClosedSessionNotification(sessionId, Instant.now(), session.getLanguage()));
        }

        logSessionAction(session, "CLOSED", "Closed by " + closer.getUsername());
        finalizeSession(session);

        if (plugin.getDiscordBot() != null) {
            final String sid = session.getSessionId().toString();
            final String lang = session.getLanguage();
            final String pname = session.getPlayerName();
            final Instant createdAt = session.getCreatedAt();
            ioExecutor.execute(() -> plugin.getDiscordBot().closeSupportChannel(sid, lang, pname, createdAt));
        }
    }

    public boolean closeSupportSessionFromDiscord(String discordStaffId) {
        UUID sessionId = discordStaffToSession.get(discordStaffId);
        if (sessionId == null) return false;
        return closeSessionCore(sessionId, discordStaffId, "CLOSED_DISCORD", "Closed by Discord team member");
    }

    public boolean forceCloseSessionByUUID(UUID sessionId) {
        return closeSessionCore(sessionId, null, "FORCE_CLOSED_DISCORD", "Force-closed by management via Discord");
    }

    private boolean closeSessionCore(UUID sessionId, String discordStaffId, String action, String detail) {
        // Atomic claim — see autoCloseSession.
        SupportSession session = activeSessions.remove(sessionId);
        if (session == null) return false;

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff ->
            CompatibilityHelper.sendMessage(staff, getMessage("support.ticket-closed-staff", "player", session.getPlayerName())));

        if (discordStaffId != null) {
            discordStaffToSession.remove(discordStaffId);
            sessionToDiscordStaff.remove(sessionId);
        } else {
            String prevDiscord3 = sessionToDiscordStaff.remove(sessionId);
            if (prevDiscord3 != null) discordStaffToSession.remove(prevDiscord3);
        }
        playerToSession.remove(session.getPlayerId());

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        if (playerOpt.isPresent()) {
            CompatibilityHelper.sendMessage(playerOpt.get(), getMessage("support.ticket-closed"));
            sendRatingPrompt(playerOpt.get());
        } else {
            pendingNotifications.put(session.getPlayerId(), new ClosedSessionNotification(sessionId, Instant.now(), session.getLanguage()));
        }

        logSessionAction(session, action, detail);
        finalizeSession(session);

        if (plugin.getDiscordBot() != null) {
            final String sid = session.getSessionId().toString();
            final String lang = session.getLanguage();
            final String pname = session.getPlayerName();
            final Instant createdAt = session.getCreatedAt();
            ioExecutor.execute(() -> plugin.getDiscordBot().closeSupportChannel(sid, lang, pname, createdAt));
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
                "player", sender.getUsername(),
                "message", CompatibilityHelper.escapeMiniMessage(message)
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

    /**
     * Relay a Discord staff message to the in-game player and any in-game staff on the session.
     * Identified by sessionId so any Discord staff member in the channel can relay, not just
     * the original claimer (whose discordStaffToSession entry is used for close/claim logic).
     */
    public void handleSupportChatFromDiscord(String sessionIdStr, String senderName, String discordUserId, String message) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) return;

        sessionLastActivity.put(sessionId, Instant.now());

        Component chatMessage = getMessage("support.staff-chat-format",
                "player", "Discord",
                "message", CompatibilityHelper.escapeMiniMessage(message)
        );

        plugin.getServer().getPlayer(session.getPlayerId())
                .ifPresent(p -> CompatibilityHelper.sendMessage(p, chatMessage));

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer)
                .ifPresent(s -> CompatibilityHelper.sendMessage(s, chatMessage));

        logSessionChat(session, "Discord/" + senderName + "#" + discordUserId, message);
    }

    private void sendRatingPrompt(Player player) {
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
        CompatibilityHelper.sendMessage(player, getMessage("support.rating-received", "rating", String.valueOf(rating)));

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff ->
                CompatibilityHelper.sendMessage(staff, CompatibilityHelper.colorize(
                        "<gray>{player} rated this session <white>{rating}/5<gray>.",
                        "player", CompatibilityHelper.escapeMiniMessage(player.getUsername()),
                        "rating", String.valueOf(rating))));

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().sendRatingEmbedToChannel(session.getSessionId().toString(), rating, player.getUsername(), staffName);
        }

        logSessionAction(session, "RATED", player.getUsername() + " rated " + rating + "/5");
    }

    public void handlePlayerJoin(Player player) {
        ClosedSessionNotification note = pendingNotifications.remove(player.getUniqueId());
        if (note != null && note.closedAt().plusSeconds(3600).isAfter(Instant.now())) {
            CompatibilityHelper.sendMessage(player, getMessage("support.ticket-auto-closed"));
            sendRatingPrompt(player);
        }

        UUID sessionId = playerToSession.get(player.getUniqueId());
        if (sessionId != null) {
            SupportSession session = activeSessions.get(sessionId);
            if (session != null) {
                // Notify in-game staff regardless of whether a Discord staff also claimed.
                session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff ->
                        CompatibilityHelper.sendMessage(staff, getMessage("support.player-online-status", "player", player.getUsername())));
                // Always notify the Discord channel — Discord-only sessions have no in-game
                // staffId, so nesting this inside ifPresent would silently skip the update.
                if (plugin.getDiscordBot() != null) {
                    plugin.getDiscordBot().sendStatusMessageToChannel(sessionId.toString(), player.getUsername() + " is online");
                }
            }
        }
    }

    public void handlePlayerLeave(Player player) {
        UUID sessionId = playerToSession.get(player.getUniqueId());
        if (sessionId != null) {
            SupportSession session = activeSessions.get(sessionId);
            if (session != null) {
                session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff ->
                        CompatibilityHelper.sendMessage(staff, getMessage("support.player-offline-status", "player", player.getUsername())));
                if (plugin.getDiscordBot() != null) {
                    plugin.getDiscordBot().sendStatusMessageToChannel(sessionId.toString(), player.getUsername() + " went offline");
                }
            }
        }
    }

    public void banPlayer(Player staff, String playerName, String durationStr) {
        long millis = DurationUtil.parse(durationStr);
        if (millis <= 0) {
            CompatibilityHelper.sendMessage(staff, getMessage("support.invalid-duration"));
            return;
        }
        Optional<Player> onlineOpt = plugin.getServer().getPlayer(playerName);
        if (onlineOpt.isPresent()) {
            applyBan(staff, onlineOpt.get().getUniqueId(), onlineOpt.get().getUsername(), millis);
            return;
        }
        // Offline path: look up by name in the database.
        if (playerDataDAO == null) {
            CompatibilityHelper.sendMessage(staff, getMessage("general.player-not-found", "player", playerName));
            return;
        }
        playerDataDAO.getPlayerDataByName(playerName).thenAccept(data -> {
            if (data == null) {
                CompatibilityHelper.sendMessage(staff, getMessage("general.player-not-found", "player", playerName));
                return;
            }
            applyBan(staff, data.getUuid(), data.getUsername(), millis);
        });
    }

    private void applyBan(Player staff, UUID targetUuid, String targetName, long millis) {
        BanEntry ban = new BanEntry(targetUuid, staff.getUniqueId(), Instant.now(), millis);
        bans.put(targetUuid, ban);
        saveBans();
        String formatted = DurationUtil.format(millis);
        CompatibilityHelper.sendMessage(staff, getMessage("support.player-banned", "player", targetName, "duration", formatted));
        plugin.getServer().getPlayer(targetUuid)
                .ifPresent(t -> CompatibilityHelper.sendMessage(t, getMessage("support.you-are-banned", "duration", formatted)));
    }

    public void unbanPlayer(Player staff, String playerName) {
        Optional<Player> onlineOpt = plugin.getServer().getPlayer(playerName);
        if (onlineOpt.isPresent()) {
            applyUnban(staff, onlineOpt.get().getUniqueId(), onlineOpt.get().getUsername());
            return;
        }
        // Offline path: look up by name in the database.
        if (playerDataDAO == null) {
            CompatibilityHelper.sendMessage(staff, getMessage("general.player-not-found", "player", playerName));
            return;
        }
        playerDataDAO.getPlayerDataByName(playerName).thenAccept(data -> {
            if (data == null) {
                CompatibilityHelper.sendMessage(staff, getMessage("general.player-not-found", "player", playerName));
                return;
            }
            applyUnban(staff, data.getUuid(), data.getUsername());
        });
    }

    private void applyUnban(Player staff, UUID targetUuid, String targetName) {
        if (bans.remove(targetUuid) != null) {
            saveBans();
            CompatibilityHelper.sendMessage(staff, getMessage("support.player-unbanned", "player", targetName));
            plugin.getServer().getPlayer(targetUuid)
                    .ifPresent(t -> CompatibilityHelper.sendMessage(t, getMessage("support.you-are-unbanned")));
        } else {
            CompatibilityHelper.sendMessage(staff, getMessage("support.not-banned", "player", targetName));
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
        return configManager.getMessages().node("support", "you-are-banned").getString("<red>You are banned from the support system. Remaining: <white>{duration}<red>.").replace("{duration}", timeLeft);
    }

    private Component getMessage(String path, String... pairs) {
        String template = configManager.getMessages().node((Object[]) path.split("\\.")).getString("");
        if (template == null || template.isEmpty()) {
            return Component.text("Error: Message for " + path + " not found.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED);
        }
        return CompatibilityHelper.colorize(template, pairs);
    }

    private String getStaffPermission() {
        return configManager.getConfig().node("support", "staff-permission").getString("velocitycore.support.staff");
    }

    private boolean hasPermission(Player p, String permission) {
        // Check the specific permission directly; also honour the wildcard only when
        // the requested permission is a sub-node of the staff base permission.
        String base = getStaffPermission();
        boolean wildcardApplies = permission.startsWith(base + ".") || permission.equals(base);
        return p.hasPermission(permission) || (wildcardApplies && p.hasPermission(base + ".*"));
    }

    private void logSessionAction(SupportSession session, String action, String detail) {
        String line = String.format("[%s UTC] [%s] %s | player=%s | staff=%s | %s%n",
                logFormatter.format(Instant.now()),
                session.getSessionId().toString().substring(0, 8),
                action,
                session.getPlayerName(),
                session.getStaffName() != null ? session.getStaffName() : "none",
                detail);
        appendLog("sessions.log", line);
    }

    private void logSessionChat(SupportSession session, String author, String message) {
        String line = String.format("[%s UTC] [%s] %s: %s%n",
                logFormatter.format(Instant.now()),
                session.getSessionId().toString().substring(0, 8),
                author, message);
        appendLog("chat.log", line);
    }

    private void appendLog(String filename, String content) {
        Path dir = plugin.getDataDirectory().resolve("logs");
        // Directory is guaranteed by SupportPlugin.initDirectories() at startup
        ioExecutor.execute(() -> {
            try {
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
