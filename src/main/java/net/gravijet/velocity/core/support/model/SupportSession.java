package net.gravijet.velocity.core.support.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class SupportSession {
    private final UUID sessionId;
    private final UUID playerId;
    private final String playerName;
    private UUID staffId;
    private String staffName;
    private String language;
    private final Instant createdAt;
    private Instant closedAt;
    private boolean active;
    private boolean discordOnly;

    public SupportSession(UUID playerId, String playerName, String language, Instant createdAt) {
        this.sessionId = UUID.randomUUID();
        this.playerId = playerId;
        this.playerName = playerName;
        this.language = language != null ? language.toUpperCase() : "UNKNOWN";
        this.createdAt = createdAt;
        this.active = true;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }

    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }
    public Optional<UUID> getStaffIdOpt() { return Optional.ofNullable(staffId); }

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language != null ? language.toUpperCase() : "UNKNOWN"; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isDiscordOnly() { return discordOnly; }
    public void setDiscordOnly(boolean discordOnly) { this.discordOnly = discordOnly; }
}
