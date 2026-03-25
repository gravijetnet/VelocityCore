package net.gravijet.velocity.core.managers;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.database.DatabaseManager;
import net.gravijet.velocity.core.database.models.PlayerData;

import java.sql.*;
import java.time.Month;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TokenManager {
    private final DatabaseManager databaseManager;
    private final ProxyServer proxy;
    // practical upper bound for permission-based token amounts (server admins can set large numbers)
    private static final int TOKEN_PERMISSION_UPPER_BOUND = 1000000;

    public TokenManager(DatabaseManager databaseManager, ProxyServer proxy) {
        this.databaseManager = databaseManager;
        this.proxy = proxy;
    }

    public CompletableFuture<PlayerData> getPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection()) {
                String sql = "SELECT * FROM player_data WHERE uuid = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, uuid.toString());

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    PlayerData data = new PlayerData(
                            uuid,
                            rs.getString("username"),
                            rs.getInt("monthly_tokens"),
                            rs.getInt("permanent_tokens"),
                            rs.getInt("last_reset_month"),
                            rs.getString("color"),
                            rs.getString("last_server"),
                            rs.getLong("last_online")
                    );
                    // Check for monthly reset when loading data
                    checkMonthlyReset(data);
                    return data;
                } else {
                    return createPlayerData(uuid);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private PlayerData createPlayerData(UUID uuid) {
        String username = proxy.getPlayer(uuid).map(player -> player.getUsername()).orElse("Unknown");
        PlayerData data = new PlayerData(uuid, username, 0, 2, getCurrentMonth(), null, null, 0);
        savePlayerData(data);
        return data;
    }

    public CompletableFuture<Boolean> useToken(UUID uuid) {
        return getPlayerData(uuid).thenApplyAsync(data -> {
            if (data == null) return false;

            // Check monthly reset before using token
            checkMonthlyReset(data);

            // Check for unlimited permission - don't consume tokens
            if (hasPermission(uuid, "core.joinme.tokens.unlimited")) {
                return true;
            }

            if (data.getTotalTokens() <= 0) {
                return false;
            }

            if (data.getMonthlyTokens() > 0) {
                data.setMonthlyTokens(data.getMonthlyTokens() - 1);
            } else if (data.getPermanentTokens() > 0) {
                data.setPermanentTokens(data.getPermanentTokens() - 1);
            } else {
                return false;
            }

            return savePlayerData(data);
        });
    }

    private int safeAdd(int a, int b) {
        long res = (long) a + (long) b;
        if (res >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (res <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) res;
    }

    public CompletableFuture<Boolean> addPermanentTokens(UUID uuid, int amount) {
        return getPlayerData(uuid).thenApplyAsync(data -> {
            if (data != null) {
                int newAmount = safeAdd(data.getPermanentTokens(), amount);
                data.setPermanentTokens(newAmount);
                return savePlayerData(data);
            }
            return false;
        });
    }

    public CompletableFuture<Boolean> removePermanentTokens(UUID uuid, int amount) {
        return getPlayerData(uuid).thenApplyAsync(data -> {
            if (data != null) {
                int newAmount = Math.max(0, data.getPermanentTokens() - amount);
                data.setPermanentTokens(newAmount);
                return savePlayerData(data);
            }
            return false;
        });
    }

    private void checkMonthlyReset(PlayerData data) {
        int currentMonth = getCurrentMonth();
        if (data.getLastResetMonth() != currentMonth) {
            data.setMonthlyTokens(calculateMonthlyTokens(data.getUuid()));
            data.setLastResetMonth(currentMonth);
            savePlayerData(data);
        }
    }

    private int calculateMonthlyTokens(UUID uuid) {
        if (hasPermission(uuid, "core.joinme.tokens.unlimited")) {
            return Integer.MAX_VALUE;
        }

        if (hasPermission(uuid, "core.joinme.tokens.*") || hasPermission(uuid, "core.*")) {
            return Integer.MAX_VALUE;
        }

        for (int i = TOKEN_PERMISSION_UPPER_BOUND; i >= 1; i--) {
            if (hasPermission(uuid, "core.joinme.tokens." + i)) {
                return i;
            }
        }

        return 0;
    }

    private boolean hasPermission(UUID uuid, String permission) {
        if ("core.joinme.use".equals(permission) || "core.joinme.tokens.view".equals(permission)) {
            return proxy.getPlayer(uuid).map(player -> true).orElse(true);
        }

        return proxy.getPlayer(uuid)
                .map(player -> player.hasPermission("core.*") || player.hasPermission(permission))
                .orElse(false);
    }

    private int getCurrentMonth() {
        return Month.from(java.time.LocalDate.now()).getValue();
    }

    private boolean savePlayerData(PlayerData data) {
        try (Connection conn = databaseManager.getConnection()) {
            int monthly = Math.max(0, Math.min(data.getMonthlyTokens(), Integer.MAX_VALUE));
            int perm = Math.max(0, Math.min(data.getPermanentTokens(), Integer.MAX_VALUE));
            data.setMonthlyTokens(monthly);
            data.setPermanentTokens(perm);

            String sql = "INSERT INTO player_data (uuid, username, monthly_tokens, permanent_tokens, last_reset_month, color, last_server, last_online) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                    "username = VALUES(username), monthly_tokens = VALUES(monthly_tokens), " +
                    "permanent_tokens = VALUES(permanent_tokens), last_reset_month = VALUES(last_reset_month), " +
                    "color = VALUES(color), last_server = VALUES(last_server), last_online = VALUES(last_online)";

            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, data.getUuid().toString());
            stmt.setString(2, data.getUsername());
            stmt.setInt(3, monthly);
            stmt.setInt(4, perm);
            stmt.setInt(5, data.getLastResetMonth());
            stmt.setString(6, data.getColor());
            stmt.setString(7, data.getLastServer());
            stmt.setLong(8, data.getLastOnline());

            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public CompletableFuture<String> getDetailedTokenInfo(UUID uuid) {
        return getPlayerData(uuid).thenApply(data -> {
            if (data == null) return "§cPlayer data not found";

            int expectedMonthly = calculateMonthlyTokens(uuid);
            boolean hasUnlimited = hasPermission(uuid, "core.joinme.tokens.unlimited");

            String unlimitedInfo = hasUnlimited ? "\n§aUnlimited: §9Yes" : "§aUnlimited: §9No";

            return "§aMonthly tokens: §9" + data.getMonthlyTokens() + " " +
                    unlimitedInfo + "\n" +
                    "§aTokens: §9" + data.getPermanentTokens() + "\n" +
                    "§aTotal tokens: §9" + data.getTotalTokens() + "\n" +
                    "§aLast reset: §9" + data.getLastResetMonth() + " §7(Current: §9" + getCurrentMonth() + "§7)";
        });
    }

    public CompletableFuture<Boolean> recalculateMonthlyTokens(UUID uuid) {
        return getPlayerData(uuid).thenApplyAsync(data -> {
            if (data != null) {
                data.setMonthlyTokens(calculateMonthlyTokens(uuid));
                data.setLastResetMonth(getCurrentMonth());
                return savePlayerData(data);
            }
            return false;
        });
    }

    public boolean hasUnlimitedTokens(UUID uuid) {
        return hasPermission(uuid, "core.joinme.tokens.unlimited");
    }

    public CompletableFuture<Boolean> setPlayerColor(UUID uuid, String color) {
        return getPlayerData(uuid).thenApplyAsync(data -> {
            if (data == null) return false;
            data.setColor(color);
            return savePlayerData(data);
        });
    }
}
