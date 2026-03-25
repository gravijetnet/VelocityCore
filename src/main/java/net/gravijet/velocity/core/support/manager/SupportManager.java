package net.gravijet.velocity.core.support.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.gravijet.velocity.core.support.SupportPlugin;
import net.gravijet.velocity.core.support.config.SupportConfig;
import net.gravijet.velocity.core.support.model.BanEntry;
import net.gravijet.velocity.core.support.model.RatedSession;
import net.gravijet.velocity.core.support.model.SupportSession;
import net.gravijet.velocity.core.support.util.DurationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
import java.util.concurrent.TimeUnit;

public class SupportManager {
    private final SupportPlugin plugin;
    private final SupportConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final File bansFile;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();
    private final DateTimeFormatter logFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private ScheduledTask autoCloseTask;

    // Active sessions
    private final Map<UUID, SupportSession> activeSessions       = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>           playerToSession      = new ConcurrentHashMap<>(); // playerId -> sessionId
    private final Map<UUID, UUID>           staffToSession       = new ConcurrentHashMap<>(); // staffId -> sessionId
    private final Map<String, UUID>         discordStaffToSession = new ConcurrentHashMap<>(); // discordId -> sessionId

    // Closed sessions & ratings
    private final Map<UUID, SupportSession>          closedSessions      = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>                    playerToLastClosed  = new ConcurrentHashMap<>(); // playerId -> last closed sessionId
    private final Map<UUID, RatedSession>            ratedSessions       = new ConcurrentHashMap<>();
    private final Map<UUID, Instant>                 sessionLastActivity = new ConcurrentHashMap<>();
    private final Map<UUID, ClosedSessionNotification> pendingNotifications = new ConcurrentHashMap<>();

    // Bans
    private final Map<UUID, BanEntry> bans = new ConcurrentHashMap<>();

    // Discord <-> Minecraft name links
    private final Map<String, String> linkedAccounts   = new ConcurrentHashMap<>(); // discordId -> mcName
    private final File                linkedAccountsFile;

    public SupportManager(SupportPlugin plugin, SupportConfig config) {
        this.plugin              = plugin;
        this.config              = config;
        this.bansFile            = new File(plugin.getDataDirectory().toFile(), "bans.json");
        this.linkedAccountsFile  = new File(plugin.getDataDirectory().toFile(), "linked_accounts.json");
        loadBans();
        loadLinkedAccounts();
        startAutoCloseTask();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

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
    }

