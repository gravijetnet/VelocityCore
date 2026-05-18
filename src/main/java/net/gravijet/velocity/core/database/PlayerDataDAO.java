package net.gravijet.velocity.core.database;

import net.gravijet.velocity.core.database.models.PlayerData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PlayerDataDAO {

    private static final Logger logger = LoggerFactory.getLogger(PlayerDataDAO.class);
    private final DatabaseManager databaseManager;

    public PlayerDataDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<PlayerData> getPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement ps = connection.prepareStatement("SELECT * FROM player_data WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerData(
                                uuid,
                                rs.getString("username"),
                                rs.getInt("monthly_tokens"),
                                rs.getInt("permanent_tokens"),
                                rs.getInt("last_reset_month"),
                                rs.getString("color"),
                                rs.getString("last_server"),
                                rs.getLong("last_online")
                        );
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to get player data for {}", uuid, e);
            }
            return null;
        });
    }

    public CompletableFuture<PlayerData> getPlayerDataByName(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement ps = connection.prepareStatement("SELECT * FROM player_data WHERE username = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        try {
                            return new PlayerData(
                                    UUID.fromString(rs.getString("uuid")),
                                    rs.getString("username"),
                                    rs.getInt("monthly_tokens"),
                                    rs.getInt("permanent_tokens"),
                                    rs.getInt("last_reset_month"),
                                    rs.getString("color"),
                                    rs.getString("last_server"),
                                    rs.getLong("last_online")
                            );
                        } catch (IllegalArgumentException e) {
                            logger.error("Corrupt UUID in database for username '{}'", name, e);
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to get player data for name '{}'", name, e);
            }
            return null;
        });
    }

    public CompletableFuture<Void> savePlayerData(PlayerData data) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "INSERT INTO player_data (uuid, username, monthly_tokens, permanent_tokens, last_reset_month, color, last_server, last_online) " +
                                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                                 "ON DUPLICATE KEY UPDATE " +
                                 "username = VALUES(username), " +
                                 "monthly_tokens = VALUES(monthly_tokens), " +
                                 "permanent_tokens = VALUES(permanent_tokens), " +
                                 "last_reset_month = VALUES(last_reset_month), " +
                                 "color = VALUES(color), " +
                                 "last_server = VALUES(last_server), " +
                                 "last_online = VALUES(last_online)")) {

                ps.setString(1, data.getUuid().toString());
                ps.setString(2, data.getUsername());
                ps.setInt(3, data.getMonthlyTokens());
                ps.setInt(4, data.getPermanentTokens());
                ps.setInt(5, data.getLastResetMonth());
                ps.setString(6, data.getColor());
                ps.setString(7, data.getLastServer());
                ps.setLong(8, data.getLastOnline());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save player data for {}", data.getUuid(), e);
            }
        });
    }

    public CompletableFuture<Void> updateLastSeen(UUID uuid, long lastOnline, String lastServer) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "UPDATE player_data SET last_online = ?, last_server = ? WHERE uuid = ?")) {
                ps.setLong(1, lastOnline);
                ps.setString(2, lastServer);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to update last seen for {}", uuid, e);
            }
        });
    }

    public CompletableFuture<List<PlayerData>> getAllPlayers() {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerData> players = new ArrayList<>();
            try (Connection connection = databaseManager.getConnection();
                 PreparedStatement ps = connection.prepareStatement("SELECT * FROM player_data");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        players.add(new PlayerData(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("username"),
                                rs.getInt("monthly_tokens"),
                                rs.getInt("permanent_tokens"),
                                rs.getInt("last_reset_month"),
                                rs.getString("color"),
                                rs.getString("last_server"),
                                rs.getLong("last_online")
                        ));
                    } catch (IllegalArgumentException e) {
                        logger.error("Corrupt UUID in database for username '{}'", rs.getString("username"), e);
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load all players", e);
            }
            return players;
        });
    }
}
