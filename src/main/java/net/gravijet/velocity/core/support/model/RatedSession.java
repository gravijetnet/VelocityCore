package net.gravijet.velocity.core.support.model;

import java.time.Instant;
import java.util.UUID;

public class RatedSession {
    private final UUID sessionId;
    private final int rating;
    private final Instant ratedAt;
    private final String staffName;

    public RatedSession(UUID sessionId, int rating, Instant ratedAt, String staffName) {
        this.sessionId = sessionId;
        this.rating = rating;
        this.ratedAt = ratedAt;
        this.staffName = staffName != null ? staffName : "Unknown";
    }

    public UUID getSessionId() { return sessionId; }
    public int getRating() { return rating; }
    public Instant getRatedAt() { return ratedAt; }
    public String getStaffName() { return staffName; }
}
