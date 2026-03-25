package net.gravijet.velocity.core.database.models;

import java.util.UUID;

public class PlayerData {
    private final UUID uuid;
    private String username;
    private int monthlyTokens;
    private int permanentTokens;
    private int lastResetMonth;
    private String color;
    private String lastServer;
    private long lastOnline;

    public PlayerData(UUID uuid, String username, int monthlyTokens, int permanentTokens, int lastResetMonth,
                      String color, String lastServer, long lastOnline) {
        this.uuid = uuid;
        this.username = username;
        this.monthlyTokens = monthlyTokens;
        this.permanentTokens = permanentTokens;
        this.lastResetMonth = lastResetMonth;
        this.color = color;
        this.lastServer = lastServer;
        this.lastOnline = lastOnline;
    }

    public UUID getUuid() { return uuid; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getMonthlyTokens() { return monthlyTokens; }
    public void setMonthlyTokens(int monthlyTokens) { this.monthlyTokens = monthlyTokens; }
    public int getPermanentTokens() { return permanentTokens; }
    public void setPermanentTokens(int permanentTokens) { this.permanentTokens = permanentTokens; }
    public int getLastResetMonth() { return lastResetMonth; }
    public void setLastResetMonth(int lastResetMonth) { this.lastResetMonth = lastResetMonth; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getLastServer() { return lastServer; }
    public void setLastServer(String lastServer) { this.lastServer = lastServer; }
    public long getLastOnline() { return lastOnline; }
    public void setLastOnline(long lastOnline) { this.lastOnline = lastOnline; }

    public int getTotalTokens() {
        long sum = (long) monthlyTokens + (long) permanentTokens;
        if (sum >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(0, sum);
    }
}
