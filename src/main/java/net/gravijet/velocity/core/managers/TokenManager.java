package net.gravijet.velocity.core.managers;

import com.velocitypowered.api.proxy.ProxyServer;
import net.gravijet.velocity.core.database.DatabaseManager;
import net.gravijet.velocity.core.database.models.PlayerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TokenManager {
    private static final Logger logger = LoggerFactory.getLogger(TokenManager.class);
    private final DatabaseManager databaseManager;
    private final ProxyServer proxy;
    // Upper bound for permission-based token amounts. Checked via linear scan, so keep small.
    private static final int TOKEN_PERMISSION_UPPER_BOUND = 1_000;

    public TokenManager(DatabaseManager databaseManager, ProxyServer proxy) {
        this.databaseManager = databaseManager;
        this.proxy = proxy;
    }

    public CompletableFuture<PlayerData> getPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT * FROM player_data WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
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
                }
            } catch (SQLException e) {
                logger.error("Failed to get player data for {}", uuid, e);
                return null;
            }
        });
    }

    private PlayerData createPlayerData(UUID uuid) {
        String username = proxy.getPlayer(uuid).map(player -> player.getUsername()).orElse("Unknown");
        // Seed the monthly allowance on first creation. Without this a player who
        // holds a core.joinme.tokens.<n> permission and joins mid-month gets 0
        // monthly tokens until the next monthly reset. calculateMonthlyTokens
        // returns 0 for offline players, preserving the old behaviour off-thread.
        int monthly = calculateMonthlyTokens(uuid);
        PlayerData data = new PlayerData(uuid, username, monthly, 2, getCurrentYearMonth(), null, null, 0);
        savePlayerData(data);
        return data;
    }

    public CompletableFuture<Boolean> useToken(UUID uuid) {
        // Permission check is fast and avoids a DB round-trip for unlimited players.
        if (hasPermission(uuid, "core.joinme.tokens.unlimited")) {
            return CompletableFuture.completedFuture(true);
        }
        // Use atomic DB UPDATEs to avoid TOCTOU races when two concurrent
        // /joinme calls both see "tokens available" and both succeed.
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection()) {
                // Prefer monthly tokens first.
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE player_data SET monthly_tokens = monthly_tokens - 1 " +
                        "WHERE uuid = ? AND monthly_tokens > 0")) {
                    stmt.setString(1, uuid.toString());
                    if (stmt.executeUpdate() > 0) return true;
                }
                // Fall back to permanent tokens.
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE player_data SET permanent_tokens = permanent_tokens - 1 " +
                        "WHERE uuid = ? AND permanent_tokens > 0")) {
                    stmt.setString(1, uuid.toString());
                    return stmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                logger.error("Failed to use token for {}", uuid, e);
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> addPermanentTokens(UUID uuid, int amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE player_data SET permanent_tokens = LEAST(permanent_tokens + ?, 2147483647) WHERE uuid = ?")) {
                stmt.setInt(1, amount);
                stmt.setString(2, uuid.toString());
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.error("Failed to add permanent tokens for {}", uuid, e);
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> removePermanentTokens(UUID uuid, int amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE player_data SET permanent_tokens = GREATEST(0, permanent_tokens - ?) WHERE uuid = ?")) {
                stmt.setInt(1, amount);
                stmt.setString(2, uuid.toString());
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.error("Failed to remove permanent tokens for {}", uuid, e);
                return false;
            }
        });
    }

    private void checkMonthlyReset(PlayerData data) {
        int currentYearMonth = getCurrentYearMonth();
        int lastReset = data.getLastResetMonth();
        // Values ≤ 12 are the old single-month format (pre-migration); compare month-only
        // so existing records don't spuriously reset on the first login after the upgrade.
        boolean needsReset = (lastReset <= 12)
                ? lastReset != (currentYearMonth % 100)
                : lastReset != currentYearMonth;
        if (!needsReset) return;

        // Permissions can only be evaluated for online players.
        // Defer the reset until they log in to avoid zeroing their monthly tokens.
        if (proxy.getPlayer(data.getUuid()).isEmpty()) return;
        int newMonthly = calculateMonthlyTokens(data.getUuid());

        // Use a targeted UPDATE instead of a full row write to avoid overwriting
        // concurrent token deductions (useToken atomic UPDATE) with a stale read.
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE player_data SET monthly_tokens = ?, last_reset_month = ? WHERE uuid = ?")) {
            stmt.setInt(1, newMonthly);
            stmt.setInt(2, currentYearMonth);
            stmt.setString(3, data.getUuid().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to reset monthly tokens for {}", data.getUuid(), e);
        }
        // Keep the in-memory object consistent so callers see the updated values.
        data.setMonthlyTokens(newMonthly);
        data.setLastResetMonth(currentYearMonth);
    }

    private int calculateMonthlyTokens(UUID uuid) {
        // Can only evaluate live permissions for online players
        if (proxy.getPlayer(uuid).isEmpty()) return 0;

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
        return proxy.getPlayer(uuid)
                .map(player -> player.hasPermission("core.*") || player.hasPermission(permission))
                .orElse(false);
    }

    private int getCurrentYearMonth() {
        LocalDate now = LocalDate.now();
        return now.getYear() * 100 + now.getMonthValue();
    }

    private boolean savePlayerData(PlayerData data) {
        int monthly = Math.max(0, data.getMonthlyTokens());
        int perm = Math.max(0, data.getPermanentTokens());
        data.setMonthlyTokens(monthly);
        data.setPermanentTokens(perm);

        String sql = "INSERT INTO player_data (uuid, username, monthly_tokens, permanent_tokens, last_reset_month, color, last_server, last_online) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                "username = VALUES(username), monthly_tokens = VALUES(monthly_tokens), " +
                "permanent_tokens = VALUES(permanent_tokens), last_reset_month = VALUES(last_reset_month), " +
                "color = VALUES(color), last_server = VALUES(last_server), last_online = VALUES(last_online)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
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
            logger.error("Failed to save player data for {}", data.getUuid(), e);
            return false;
        }
    }

    public CompletableFuture<String> getDetailedTokenInfo(UUID uuid) {
        return getPlayerData(uuid).thenApply(data -> {
            if (data == null) return "<red>Player data not found";

            boolean hasUnlimited = hasPermission(uuid, "core.joinme.tokens.unlimited");
            int expectedMonthly = calculateMonthlyTokens(uuid);

            return "<green>Monthly tokens: <aqua>" + data.getMonthlyTokens() + "\n" +
                    "<green>Expected monthly: <aqua>" + (hasUnlimited ? "unlimited" : String.valueOf(expectedMonthly)) + "\n" +
                    "<green>Unlimited: <aqua>" + (hasUnlimited ? "Yes" : "No") + "\n" +
                    "<green>Tokens: <aqua>" + data.getPermanentTokens() + "\n" +
                    "<green>Total tokens: <aqua>" + data.getTotalTokens() + "\n" +
                    "<green>Last reset: <aqua>" + data.getLastResetMonth() + " <gray>(Current: <aqua>" + getCurrentYearMonth() + "<gray>)";
        });
    }

    public CompletableFuture<Boolean> recalculateMonthlyTokens(UUID uuid) {
        return getPlayerData(uuid).thenApplyAsync(data -> {
            if (data != null) {
                data.setMonthlyTokens(calculateMonthlyTokens(uuid));
                data.setLastResetMonth(getCurrentYearMonth());
                return savePlayerData(data);
            }
            return false;
        });
    }

    public boolean hasUnlimitedTokens(UUID uuid) {
        return hasPermission(uuid, "core.joinme.tokens.unlimited");
    }

    public CompletableFuture<Boolean> setPlayerColor(UUID uuid, String color) {
        // Use a targeted UPDATE instead of a full row read-modify-write to avoid
        // overwriting concurrent token deductions with a stale snapshot of the row.
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE player_data SET color = ? WHERE uuid = ?")) {
                stmt.setString(1, color);
                stmt.setString(2, uuid.toString());
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.error("Failed to set color for {}", uuid, e);
                return false;
            }
        });
    }
}
