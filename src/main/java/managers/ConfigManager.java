package managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Central utility for loading and managing YAML configuration files.
 * <p>
 * This class wraps a {@link FileConfiguration} and provides both basic
 * file handling (load/save/reload) and higher-level validation helpers
 * for reading strongly-typed and range-checked values. It is intended
 * to reduce boilerplate and improve error messages for configuration
 * mistakes.
 */
public class ConfigManager {

    private static final String LOG_PREFIX = "[Config] ";

    private final JavaPlugin plugin;
    private final String fileName;
    private final Logger logger;

    private File configFile;
    private FileConfiguration config;

    /**
     * Creates a new manager for {@code <fileName>.yml} in the plugin data folder.
     *
     * @param plugin   the owning plugin
     * @param fileName the logical config name (without {@code .yml} extension)
     */
    public ConfigManager(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.logger = plugin.getLogger();
    }

    /**
     * Initializes this configuration file.
     * <p>
     * If the plugin data folder does not exist yet, it is created. If the
     * target file does not exist, the method first attempts to copy a
     * bundled resource with the same name from the plugin JAR; if no such
     * resource exists, an empty file is created instead.
     */
    public void setup() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        configFile = new File(plugin.getDataFolder(), fileName + ".yml");

        // NEW: ensure parent folders exist (e.g. modules/economy/)
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                logger.severe(LOG_PREFIX + "Could not create directories for " + configFile.getPath());
                return;
            }
        }

        if (!configFile.exists()) {
            if (plugin.getResource(fileName + ".yml") != null) {
                plugin.saveResource(fileName + ".yml", false);
                logger.info(LOG_PREFIX + "Created " + fileName + ".yml from plugin defaults.");
            } else {
                try {
                    if (configFile.createNewFile()) {
                        logger.warning(LOG_PREFIX + fileName + ".yml did not exist, created an empty file.");
                    }
                } catch (IOException e) {
                    logger.severe(LOG_PREFIX + "Could not create " + fileName + ".yml");
                    e.printStackTrace();
                    return;
                }
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
    }

    /**
     * Returns the underlying configuration instance.
     *
     * @return the loaded {@link FileConfiguration}
     */
    public FileConfiguration getConfig() {
        return config;
    }

    /**
     * Saves the current in-memory configuration back to disk.
     */
    public void saveConfig() {
        if (config == null || configFile == null) {
            logger.warning(LOG_PREFIX + "saveConfig() called before setup() for " + fileName + ".yml");
            return;
        }
        try {
            config.save(configFile);
        } catch (IOException e) {
            logger.severe(LOG_PREFIX + "Could not save config to " + configFile);
            e.printStackTrace();
        }
    }

    /**
     * Reloads the configuration from disk, discarding any unsaved changes.
     */
    public void reloadConfig() {
        if (configFile == null) {
            logger.warning(LOG_PREFIX + "reloadConfig() called before setup() for " + fileName + ".yml");
            return;
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    // ========================================================================
    // Validation helpers
    // ========================================================================

    /**
     * Reads a required, non-empty string from the given section.
     *
     * @param section     the configuration section owning this path
     * @param path        the relative path within the section
     * @param displayPath a human-readable name for error messages
     * @return the trimmed string value
     * @throws IllegalStateException if the value is missing or empty
     */
    public String getRequiredString(ConfigurationSection section, String path, String displayPath) {
        if (section == null) {
            throw new IllegalStateException("Missing section while reading " + displayPath + " in " + fileName + ".yml");
        }
        String value = section.getString(path);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing or empty " + displayPath + " in " + fileName + ".yml");
        }
        return value.trim();
    }

    /**
     * Reads a boolean value, logging a warning if the node exists but is not
     * actually a boolean.
     *
     * @param section     the configuration section
     * @param path        the relative path
     * @param def         default value if missing or invalid
     * @param displayPath label used in log messages (e.g. "Database.SSL.UseSSL")
     * @return the boolean value or the default
     */
    public boolean getBooleanWithWarning(ConfigurationSection section,
                                         String path,
                                         boolean def,
                                         String displayPath) {
        if (section == null || !section.contains(path)) {
            return def;
        }
        if (!section.isBoolean(path)) {
            logger.warning(LOG_PREFIX + displayPath +
                    " should be true/false. You gave something else, using default: " + def + ".");
            return def;
        }
        return section.getBoolean(path);
    }

    /**
     * Reads an integer value that must fall within the provided range.
     *
     * @param section     the configuration section
     * @param path        the relative path
     * @param min         inclusive minimum
     * @param max         inclusive maximum
     * @param displayPath label used in error messages
     * @return the integer value
     * @throws IllegalStateException if missing, non-numeric, or out of range
     */
    public int getIntStrictRange(ConfigurationSection section,
                                 String path,
                                 int min,
                                 int max,
                                 String displayPath) {
        if (section == null || !section.contains(path)) {
            throw new IllegalStateException(displayPath + " is missing in " + fileName + ".yml");
        }
        if (!section.isInt(path)) {
            throw new IllegalStateException(displayPath + " must be a number in " + fileName + ".yml");
        }
        int value = section.getInt(path);
        if (value < min || value > max) {
            throw new IllegalStateException(displayPath + " must be between " + min + " and " + max +
                    " (got " + value + ")");
        }
        return value;
    }

    /**
     * Reads an integer value and clamps it into the provided range, emitting
     * a warning when the configured value is outside that range.
     *
     * @param section     the configuration section (may be {@code null})
     * @param path        the relative path
     * @param def         default value if missing or invalid
     * @param min         inclusive minimum allowed value
     * @param max         inclusive maximum allowed value
     * @param displayPath label used in log messages
     * @param clampMsg    message template for clamping, with {@code %d} placeholders
     *                    for {@code actual} and {@code clamped} values
     * @return the clamped value or default
     */
    public int getIntClamped(ConfigurationSection section,
                             String path,
                             int def,
                             int min,
                             int max,
                             String displayPath,
                             String clampMsg) {
        if (section == null || !section.contains(path)) {
            return def;
        }
        if (!section.isInt(path)) {
            logger.warning(LOG_PREFIX + displayPath +
                    " should be a number. Using default: " + def + ".");
            return def;
        }

        int value = section.getInt(path);
        if (value < min) {
            logger.warning(LOG_PREFIX + String.format(clampMsg, value, min));
            return min;
        }
        if (value > max) {
            logger.warning(LOG_PREFIX + String.format(clampMsg, value, max));
            return max;
        }
        return value;
    }

    /**
     * Reads a long value (typically a timeout in milliseconds) and clamps it
     * into the provided range, emitting a warning when clamped.
     *
     * @param section     the configuration section (may be {@code null})
     * @param path        the relative path
     * @param def         default value if missing or invalid
     * @param min         inclusive minimum allowed value
     * @param max         inclusive maximum allowed value
     * @param displayPath label used in log messages
     * @param clampMsg    message template for clamping, with {@code %d} placeholders
     *                    for {@code actual} and {@code clamped} values
     * @return the clamped value or default
     */
    public long getLongClamped(ConfigurationSection section,
                               String path,
                               long def,
                               long min,
                               long max,
                               String displayPath,
                               String clampMsg) {
        if (section == null || !section.contains(path)) {
            return def;
        }
        if (!(section.isInt(path) || section.isLong(path))) {
            logger.warning(LOG_PREFIX + displayPath +
                    " should be a number (milliseconds). Using default: " + def + " ms.");
            return def;
        }

        long value = section.getLong(path);
        if (value < min) {
            logger.warning(LOG_PREFIX + String.format(clampMsg, value, min));
            return min;
        }
        if (value > max) {
            logger.warning(LOG_PREFIX + String.format(clampMsg, value, max));
            return max;
        }
        return value;
    }

    /**
     * Reads a required, non-empty string and enforces a length range.
     *
     * @param section     the configuration section
     * @param path        the relative path
     * @param displayPath label used in error messages
     * @param minLen      inclusive minimum length
     * @param maxLen      inclusive maximum length
     * @return the trimmed string value
     * @throws IllegalStateException if missing, empty, or out of range
     */
    public String getRequiredStringBounded(ConfigurationSection section,
                                           String path,
                                           String displayPath,
                                           int minLen,
                                           int maxLen) {
        String value = getRequiredString(section, path, displayPath);
        int len = value.length();
        if (len < minLen || len > maxLen) {
            throw new IllegalStateException(displayPath + " length must be between " +
                    minLen + " and " + maxLen + " characters (got " + len + ").");
        }
        return value;
    }

    /**
     * Reads and validates a host name (or IP) value.
     * <p>
     * Rules:
     * <ul>
     *   <li>Required, non-empty, length within the given bounds.</li>
     *   <li>No whitespace.</li>
     *   <li>Must not contain a protocol prefix (e.g. {@code mysql://}).</li>
     *   <li>Must not contain a port separator ({@code :}); port belongs in Database.Port.</li>
     *   <li>Optionally warns if the host string equals other config values such as
     *       Database.Name or Database.User (likely copy-paste mistakes).</li>
     * </ul>
     *
     * @param section        the configuration section
     * @param path           the relative path
     * @param displayPath    label used in error messages
     * @param minLen         inclusive minimum length
     * @param maxLen         inclusive maximum length
     * @param suspiciousLike optional other values that, if equal to the host, trigger a warning
     * @return the validated host string
     */
    public String getHostString(ConfigurationSection section,
                                String path,
                                String displayPath,
                                int minLen,
                                int maxLen,
                                String... suspiciousLike) {

        String host = getRequiredStringBounded(section, path, displayPath, minLen, maxLen);

        // no protocol nonsense
        if (host.contains("://")) {
            throw new IllegalStateException(displayPath +
                    " should not include a protocol prefix like 'mysql://'. " +
                    "Use only hostname or IP, e.g. 'localhost'.");
        }

        // no whitespace
        if (host.contains(" ") || host.contains("\t")) {
            throw new IllegalStateException(displayPath +
                    " must not contain whitespace. Use a plain hostname or IP.");
        }

        // no embedded port; we have Database.Port for that
        if (host.contains(":")) {
            throw new IllegalStateException(displayPath +
                    " must not contain a port (':'). Use Database.Port to configure the port.");
        }

        if (suspiciousLike != null) {
            for (String other : suspiciousLike) {
                if (other == null || other.isEmpty()) {
                    continue;
                }
                if (host.equalsIgnoreCase(other)) {
                    logger.warning(LOG_PREFIX + displayPath + " is identical to '" + other +
                            "'. This looks like a copy-paste mistake (host is not a database name or user).");
                }
            }
        }

        return host;
    }

    /**
     * Counts newline characters in the given string. Intended for sanity checks
     * on suspiciously large values (e.g. someone pasted a whole book as a password).
     *
     * @param value the string to inspect
     * @return the number of newline characters ({@code \n} or {@code \r})
     */
    public int countNewlines(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                count++;
            }
        }
        return count;
    }
    
}