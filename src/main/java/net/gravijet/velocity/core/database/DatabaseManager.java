package net.gravijet.velocity.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.gravijet.velocity.core.Core;

import java.sql.*;

public class DatabaseManager {
    private final HikariDataSource dataSource;

    public DatabaseManager() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://localhost:3306/velocity");
        cfg.setUsername("velocity");
        cfg.setPassword("velocity");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        this.dataSource = new HikariDataSource(cfg);
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection connection = getConnection()) {
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
            // Column already exists or other non-critical error
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        dataSource.close();
    }
}
