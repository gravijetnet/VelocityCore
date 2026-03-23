package net.gravijet.support.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public class BanEntry {
    private final UUID playerId;
    private final UUID bannedBy;
    private final long bannedAtEpoch;
    private final long duration; // millis; Long.MAX_VALUE = permanent

    /** Jackson deserialization constructor */
    @JsonCreator
    public BanEntry(
            @JsonProperty("playerId") String playerId,
            @JsonProperty("bannedBy") String bannedBy,
            @JsonProperty("bannedAt") long bannedAtEpoch,
            @JsonProperty("duration") long duration) {
        this.playerId = UUID.fromString(playerId);
        this.bannedBy = UUID.fromString(bannedBy);
        this.bannedAtEpoch = bannedAtEpoch;
        this.duration = duration;
    }

    /** Runtime constructor */
    public BanEntry(UUID playerId, UUID bannedBy, Instant bannedAt, long duration) {
        this.playerId = playerId;
        this.bannedBy = bannedBy;
        this.bannedAtEpoch = bannedAt.toEpochMilli();
        this.duration = duration;
    }

    @JsonProperty("playerId")
    public String getPlayerIdString() { return playerId.toString(); }

    @JsonProperty("bannedBy")
    public String getBannedByString() { return bannedBy.toString(); }

    @JsonProperty("bannedAt")
    public long getBannedAtEpoch() { return bannedAtEpoch; }

    @JsonProperty("duration")
    public long getDuration() { return duration; }

    public UUID getPlayerId() { return playerId; }
    public UUID getBannedBy() { return bannedBy; }
    public Instant getBannedAt() { return Instant.ofEpochMilli(bannedAtEpoch); }

    public boolean isExpired() {
        if (duration == Long.MAX_VALUE) return false;
        return System.currentTimeMillis() > bannedAtEpoch + duration;
    }

    public long getRemainingMillis() {
        if (duration == Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0L, bannedAtEpoch + duration - System.currentTimeMillis());
    }
}