    private void checkInactiveSessions() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        // Remove expired rating windows from pending notifications
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
            staff.sendMessage(serializer.deserialize(config.getSupportClosedStaff().replace("{player}", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        playerToSession.remove(session.getPlayerId()); // always remove, online or not
        if (playerOpt.isPresent()) {
            playerOpt.get().sendMessage(serializer.deserialize(config.getSupportAutoClosedPlayer(session.getLanguage())));
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

    // -------------------------------------------------------------------------
    // Support request
    // -------------------------------------------------------------------------

    public boolean canRequestSupport(Player player, String lang) {
        if (hasPermission(player, config.getPermissions().supportClaim)) {
            player.sendMessage(serializer.deserialize(config.getStaffCannotRequest(lang)));
            return false;
        }
        String banMsg = getBanMessage(player.getUniqueId(), lang);
        if (banMsg != null) {
            player.sendMessage(serializer.deserialize(banMsg));
            return false;
        }
        if (playerToSession.containsKey(player.getUniqueId())) {
            player.sendMessage(serializer.deserialize(config.getAlreadyInSession(lang)));
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

        String msg = config.getSupportRequestReceived()
                .replace("{player}", player.getUsername())
                .replace("{server}", serverName)
                .replace("{language}", language.toUpperCase());
        Component component = serializer.deserialize(msg)
                .clickEvent(ClickEvent.suggestCommand("/support claim " + player.getUsername()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to claim this request")));
        plugin.getServer().getAllPlayers().stream()
                .filter(p -> hasPermission(p, config.getPermissions().supportRequests))
                .forEach(p -> p.sendMessage(component));

        player.sendMessage(serializer.deserialize(config.getSupportRequestSent(language)));
        logSessionAction(session, "CREATED", "Player requested support (" + language.toUpperCase() + ")");
    }

    // -------------------------------------------------------------------------
    // Claim
    // -------------------------------------------------------------------------

    public boolean claimSupport(Player staff, String playerName, boolean force) {
        Optional<Player> targetOpt = plugin.getServer().getPlayer(playerName);
        if (targetOpt.isEmpty()) {
            staff.sendMessage(serializer.deserialize(config.getPlayerNotFound()));
            return false;
        }

        Player target = targetOpt.get();
        UUID sessionId = playerToSession.get(target.getUniqueId());
        if (sessionId == null) {
            staff.sendMessage(serializer.deserialize("&cNo active support session found for this player."));
            return false;
        }

        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            staff.sendMessage(serializer.deserialize("&cNo active support session found for this player."));
            return false;
        }

        // A staff member cannot handle two sessions at once, even with force
        UUID existingStaffSession = staffToSession.get(staff.getUniqueId());
        if (existingStaffSession != null && !existingStaffSession.equals(sessionId)) {
            staff.sendMessage(serializer.deserialize("&cYou are already handling a support session."));
            return false;
        }

        boolean alreadyClaimed = session.getStaffId() != null || discordStaffToSession.containsValue(sessionId);
        if (alreadyClaimed && !force) {
            String existingStaff = session.getStaffName() != null ? session.getStaffName() : "a Discord team member";
            staff.sendMessage(serializer.deserialize(config.getSupportAlreadyClaimed().replace("{staff}", existingStaff)));
            return false;
        }

        if (force) {
            discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
        }

        session.setStaffId(staff.getUniqueId());
        session.setStaffName(staff.getUsername());
        session.setDiscordOnly(false);
        staffToSession.put(staff.getUniqueId(), sessionId);

        staff.sendMessage(serializer.deserialize(config.getSupportClaimed().replace("{player}", target.getUsername())));
        target.sendMessage(serializer.deserialize(config.getSupportClaimedPlayer(session.getLanguage())));

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

        // A Discord staff member cannot handle two sessions at once
        UUID existingDiscordSession = discordStaffToSession.get(discordStaffId);
        if (existingDiscordSession != null && !existingDiscordSession.equals(sessionId)) return false;

        boolean alreadyClaimed = session.getStaffId() != null || discordStaffToSession.containsValue(sessionId);
        if (alreadyClaimed && !force) return false;

        session.setStaffName(staffName);
        session.setDiscordOnly(true);
        discordStaffToSession.put(discordStaffId, sessionId);
        sessionLastActivity.put(sessionId, Instant.now());

        targetOpt.get().sendMessage(serializer.deserialize(config.getSupportClaimedPlayer(session.getLanguage())));
        logSessionAction(session, "CLAIMED_DISCORD", "Claimed by Discord user " + staffName);
        return true;
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    public void closeSupportSession(Player closer) {
        boolean isStaff = hasPermission(closer, config.getPermissions().supportClose);
        UUID sessionId = isStaff ? staffToSession.get(closer.getUniqueId()) : playerToSession.get(closer.getUniqueId());

        if (sessionId == null) {
            closer.sendMessage(serializer.deserialize(config.getNoActiveSupport(isStaff ? "en" : getPlayerLanguage(closer))));
            return;
        }
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            closer.sendMessage(serializer.deserialize(config.getNoActiveSupport(isStaff ? "en" : getPlayerLanguage(closer))));
            return;
        }

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
            staff.sendMessage(serializer.deserialize(config.getSupportClosedStaff().replace("{player}", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.entrySet().removeIf(e -> e.getValue().equals(sessionId));
        playerToSession.remove(session.getPlayerId()); // always remove

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        if (playerOpt.isPresent()) {
            playerOpt.get().sendMessage(serializer.deserialize(config.getSupportClosedPlayer(session.getLanguage())));
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
            staff.sendMessage(serializer.deserialize(config.getSupportClosedStaff().replace("{player}", session.getPlayerName())));
            staffToSession.remove(staff.getUniqueId());
        });

        discordStaffToSession.remove(discordStaffId);
        playerToSession.remove(session.getPlayerId());

        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());
        if (playerOpt.isPresent()) {
            playerOpt.get().sendMessage(serializer.deserialize(config.getSupportClosedPlayer(session.getLanguage())));
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

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    public void handleSupportChat(Player sender, String message) {
        boolean isStaff = hasPermission(sender, config.getPermissions().supportChat);
        UUID sessionId = isStaff ? staffToSession.get(sender.getUniqueId()) : playerToSession.get(sender.getUniqueId());

        if (sessionId == null) {
            sender.sendMessage(serializer.deserialize(config.getNoActiveSupport(isStaff ? "en" : getPlayerLanguage(sender))));
            return;
        }
        SupportSession session = activeSessions.get(sessionId);
        if (session == null) {
            sender.sendMessage(serializer.deserialize(config.getNoActiveSupport(isStaff ? "en" : getPlayerLanguage(sender))));
            return;
        }

        sessionLastActivity.put(sessionId, Instant.now());
        Optional<Player> staffOpt  = session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer);
        Optional<Player> playerOpt = plugin.getServer().getPlayer(session.getPlayerId());

        if (isStaff) {
            playerOpt.ifPresent(p -> p.sendMessage(serializer.deserialize(
                    config.getChatFormatPlayerStaff(session.getLanguage()).replace("{message}", message))));
            String staffFmt = config.getChatFormatStaff().replace("{player}", sender.getUsername()).replace("{message}", message);
            staffOpt.filter(s -> !s.getUniqueId().equals(sender.getUniqueId()))
                    .ifPresent(s -> s.sendMessage(serializer.deserialize(staffFmt)));
            sender.sendMessage(serializer.deserialize(staffFmt));
        } else {
            staffOpt.ifPresent(s -> s.sendMessage(serializer.deserialize(
                    config.getChatFormatStaff().replace("{player}", sender.getUsername()).replace("{message}", message))));
            sender.sendMessage(serializer.deserialize(
                    config.getChatFormatPlayer(session.getLanguage()).replace("{message}", message)));
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

        String staffFmt = config.getChatFormatPlayerStaff(session.getLanguage()).replace("{message}", message);
        plugin.getServer().getPlayer(session.getPlayerId())
                .ifPresent(p -> p.sendMessage(serializer.deserialize(staffFmt)));

        // Also relay to the Minecraft staff member if one has claimed the session
        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer)
                .ifPresent(s -> s.sendMessage(serializer.deserialize(
                        config.getChatFormatStaff().replace("{player}", "Discord").replace("{message}", message))));

        logSessionChat(session, "Discord/" + discordStaffId, message);
    }

    // -------------------------------------------------------------------------
    // Rating
    // -------------------------------------------------------------------------

    private void sendRatingPrompt(Player player, SupportSession session) {
        player.sendMessage(serializer.deserialize(config.getRatingPrompt(session.getLanguage())));
        Component row = Component.empty();
        for (int i = 1; i <= 5; i++) {
            Component btn = Component.text("[" + i + "]")
                    .color(net.kyori.adventure.text.format.TextColor.color(0x5865F2))
                    .hoverEvent(HoverEvent.showText(Component.text(i + "/5")))
                    .clickEvent(ClickEvent.runCommand("/support rate " + i));
            row = row.append(btn).append(Component.text(" "));
        }
        player.sendMessage(row);
    }

    public void rateSupport(Player player, int rating) {
        if (rating < 1 || rating > 5) {
            player.sendMessage(serializer.deserialize(config.getInvalidRating(getPlayerLanguage(player))));
            return;
        }

        UUID lastSessionId = playerToLastClosed.get(player.getUniqueId());
        SupportSession session = lastSessionId != null ? closedSessions.get(lastSessionId) : null;

        if (session == null) {
            player.sendMessage(serializer.deserialize(config.getNotSessionPlayer(getPlayerLanguage(player))));
            return;
        }

        String lang = session.getLanguage();

        if (ratedSessions.containsKey(session.getSessionId())) {
            player.sendMessage(serializer.deserialize(config.getAlreadyRated(lang)));
            return;
        }
        if (session.getClosedAt() != null && session.getClosedAt().plusSeconds(3600).isBefore(Instant.now())) {
            player.sendMessage(serializer.deserialize(config.getRatingExpired(lang)));
            return;
        }

        String staffName = session.getStaffName() != null ? session.getStaffName() : "Unknown";
        ratedSessions.put(session.getSessionId(), new RatedSession(session.getSessionId(), rating, Instant.now(), staffName));
        player.sendMessage(serializer.deserialize(config.getRatingReceived(lang).replace("{rating}", String.valueOf(rating))));

        session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff ->
                staff.sendMessage(serializer.deserialize("&7" + player.getUsername() + " rated this session &f" + rating + "/5&7.")));

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().sendRatingEmbedToChannel(session.getSessionId().toString(), rating, player.getUsername(), staffName);
        }

        logSessionAction(session, "RATED", player.getUsername() + " rated " + rating + "/5");
    }

    // -------------------------------------------------------------------------
    // Misc staff commands
    // -------------------------------------------------------------------------

    public void setSessionLanguage(Player staff, String language) {
        UUID sessionId = staffToSession.get(staff.getUniqueId());
        SupportSession session = sessionId != null ? activeSessions.get(sessionId) : null;
        if (session == null) {
            staff.sendMessage(serializer.deserialize(config.getNoActiveSupport("en")));
            return;
        }
        if (!language.equalsIgnoreCase("DE") && !language.equalsIgnoreCase("EN")) {
            staff.sendMessage(serializer.deserialize(config.getInvalidLanguage()));
            return;
        }
        session.setLanguage(language.toUpperCase());
        staff.sendMessage(serializer.deserialize(config.getLanguageSet().replace("{language}", language.toUpperCase())));
        plugin.getServer().getPlayer(session.getPlayerId()).ifPresent(p ->
                p.sendMessage(serializer.deserialize(config.getLanguageSetPlayer(language).replace("{language}", language.toUpperCase()))));
        logSessionAction(session, "LANGUAGE_CHANGED", "Changed to " + language.toUpperCase() + " by " + staff.getUsername());
    }

    public void transferSupport(Player staff, String targetStaffName) {
        UUID sessionId = staffToSession.get(staff.getUniqueId());
        SupportSession session = sessionId != null ? activeSessions.get(sessionId) : null;
        if (session == null) {
            staff.sendMessage(serializer.deserialize(config.getNoActiveSupport("en")));
            return;
        }
        Optional<Player> targetOpt = plugin.getServer().getPlayer(targetStaffName);
        if (targetOpt.isEmpty() || !hasPermission(targetOpt.get(), config.getPermissions().supportClaim)) {
            staff.sendMessage(serializer.deserialize(config.getPlayerNotFound()));
            return;
        }
        Player target = targetOpt.get();
        String prevStaff = staff.getUsername();

        staffToSession.remove(staff.getUniqueId());
        session.setStaffId(target.getUniqueId());
        session.setStaffName(target.getUsername());
        staffToSession.put(target.getUniqueId(), sessionId);

        staff.sendMessage(serializer.deserialize(config.getSupportTransferred().replace("{staff}", target.getUsername())));
        target.sendMessage(serializer.deserialize(config.getSupportTransferredStaff().replace("{player}", session.getPlayerName())));

        if (plugin.getDiscordBot() != null) {
            plugin.getDiscordBot().sendTransferMessageToChannel(sessionId.toString(), prevStaff, target.getUsername());
            plugin.getDiscordBot().updateChannelToClaimed(sessionId.toString(), target.getUsername(), target.getUniqueId().toString());
        }
        logSessionAction(session, "TRANSFERRED", prevStaff + " -> " + target.getUsername());
    }

    public void showSupportSession(Player staff, String playerName) {
        Optional<Player> targetOpt = plugin.getServer().getPlayer(playerName);
        if (targetOpt.isEmpty()) {
            staff.sendMessage(serializer.deserialize(config.getPlayerNotFound()));
            return;
        }
        UUID sessionId = playerToSession.get(targetOpt.get().getUniqueId());
        SupportSession session = sessionId != null ? activeSessions.get(sessionId) : null;
        if (session == null) {
            staff.sendMessage(serializer.deserialize("&cNo active session found for " + playerName + "."));
            return;
        }
        String time = LocalDateTime.ofInstant(session.getCreatedAt(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        staff.sendMessage(serializer.deserialize(config.getSupportShowHeader().replace("{player}", session.getPlayerName())));
        staff.sendMessage(serializer.deserialize(config.getSupportShowLanguage().replace("{language}", session.getLanguage())));
        staff.sendMessage(serializer.deserialize(config.getSupportShowStaff().replace("{staff}", session.getStaffName() != null ? session.getStaffName() : "none")));
        staff.sendMessage(serializer.deserialize(config.getSupportShowCreated().replace("{time}", time)));
    }

    // -------------------------------------------------------------------------
    // Bans
    // -------------------------------------------------------------------------

    public void banPlayer(Player banner, String targetName, String durationStr) {
        Optional<Player> targetOpt = plugin.getServer().getPlayer(targetName);
        if (targetOpt.isEmpty()) {
            banner.sendMessage(serializer.deserialize(config.getPlayerNotFound()));
            return;
        }
        Player target = targetOpt.get();
        long duration = DurationUtil.parse(durationStr);
        if (duration == -1L) {
            banner.sendMessage(serializer.deserialize(config.getInvalidDuration()));
            return;
        }
        BanEntry ban = new BanEntry(target.getUniqueId(), banner.getUniqueId(), Instant.now(), duration);
        bans.put(target.getUniqueId(), ban);
        saveBans();

        target.sendMessage(serializer.deserialize(config.getYouAreBanned(getPlayerLanguage(target), "")));
        banner.sendMessage(serializer.deserialize(config.getPlayerBanned()
                .replace("{player}", target.getUsername())
                .replace("{duration}", duration == Long.MAX_VALUE ? "permanent" : DurationUtil.format(duration))));
    }

    public void unbanPlayer(Player unbanner, String targetName) {
        // Try by online player first
        UUID targetId = plugin.getServer().getPlayer(targetName).map(Player::getUniqueId).orElse(null);
        if (targetId == null) {
            // Fall back to scanning ban list for UUID by name from online players
            targetId = bans.entrySet().stream()
                    .filter(e -> plugin.getServer().getPlayer(e.getKey())
                            .map(p -> p.getUsername().equalsIgnoreCase(targetName)).orElse(false))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
        if (targetId == null || !bans.containsKey(targetId)) {
            unbanner.sendMessage(serializer.deserialize("&cPlayer not found in ban list."));
            return;
        }
        bans.remove(targetId);
        saveBans();
        plugin.getServer().getPlayer(targetId).ifPresent(p ->
                p.sendMessage(serializer.deserialize(config.getYouAreUnbanned(getPlayerLanguage(p)))));
        unbanner.sendMessage(serializer.deserialize(config.getPlayerUnbanned().replace("{player}", targetName)));
    }

    public String getBanMessage(UUID playerId, String lang) {
        BanEntry ban = bans.get(playerId);
        if (ban == null) return null;
        if (ban.isExpired()) {
            bans.remove(playerId);
            saveBans();
            return null;
        }
        String timeLeft = ban.getDuration() == Long.MAX_VALUE ? "" : DurationUtil.format(ban.getRemainingMillis());
        return config.getYouAreBanned(lang, timeLeft);
    }

    // -------------------------------------------------------------------------
    // Player join/leave
    // -------------------------------------------------------------------------

    public void handlePlayerJoin(Player player) {
        ClosedSessionNotification note = pendingNotifications.remove(player.getUniqueId());
        if (note != null && note.closedAt().plusSeconds(3600).isAfter(Instant.now())) {
            player.sendMessage(serializer.deserialize(config.getSupportAutoClosedPlayer(note.language())));
            SupportSession dummy = new SupportSession(player.getUniqueId(), player.getUsername(), note.language(), Instant.now());
            sendRatingPrompt(player, dummy);
        }

        UUID sessionId = playerToSession.get(player.getUniqueId());
        if (sessionId != null) {
            SupportSession session = activeSessions.get(sessionId);
            if (session != null) {
                session.getStaffIdOpt().flatMap(plugin.getServer()::getPlayer).ifPresent(staff -> {
                    staff.sendMessage(serializer.deserialize(config.getPlayerOnlineStatus().replace("{player}", player.getUsername())));
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
                    staff.sendMessage(serializer.deserialize(config.getPlayerOfflineStatus().replace("{player}", player.getUsername())));
                    if (plugin.getDiscordBot() != null) {
                        plugin.getDiscordBot().sendStatusMessageToChannel(sessionId.toString(), player.getUsername() + " went offline");
                    }
                });
            }
        }
    }

    // -------------------------------------------------------------------------
    // Statistics
    // -------------------------------------------------------------------------

    public static class StaffStats {
        public final String staffName;
        public int totalSessions;
        private double ratingSum;
        public final int[] distribution = new int[6]; // index 1-5

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

    public int getTotalRatedCount() { return ratedSessions.size(); }

    public double getGlobalAverageRating() {
        if (ratedSessions.isEmpty()) return 0.0;
        return ratedSessions.values().stream().mapToInt(RatedSession::getRating).average().orElse(0.0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public String getPlayerLanguage(Player player) {
        UUID sessionId = playerToSession.get(player.getUniqueId());
        if (sessionId != null) {
            SupportSession session = activeSessions.get(sessionId);
            if (session != null) return session.getLanguage();
        }
        return "en";
    }

    public UUID getPlayerSessionId(UUID playerId) { return playerToSession.get(playerId); }

    private boolean hasPermission(Player p, String permission) {
        return p.hasPermission(permission) || p.hasPermission("support.*");
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

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
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(bansFile, bans.values().toArray(new BanEntry[0]));
        } catch (IOException e) {
            plugin.getLogger().error("Failed to save bans.json", e);
        }
    }

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------

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
        try {
            Path dir = plugin.getDataDirectory().resolve("logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(filename), content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write log file {}: {}", filename, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Linked accounts (Discord ID <-> Minecraft name)
    // -------------------------------------------------------------------------

    public void linkAccount(String minecraftName, String discordId, Player admin) {
        linkedAccounts.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(minecraftName));
        linkedAccounts.put(discordId, minecraftName);
        saveLinkedAccounts();
        admin.sendMessage(serializer.deserialize("&7Linked &f" + minecraftName + " &7to Discord ID &f" + discordId + "&7."));
    }

    public void unlinkAccount(String minecraftName, Player admin) {
        boolean removed = linkedAccounts.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(minecraftName));
        if (removed) {
            saveLinkedAccounts();
            admin.sendMessage(serializer.deserialize("&7Unlinked &f" + minecraftName + " &7from Discord."));
        } else {
            admin.sendMessage(serializer.deserialize("&cNo linked Discord account found for &f" + minecraftName + "&c."));
        }
    }

    public String getLinkedMinecraftName(String discordId) {
        return linkedAccounts.get(discordId);
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
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(linkedAccountsFile, linkedAccounts);
        } catch (IOException e) {
            plugin.getLogger().error("Failed to save linked_accounts.json", e);
        }
    }

    // -------------------------------------------------------------------------
    // ClosedSessionNotification (record)
    // -------------------------------------------------------------------------

    public record ClosedSessionNotification(UUID sessionId, Instant closedAt, String language) {}
}
