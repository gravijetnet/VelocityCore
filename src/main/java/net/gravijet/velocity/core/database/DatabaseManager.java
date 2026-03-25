package net.gravijet.velocity.core.database;

import net.gravijet.velocity.core.Core;

import java.sql.*;

public class DatabaseManager {
    private final String url = "jdbc:mysql://localhost:3306/velocity";
    private final String username = "velocity";
    private final String password = "velocity";

    public DatabaseManager() {
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection connection = getConnection()) {
            // Player Data Table
            connection.prepareStatement("CREATE TABLE IF NOT EXISTS player_data (" +
                    "uuid VARCHAR(100) PRIMARY KEY, " +
                    "username VARCHAR(100), " +
                    "monthly_tokens INT DEFAULT 0, " +
                    "permanent_tokens INT DEFAULT 1, " +
                    "last_reset_month INT DEFAULT 0, " +
                    "color VARCHAR(64) DEFAULT NULL" +
                    ")").executeUpdate();

            addColumnIfNotExists(connection, "player_data", "last_server", "VARCHAR(100)");
            addColumnIfNotExists(connection, "player_data", "last_online", "BIGINT");

        } catch (SQLException e) {
            e.printStackTrace();
            Core.getInstance().shutdown();
        }
    }

    private void addColumnIfNotExists(Connection connection, String tableName, String columnName, String columnDefinition) {
        try {
            DatabaseMetaData md = connection.getMetaData();
            ResultSet rs = md.getColumns(null, null, tableName, columnName);
            if (!rs.next()) {
                connection.prepareStatement("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition).executeUpdate();
            }
        } catch (SQLException e) {
            // Ignore errors, might be because column already exists
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
