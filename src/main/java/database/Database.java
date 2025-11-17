package database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import main.Main;
import managers.ConfigManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of the HikariCP connection pool and tracks the
 * current database health status. All configuration is read via a
 * {@link ConfigManager} from the {@code Database} section of the main config.
 */
public final class Database {

    private static final String DB_PREFIX = "[DBConfig] ";

    // Reasonable safety bounds
    private static final int MAX_POOL_SIZE_HARD_MAX = 50;
    private static final int MAX_POOL_SIZE_HARD_MIN = 1;

    private static final long TIMEOUT_MIN_MS = 100L;
    private static final long TIMEOUT_MAX_MS = 600_000L; // 10 minutes

    private static final int WATCHDOG_INTERVAL_MIN_TICKS = 20;      // 1 second
    private static final int WATCHDOG_INTERVAL_MAX_TICKS = 24_000;  // 20 minutes

    private static final int WATCHDOG_FAILURES_MIN = 1;
    private static final int WATCHDOG_FAILURES_MAX = 600;           // "only" ~30–60 minutes, not 7 hours of pain

    private final Main plugin;
    private final ConfigManager configManager;
    private final Logger logger;

    private HikariDataSource dataSource;

    private DatabaseStatus status = DatabaseStatus.UNKNOWN;
    private int watchdogTaskId = -1;
    private int consecutiveFailures = 0;

    private DatabaseStateListener stateListener;

    /**
     * Creates a new database handler bound to the given plugin and configuration.
     *
     * @param plugin        the owning plugin
     * @param configManager the main configuration manager (for config.yml)
     */
    public Database(Main plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.logger = plugin.getLogger();
    }

