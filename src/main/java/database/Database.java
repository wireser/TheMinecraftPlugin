package database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.SQLException;

public class Database {

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        FileConfiguration cfg = plugin.getConfig();

        String host = cfg.getString("database.host", "localhost");
        int port = cfg.getInt("database.port", 3306);
        String dbName = cfg.getString("database.name", "minecraft");
        String user = cfg.getString("database.user", "root");
        String password = cfg.getString("database.password", "");

        int poolSize = cfg.getInt("database.pool-size", 10);
        long connectionTimeout = cfg.getLong("database.connection-timeout", 30000L);
        long idleTimeout = cfg.getLong("database.idle-timeout", 600000L);
        long maxLifetime = cfg.getLong("database.max-lifetime", 1800000L);

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&autoReconnect=true&characterEncoding=utf8&serverTimezone=UTC",
                host, port, dbName
        );

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);

        config.setPoolName(plugin.getName() + "-HikariPool");
        config.setMaximumPoolSize(poolSize);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);

        // Some recommended MySQL performance options
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        this.dataSource = new HikariDataSource(config);

        plugin.getLogger().info("HikariCP pool initialized.");
    }

    /**
     * Get a connection from the pool.
     * Always use try-with-resources when working with this.
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("HikariDataSource is not initialized or already closed.");
        }
        return dataSource.getConnection();
    }

    /**
     * Close the connection pool.
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("HikariCP pool shut down.");
        }
    }
    
    public boolean isAlive() {
    	return dataSource.isRunning();
    }
    
}