    /**
     * Initializes the HikariCP connection pool using the {@code Database}
     * section of {@code config.yml}.
     * <p>
     * This method performs strict validation of all connection-critical
     * settings (host, port, database name, user, password, etc.). If any
     * required value is missing or invalid, or if the initial health check
     * fails, an {@link IllegalStateException} is thrown and the caller is
     * expected to abort plugin startup.
     */
    public void init() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.warning(DB_PREFIX + "Database.init() called but pool is already initialized. Ignoring.");
            return;
        }

        FileConfiguration root = configManager.getConfig();
        ConfigurationSection db = root.getConfigurationSection("Database");
        if (db == null) {
            throw new IllegalStateException("Missing 'Database' section in config.yml");
        }

        // === Required / connection-critical settings (with length bounds) ===
        // These bounds are intentionally generous but stop novels / garbage.
        String type   = configManager.getRequiredStringBounded(db, "Type",   "Database.Type",   2,  16);  // e.g. "MySQL"
        String engine = configManager.getRequiredStringBounded(db, "Engine", "Database.Engine", 2,  32);  // e.g. "InnoDB"
        String module = configManager.getRequiredStringBounded(db, "Module", "Database.Module", 2,  16);  // e.g. "jdbc"
        String name   = configManager.getRequiredStringBounded(db, "Name",   "Database.Name",   1,  64);  // MySQL schema name limit
        String user   = configManager.getRequiredStringBounded(db, "User",   "Database.User",   1,  32);  // MySQL user length is small
        String pass   = configManager.getRequiredStringBounded(db, "Pass",   "Database.Pass",   1, 512);  // strong but not novel-sized

        // Host: special validation, plus we pass in other strings to warn on suspicious equality
        String host = configManager.getHostString(db,
                "Host",
                "Database.Host",
                1,
                253,   // DNS host length limit
                name,
                user
        );

        if (pass.length() > 128 || configManager.countNewlines(pass) > 5) {
            logger.warning(DB_PREFIX +
                    "Database.Pass looks unusually large. " +
                    "If you pasted half a novel in there, please don't. " +
                    "MySQL just needs a password, not bedtime reading.");
        }

        int port = configManager.getIntStrictRange(
                db, "Port", 1, 65535, "Database.Port"
        );

        if (!"mysql".equalsIgnoreCase(type)) {
            throw new IllegalStateException("Unsupported Database.Type '" + type + "'. Only 'MySQL' is supported.");
        }
        
        if (!"jdbc".equalsIgnoreCase(module)) {
            throw new IllegalStateException("Unsupported Database.Module '" + module + "'. Expected 'jdbc'.");
        }
        
        if (engine.trim().isEmpty()) {
            throw new IllegalStateException("Database.Engine must not be empty.");
        }

        // === SSL configuration ===
        ConfigurationSection ssl = db.getConfigurationSection("SSL");
        boolean useSSL = configManager.getBooleanWithWarning(ssl, "UseSSL", false, "Database.SSL.UseSSL");
        boolean verifyServerCert = configManager.getBooleanWithWarning(ssl, "VerifyServerCertificate", false, "Database.SSL.VerifyServerCertificate");
        boolean allowPublicKeyRetrieval = configManager.getBooleanWithWarning(db, "allowPublicKeyRetrieval", true, "Database.allowPublicKeyRetrieval");

        if (!useSSL && verifyServerCert) {
            logger.warning(DB_PREFIX +
                    "You set Database.SSL.VerifyServerCertificate to true but Database.SSL.UseSSL is false. " +
                    "We can't verify a certificate for a connection that isn't using SSL. " +
                    "Either enable UseSSL or set VerifyServerCertificate to false.");
        }

        if (useSSL && "127.0.0.1".equals(host)) {
            logger.info(DB_PREFIX +
                    "SSL is enabled for localhost. Make sure your MySQL server is configured for SSL, " +
                    "or expect some entertaining SSL handshake errors.");
        }

        // === Encoding configuration ===
        ConfigurationSection enc = db.getConfigurationSection("Encoding");
        boolean useUnicode = configManager.getBooleanWithWarning(
                enc, "UseUnicode", true, "Database.Encoding.UseUnicode"
        );

        String charsetRaw = (enc != null ? enc.getString("Charset", "utf8mb4") : "utf8mb4");
        if (charsetRaw == null) {
            charsetRaw = "utf8mb4";
        }
        
        // Bound the charset string itself: not empty, not a paragraph.
        String charset = configManager.getRequiredStringBounded(
                enc != null ? enc : db, // if Encoding section exists, tie error to it; else still validate
                "Charset",
                "Database.Encoding.Charset",
                1,
                32
        );

        if (!charset.equalsIgnoreCase("utf8mb4") && !charset.equalsIgnoreCase("utf8")) {
            logger.warning(DB_PREFIX +
                    "Database.Encoding.Charset is set to '" + charset + "'. " +
                    "Non-UTF-8 encodings can cause issues with international characters or emojis " +
                    "unless your entire database and plugin are configured for that encoding. " +
                    "For most servers, 'utf8mb4' is recommended.");
        }

        // === Pool configuration ===
        ConfigurationSection pool = db.getConfigurationSection("Pool");

        int maxPoolSize = configManager.getIntClamped(
                pool,
                "MaxPoolSize",
                10,
                MAX_POOL_SIZE_HARD_MIN,
                MAX_POOL_SIZE_HARD_MAX,
                "Database.Pool.MaxPoolSize",
                "This is a Minecraft plugin, not a database benchmark. Clamping MaxPoolSize from %d to %d."
        );

        long connectionTimeoutMs = configManager.getLongClamped(
                pool,
                "ConnectionTimeoutMs",
                30_000L,
                TIMEOUT_MIN_MS,
                TIMEOUT_MAX_MS,
                "Database.Pool.ConnectionTimeoutMs",
                "ConnectionTimeoutMs out of range (%d ms). Clamped to %d ms."
        );

        long idleTimeoutMs = configManager.getLongClamped(
                pool,
                "IdleTimeoutMs",
                600_000L,
                TIMEOUT_MIN_MS,
                TIMEOUT_MAX_MS,
                "Database.Pool.IdleTimeoutMs",
                "IdleTimeoutMs out of range (%d ms). Clamped to %d ms."
        );

        long maxLifetimeMs = configManager.getLongClamped(
                pool,
                "MaxLifetimeMs",
                1_800_000L,
                TIMEOUT_MIN_MS,
                3_600_000L,
                "Database.Pool.MaxLifetimeMs",
                "MaxLifetimeMs out of range (%d ms). Clamped to %d ms."
        );

        long validationTimeoutMs = configManager.getLongClamped(
                pool,
                "ValidationTimeoutMs",
                2_000L,
                250L,
                30_000L,
                "Database.Pool.ValidationTimeoutMs",
                "ValidationTimeoutMs out of range (%d ms). Clamped to %d ms."
        );

        // === Build JDBC URL ===
        StringBuilder url = new StringBuilder();
        url.append("jdbc:mysql://")
           .append(host).append(':').append(port)
           .append('/').append(name)
           .append("?useUnicode=").append(useUnicode)
           .append("&characterEncoding=").append(charset)
           .append("&allowPublicKeyRetrieval=").append(allowPublicKeyRetrieval);

        if (useSSL) {
            url.append("&useSSL=true")
               .append("&requireSSL=true")
               .append("&verifyServerCertificate=").append(verifyServerCert);
        } else {
            url.append("&useSSL=false");
        }

        // === Hikari configuration ===
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url.toString());
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setPoolName(plugin.getName() + "-HikariPool");
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setConnectionTimeout(connectionTimeoutMs);
        cfg.setIdleTimeout(idleTimeoutMs);
        cfg.setMaxLifetime(maxLifetimeMs);
        cfg.setValidationTimeout(validationTimeoutMs);

        cfg.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(cfg);
        logger.info("HikariCP pool initialized for database '" + name + "' on " + host + ":" + port + ".");

        // Initial health check – fail early if DB is unreachable.
        if (!isAlive()) {
            shutdown();
            throw new IllegalStateException(
                    "Database pool created, but initial health check failed. " +
                    "Check your Database.* settings in config.yml."
            );
        }

        updateStatus(DatabaseStatus.UP);
    }

    /**
     * Starts the periodic database watchdog task.
     * <p>
     * The watchdog performs a lightweight health check at a fixed interval
     * and reacts to connectivity changes:
     * <ul>
     *     <li>Tracks the last known {@link DatabaseStatus}.</li>
     *     <li>Optionally disables the plugin if the database remains unreachable
     *         for too many consecutive checks ("fail-fast" behavior).</li>
     *     <li>Notifies the optional {@link DatabaseStateListener} on state changes.</li>
     * </ul>
     * Timing and thresholds are configured in {@code Database.Watchdog}.
     */
    public void startWatchdog() {
        if (watchdogTaskId != -1) {
            logger.warning(DB_PREFIX + "Database watchdog already running.");
            return;
        }

        if (dataSource == null || dataSource.isClosed()) {
            logger.warning(DB_PREFIX + "Cannot start watchdog: database pool is not initialized.");
            return;
        }

        ConfigurationSection db = configManager.getConfig().getConfigurationSection("Database");
        if (db == null) {
            logger.warning(DB_PREFIX + "No 'Database' section found; watchdog not started.");
            return;
        }

        ConfigurationSection wd = db.getConfigurationSection("Watchdog");
        boolean enabled = configManager.getBooleanWithWarning(wd, "Enabled", true, "Database.Watchdog.Enabled");
        if (!enabled) {
            logger.info(DB_PREFIX + "Database watchdog disabled via configuration.");
            return;
        }

        int intervalTicks = configManager.getIntClamped(
                wd,
                "CheckIntervalTicks",
                100,
                WATCHDOG_INTERVAL_MIN_TICKS,
                WATCHDOG_INTERVAL_MAX_TICKS,
                "Database.Watchdog.CheckIntervalTicks",
                "CheckIntervalTicks out of range (%d). Clamped to %d ticks."
        );

        boolean failFast = configManager.getBooleanWithWarning(wd, "FailFast", true, "Database.Watchdog.FailFast");

        int maxFailures = configManager.getIntClamped(
                wd,
                "MaxConsecutiveFailures",
                12,
                WATCHDOG_FAILURES_MIN,
                WATCHDOG_FAILURES_MAX,
                "Database.Watchdog.MaxConsecutiveFailures",
                "MaxConsecutiveFailures out of range (%d). Clamped to %d."
        );

        watchdogTaskId = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {

            boolean alive = isAlive();
            DatabaseStatus newStatus = alive ? DatabaseStatus.UP : DatabaseStatus.DOWN;

            if (alive) {
                if (status == DatabaseStatus.DOWN) {
                    logger.info("Database connection recovered.");
                }
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                logger.warning("Database health check failed. Consecutive failures: " + consecutiveFailures);

                if (failFast && consecutiveFailures >= maxFailures) {
                    logger.severe("Database appears to be down permanently. Disabling plugin (fail-fast).");
                    try {
                        shutdown();
                    } catch (Exception ex) {
                        logger.log(Level.SEVERE, "Error while shutting down database during fail-fast.", ex);
                    }
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                    updateStatus(DatabaseStatus.DOWN);
                    return;
                }
            }

            if (newStatus != status) {
                updateStatus(newStatus);
            }

        }, intervalTicks, intervalTicks).getTaskId();
    }

    /**
     * Stops the watchdog task if it is running.
     */
    public void stopWatchdog() {
        if (watchdogTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(watchdogTaskId);
            watchdogTaskId = -1;
        }
    }

    /**
     * Returns a connection from the HikariCP pool.
     * Callers <strong>must</strong> use try-with-resources:
     *
     * <pre>
     * try (Connection conn = database.getConnection()) {
     *     // work with conn
     * }
     * </pre>
     *
     * @return a valid {@link Connection}
     * @throws SQLException if the pool is not initialized or cannot provide a connection
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("HikariDataSource is not initialized or already closed.");
        }
        return dataSource.getConnection();
    }

    /**
     * Shuts down the HikariCP pool, stops the watchdog and releases all resources.
     * This method is idempotent and safe to call multiple times.
     */
    public void shutdown() {
        stopWatchdog();

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("HikariCP pool shut down.");
        }

        dataSource = null;
        updateStatus(DatabaseStatus.DOWN);
    }

    /**
     * Performs a lightweight health check against the database by requesting
     * a connection from the pool.
     *
     * @return {@code true} if the database appears reachable and responsive,
     *         {@code false} otherwise
     */
    public boolean isAlive() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }

        try (Connection ignored = dataSource.getConnection()) {
            return true;
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "Database health check failed.", ex);
            return false;
        }
    }

    /**
     * Returns {@code true} if the pool has been initialized and not yet shut down.
     */
    public boolean isInitialized() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * Returns the last known database status as tracked by the watchdog.
     */
    public DatabaseStatus getStatus() {
        return status;
    }

    /**
     * Convenience helper for callers that only care if the database is up.
     *
     * @return {@code true} if the last known status is {@link DatabaseStatus#UP}
     */
    public boolean isUp() {
        return status == DatabaseStatus.UP;
    }

    /**
     * Logs a short summary of the current JDBC URL (without credentials) for
     * debugging purposes.
     */
    public void logConfigurationSummary() {
        if (dataSource == null) {
            logger.info("Database: pool not initialized.");
            return;
        }
        logger.info("Database: " + dataSource.getJdbcUrl());
    }

    /**
     * Forces a health check and logs the result. Intended for admin/debug commands.
     *
     * @return {@code true} if the database appears reachable, {@code false} otherwise
     */
    public boolean ping() {
        boolean ok = isAlive();
        logger.info("Database ping: " + (ok ? "OK" : "FAILED"));
        return ok;
    }

    /**
     * Registers a listener to be notified when the database status changes.
     *
     * @param listener the listener to register, or {@code null} to clear it
     */
    public void setStateListener(DatabaseStateListener listener) {
        this.stateListener = listener;
    }

    // ====================== Internal helpers ======================

    private void updateStatus(DatabaseStatus newStatus) {
        if (newStatus == status) {
            return;
        }
        DatabaseStatus old = this.status;
        this.status = newStatus;

        if (stateListener != null) {
            try {
                stateListener.onDatabaseStatusChange(old, newStatus);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Exception in DatabaseStateListener.", ex);
            }
        }
    }
    
}