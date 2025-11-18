package playerdata;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import database.DatabaseAccess;
import enums.Currency;
import enums.Perm;

/**
 * Centralized data access for all profile-related persistence.
 * <p>
 * This class encapsulates all SQL interaction for player-specific data:
 * <ul>
 *     <li>Stored locations</li>
 *     <li>Balances</li>
 *     <li>Reply target, welcome message</li>
 *     <li>Social lists (friends, trust, ignore)</li>
 *     <li>Settings</li>
 *     <li>Stats</li>
 *     <li>Timers / tickers</li>
 *     <li>Ban status</li>
 * </ul>
 *
 * All interaction with {@link DatabaseAccess} that concerns player state should go
 * through this class, rather than being duplicated across modules.
 */
public final class ProfileStorage {

    private final DatabaseAccess db;
    private final Logger logger;

    /**
     * Creates a new {@code ProfileStorage} instance backed by the given
     * {@link DatabaseAccess} and {@link Logger}.
     *
     * @param db     database helper used for all SQL interactions
     * @param logger logger used for error reporting
     */
    public ProfileStorage(DatabaseAccess db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

	/**
     * Ensures that the given profile has a valid {@code players.id}.
     * Logs and returns {@code false} if not.
     */
    private boolean ensureValidId(Profile profile, String context) {
        if (profile == null || profile.getId() <= 0) {
            logger.warning("ProfileStorage: attempted " + context + " with invalid player id: " +
                    (profile == null ? "null profile" : profile.getId()));
            return false;
        }
        return true;
    }

    // ======================================================================
    // Locations
    // ======================================================================

    /*
     * For locations, we store a single serialized string in the column `location`,
     * formatted as:
     *
     *   worldName;x;y;z;yaw;pitch
     *
     * This keeps the DB schema simple and flexible.
     */

    private String serializeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return String.format(
                "%s;%f;%f;%f;%f;%f",
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch()
        );
    }

    private Location deserializeLocation(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String[] parts = data.split(";");
        if (parts.length < 6) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Retrieves a stored location for the given profile and key.
     *
     * @param profile profile whose location to load
     * @param key     logical key (e.g. "home:main", "death", "back")
     * @return stored location, or {@code null} if none is present or an error occurs
     */
    public Location getLocation(Profile profile, String key) {
        if (!ensureValidId(profile, "getLocation(" + key + ")")) {
            return null;
        }
        try {
            String data = db.getString(
                    "players_locations",
                    "location",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );
            return deserializeLocation(data);
        } catch (SQLException e) {
            logger.severe("Failed to load location '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Stores or updates a location for the given profile and key.
     *
     * @param profile  profile whose location to store
     * @param key      logical key
     * @param location location value, may be {@code null} in which case the
     *                 entry is deleted
     */
    public void setLocation(Profile profile, String key, Location location) {
        if (!ensureValidId(profile, "setLocation(" + key + ")")) {
            return;
        }
        if (location == null) {
            deleteLocation(profile, key);
            return;
        }

        String data = serializeLocation(location);
        if (data == null) {
            deleteLocation(profile, key);
            return;
        }

        try {
            // Try UPDATE first
            int updated = db.update(
                    "players_locations",
                    List.of("location"),
                    List.of(data),
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );

            if (updated == 0) {
                // No existing row -> INSERT
                db.insert(
                        "players_locations",
                        List.of("player_id", "key", "location"),
                        List.of(profile.getId(), key, data)
                );
            }
        } catch (SQLException e) {
            logger.severe("Failed to store location '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Deletes a stored location for the given profile and key.
     *
     * @param profile profile whose location to delete
     * @param key     logical key
     */
    public void deleteLocation(Profile profile, String key) {
        if (!ensureValidId(profile, "deleteLocation(" + key + ")")) {
            return;
        }
        try {
            db.delete(
                    "players_locations",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );
        } catch (SQLException e) {
            logger.severe("Failed to delete location '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ======================================================================
    // Balances (BigDecimal)
    // ======================================================================

    /**
     * Converts a {@link Currency} enum to the string key used in the database.
     *
     * @param currency currency enum
     * @return non-null key string
     */
    private String currencyKey(Currency currency) {
        // Adjust if your Currency enum has a dedicated key accessor
        return currency.name().toLowerCase();
    }

    /**
     * Loads the balance for a specific currency.
     *
     * @param profile  profile whose balance to load
     * @param currency currency key
     * @return non-null {@link BigDecimal} amount (zero if none is stored or an error occurs)
     */
    public BigDecimal getBalance(Profile profile, Currency currency) {
        if (!ensureValidId(profile, "getBalance(" + currency + ")")) {
            return BigDecimal.ZERO;
        }
        if (currency == null) {
            return BigDecimal.ZERO;
        }

        try {
            String raw = db.getString(
                    "players_balances",
                    "value",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), currencyKey(currency))
            );
            if (raw == null) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(raw);
        } catch (SQLException e) {
            logger.severe("Failed to load balance for playerId=" + profile.getId()
                    + ", currency=" + currency + ": " + e.getMessage());
            e.printStackTrace();
            return BigDecimal.ZERO;
        } catch (NumberFormatException e) {
            logger.severe("Invalid numeric balance format for playerId=" + profile.getId()
                    + ", currency=" + currency + ": " + e.getMessage());
            e.printStackTrace();
            return BigDecimal.ZERO;
        }
    }

    /**
     * Sets the balance for a specific currency.
     *
     * @param profile  profile whose balance to update
     * @param currency currency key
     * @param amount   non-null value to store
     */
    public void setBalance(Profile profile, Currency currency, BigDecimal amount) {
        if (!ensureValidId(profile, "setBalance(" + currency + ")")) {
            return;
        }
        if (currency == null || amount == null) {
            return;
        }

        try {
            int updated = db.update(
                    "players_balances",
                    List.of("value"),
                    List.of(amount),
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), currencyKey(currency))
            );

            if (updated == 0) {
                db.insert(
                        "players_balances",
                        List.of("player_id", "key", "value"),
                        List.of(profile.getId(), currencyKey(currency), amount)
                );
            }
        } catch (SQLException e) {
            logger.severe("Failed to set balance for playerId=" + profile.getId()
                    + ", currency=" + currency + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Adds a delta to the balance for a specific currency.
     *
     * @param profile  profile whose balance to update
     * @param currency currency key
     * @param delta    non-zero delta (may be negative)
     */
    public void addBalance(Profile profile, Currency currency, BigDecimal delta) {
        if (!ensureValidId(profile, "addBalance(" + currency + ")")) {
            return;
        }
        if (currency == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        try {
            db.incrementOrInsert(
                    "players_balances",
                    List.of("player_id", "key"),
                    List.of(profile.getId(), currencyKey(currency)),
                    "value",
                    delta
            );
        } catch (SQLException e) {
            logger.severe("Failed to add balance delta for playerId=" + profile.getId()
                    + ", currency=" + currency + ", delta=" + delta + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ======================================================================
    // Reply target & welcome message
    // ======================================================================

    /**
     * Loads the reply target player id from {@code players.reply}.
     *
     * @param profile profile whose reply target to load
     * @return player id (players.id) or {@code null} if not set
     */
    public Integer getReplyTargetId(Profile profile) {
        if (!ensureValidId(profile, "getReplyTargetId")) {
            return null;
        }
        try {
            return db.getInt(
                    "players",
                    "reply",
                    "id = ?",
                    List.of(profile.getId())
            );
        } catch (SQLException e) {
            logger.severe("Failed to load reply target for playerId=" + profile.getId()
                    + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Updates {@code players.reply} for the given profile.
     *
     * @param profile  profile to update
     * @param targetId new reply target id, may be {@code null} to clear
     */
    public void setReplyTargetId(Profile profile, Integer targetId) {
        if (!ensureValidId(profile, "setReplyTargetId")) {
            return;
        }
        try {
            db.update(
                    "players",
                    List.of("reply"),
                    List.of(targetId),
                    "id = ?",
                    List.of(profile.getId())
            );
        } catch (SQLException e) {
            logger.severe("Failed to update reply target for playerId=" + profile.getId()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Loads the welcome message from {@code players.welcome}.
     *
     * @param profile profile whose welcome message to load
     * @return welcome message, or {@code null} if none is set
     */
    public String getWelcome(Profile profile) {
        if (!ensureValidId(profile, "getWelcome")) {
            return null;
        }
        try {
            return db.getString(
                    "players",
                    "welcome",
                    "id = ?",
                    List.of(profile.getId())
            );
        } catch (SQLException e) {
            logger.severe("Failed to load welcome message for playerId=" + profile.getId()
                    + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Updates the welcome message in {@code players.welcome}.
     *
     * @param profile profile to update
     * @param welcome new message, or {@code null} to clear
     */
    public void setWelcome(Profile profile, String welcome) {
        if (!ensureValidId(profile, "setWelcome")) {
            return;
        }
        try {
            db.update(
                    "players",
                    List.of("welcome"),
                    List.of(welcome),
                    "id = ?",
                    List.of(profile.getId())
            );
        } catch (SQLException e) {
            logger.severe("Failed to update welcome message for playerId=" + profile.getId()
                    + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ======================================================================
    // Ban status & timers
    // ======================================================================

    /**
     * Determines whether the given profile is currently banned.
     * <p>
     * This implementation checks:
     * <ul>
     *     <li>{@code players.banned} for a hard ban</li>
     *     <li>{@code players_timers} with key {@code "tempban"} for a time-limited ban</li>
     * </ul>
     *
     * @param profile profile to check
     * @return {@code true} if the player is currently banned
     */
    public boolean isBanned(Profile profile) {
        if (!ensureValidId(profile, "isBanned")) {
            return false;
        }
        try {
            Boolean hardBan = db.getBoolean(
                    "players",
                    "banned",
                    "id = ?",
                    List.of(profile.getId())
            );
            if (Boolean.TRUE.equals(hardBan)) {
                return true;
            }

            return hasActiveTimer(profile, "tempban");
        } catch (SQLException e) {
            logger.severe("Failed to check ban status for playerId=" + profile.getId()
                    + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Checks whether a logical timer is currently active for the given profile.
     *
     * @param profile profile to check
     * @param key     timer key (e.g. "fly", "tempban", "godmode")
     * @return {@code true} if the timer is active, otherwise {@code false}
     */
    public boolean hasActiveTimer(Profile profile, String key) {
        if (!ensureValidId(profile, "hasActiveTimer(" + key + ")")) {
            return false;
        }

        try {
            // Infinite timer
            Boolean infinite = db.getBoolean(
                    "players_timers",
                    "infinite",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );
            if (Boolean.TRUE.equals(infinite)) {
                return true;
            }

            // Check expiry
            LocalDateTime expiresAt = db.getDateTime(
                    "players_timers",
                    "expires_at",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );
            if (expiresAt == null) {
                return false;
            }

            return expiresAt.isAfter(LocalDateTime.now());
        } catch (SQLException e) {
            logger.severe("Failed to check timer '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ======================================================================
    // Social lists: friends / trust / ignore
    // ======================================================================

    /**
     * Convenience method that loads all {@code target_id} values from a simple
     * relation table of the form (player_id, target_id).
     */
    private List<Integer> fetchTargetIdList(String table, int ownerId) {
        try {
            List<Integer> list = db.getIntList(
                    table,
                    "target_id",
                    "player_id = ?",
                    List.of(ownerId),
                    0
            );
            return list != null ? list : Collections.emptyList();
        } catch (SQLException e) {
            logger.severe("Failed to fetch id list from " + table + " for playerId="
                    + ownerId + ": " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private void insertPair(String table, int playerId, int targetId) throws SQLException {
        db.insert(
                table,
                List.of("player_id", "target_id"),
                List.of(playerId, targetId)
        );
    }

    private void deletePair(String table, int playerId, int targetId) throws SQLException {
        db.delete(
                table,
                "player_id = ? AND target_id = ?",
                List.of(playerId, targetId)
        );
    }

    private boolean hasPair(String table, int playerId, int targetId) throws SQLException {
        int count = db.count(
                table,
                "player_id = ? AND target_id = ?",
                List.of(playerId, targetId)
        );
        return count > 0;
    }

    // ----- Friends -----

    /**
     * Adds an entry to {@code players_friend_list}.
     */
    public void addFriend(Profile profile, int friendId) {
        if (!ensureValidId(profile, "addFriend")) {
            return;
        }
        try {
            insertPair("players_friend_list", profile.getId(), friendId);
        } catch (SQLException e) {
            logger.severe("Failed to add friend: playerId=" + profile.getId()
                    + ", friendId=" + friendId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Removes an entry from {@code players_friend_list}.
     */
    public void removeFriend(Profile profile, int friendId) {
        if (!ensureValidId(profile, "removeFriend")) {
            return;
        }
        try {
            deletePair("players_friend_list", profile.getId(), friendId);
        } catch (SQLException e) {
            logger.severe("Failed to remove friend: playerId=" + profile.getId()
                    + ", friendId=" + friendId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks whether {@code playerId} has {@code targetId} on their friend list.
     */
    public boolean isFriend(int playerId, int targetId) {
        try {
            return hasPair("players_friend_list", playerId, targetId);
        } catch (SQLException e) {
            logger.severe("Failed to check friend relation: " + playerId + " -> "
                    + targetId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Returns all friend ids ({@code players.id}) for the given profile.
     */
    public List<Integer> getFriendIds(Profile profile) {
        if (!ensureValidId(profile, "getFriendIds")) {
            return Collections.emptyList();
        }
        return fetchTargetIdList("players_friend_list", profile.getId());
    }

    // ----- Trust -----

    public void addTrusted(Profile profile, int targetId) {
        if (!ensureValidId(profile, "addTrusted")) {
            return;
        }
        try {
            insertPair("players_trust_list", profile.getId(), targetId);
        } catch (SQLException e) {
            logger.severe("Failed to add trusted: playerId=" + profile.getId()
                    + ", targetId=" + targetId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeTrusted(Profile profile, int targetId) {
        if (!ensureValidId(profile, "removeTrusted")) {
            return;
        }
        try {
            deletePair("players_trust_list", profile.getId(), targetId);
        } catch (SQLException e) {
            logger.severe("Failed to remove trusted: playerId=" + profile.getId()
                    + ", targetId=" + targetId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks whether {@code playerId} trusts {@code targetId}.
     */
    public boolean isTrusted(int playerId, int targetId) {
        try {
            return hasPair("players_trust_list", playerId, targetId);
        } catch (SQLException e) {
            logger.severe("Failed to check trust relation: " + playerId + " -> "
                    + targetId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Returns all trusted ids ({@code players.id}) for the given profile.
     */
    public List<Integer> getTrustedIds(Profile profile) {
        if (!ensureValidId(profile, "getTrustedIds")) {
            return Collections.emptyList();
        }
        return fetchTargetIdList("players_trust_list", profile.getId());
    }

    // ----- Ignore -----

    public void addIgnored(Profile profile, int targetId) {
        if (!ensureValidId(profile, "addIgnored")) {
            return;
        }
        try {
            insertPair("players_ignore_list", profile.getId(), targetId);
        } catch (SQLException e) {
            logger.severe("Failed to add ignored: playerId=" + profile.getId()
                    + ", targetId=" + targetId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeIgnored(Profile profile, int targetId) {
        if (!ensureValidId(profile, "removeIgnored")) {
            return;
        }
        try {
            deletePair("players_ignore_list", profile.getId(), targetId);
        } catch (SQLException e) {
            logger.severe("Failed to remove ignored: playerId=" + profile.getId()
                    + ", targetId=" + targetId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks whether {@code playerId} ignores {@code targetId}.
     */
    public boolean isIgnored(int playerId, int targetId) {
        try {
            return hasPair("players_ignore_list", playerId, targetId);
        } catch (SQLException e) {
            logger.severe("Failed to check ignore relation: " + playerId + " -> "
                    + targetId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Returns all ignored ids ({@code players.id}) for the given profile.
     */
    public List<Integer> getIgnoredIds(Profile profile) {
        if (!ensureValidId(profile, "getIgnoredIds")) {
            return Collections.emptyList();
        }
        return fetchTargetIdList("players_ignore_list", profile.getId());
    }

    // ======================================================================
    // Settings
    // ======================================================================

    /**
     * Loads a boolean setting from {@code players_settings}.
     *
     * @param profile      profile whose setting to load
     * @param key          logical setting key
     * @param defaultValue value returned when no setting is present
     * @return stored value or {@code defaultValue} on missing row or error
     */
    public boolean getSetting(Profile profile, String key, boolean defaultValue) {
        if (!ensureValidId(profile, "getSetting(" + key + ")")) {
            return defaultValue;
        }
        try {
            Boolean value = db.getBoolean(
                    "players_settings",
                    "value",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );
            return value != null ? value : defaultValue;
        } catch (SQLException e) {
            logger.severe("Failed to load setting '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
            return defaultValue;
        }
    }

    /**
     * Updates or inserts a boolean setting in {@code players_settings}.
     *
     * @param profile profile to update
     * @param key     logical setting key
     * @param value   value to store
     */
    public void setSetting(Profile profile, String key, boolean value) {
        if (!ensureValidId(profile, "setSetting(" + key + ")")) {
            return;
        }
        try {
            int updated = db.update(
                    "players_settings",
                    List.of("value"),
                    List.of(value),
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );

            if (updated == 0) {
                db.insert(
                        "players_settings",
                        List.of("player_id", "key", "value"),
                        List.of(profile.getId(), key, value)
                );
            }
        } catch (SQLException e) {
            logger.severe("Failed to update setting '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ======================================================================
    // Stats (BIGINT)
    // ======================================================================

    /**
     * Retrieves a numeric statistic from {@code players_stats}.
     *
     * @param profile profile whose stat to load
     * @param key     logical stat key
     * @return stored value or 0 if no row is present or an error occurs
     */
    public long getStat(Profile profile, String key) {
        if (!ensureValidId(profile, "getStat(" + key + ")")) {
            return 0L;
        }
        try {
            Long value = db.getLong(
                    "players_stats",
                    "value",
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );
            return value != null ? value : 0L;
        } catch (SQLException e) {
            logger.severe("Failed to load stat '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
            return 0L;
        }
    }

    /**
     * Sets a numeric statistic to an exact value.
     *
     * @param profile profile whose stat to update
     * @param key     logical stat key
     * @param value   new value
     */
    public void setStat(Profile profile, String key, long value) {
        if (!ensureValidId(profile, "setStat(" + key + ")")) {
            return;
        }
        try {
            int updated = db.update(
                    "players_stats",
                    List.of("value"),
                    List.of(value),
                    "player_id = ? AND `key` = ?",
                    List.of(profile.getId(), key)
            );

            if (updated == 0) {
                db.insert(
                        "players_stats",
                        List.of("player_id", "key", "value"),
                        List.of(profile.getId(), key, value)
                );
            }
        } catch (SQLException e) {
            logger.severe("Failed to update stat '" + key + "' for playerId="
                    + profile.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Adds a delta to a numeric statistic using {@link DatabaseAccess#incrementOrInsert}.
     *
     * @param profile profile whose stat to update
     * @param key     logical stat key
     * @param delta   value to add (may be negative)
     */
    public void addStat(Profile profile, String key, long delta) {
        if (!ensureValidId(profile, "addStat(" + key + ")")) {
            return;
        }
        if (delta == 0L) {
            return;
        }
        try {
            db.incrementOrInsert(
                    "players_stats",
                    List.of("player_id", "key"),
                    List.of(profile.getId(), key),
                    "value",
                    delta
            );
        } catch (SQLException e) {
            logger.severe("Failed to add stat delta for '" + key + "' playerId="
                    + profile.getId() + ", delta=" + delta + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ======================================================================
    // Identity lookups (players table)
    // ======================================================================

    /**
     * Checks whether a row exists in {@code players} with the given id.
     *
     * @param id database primary key (players.id)
     * @return {@code true} if a player with this id exists
     */
    public boolean idExists(int id) {
        if (id <= 0) {
            return false;
        }
        try {
            int count = db.count(
                    "players",
                    "id = ?",
                    List.of(id)
            );
            return count > 0;
        } catch (SQLException e) {
            logger.severe("Failed to check idExists(" + id + "): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Checks whether a row exists in {@code players} with the given UUID.
     *
     * @param uuid Mojang UUID
     * @return {@code true} if a player with this UUID exists
     */
    public boolean uuidExists(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            int count = db.count(
                    "players",
                    "uuid = ?",
                    List.of(uuid.toString())
            );
            return count > 0;
        } catch (SQLException e) {
            logger.severe("Failed to check uuidExists(" + uuid + "): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Resolves a database id from a Mojang UUID.
     *
     * @param uuid Mojang UUID
     * @return {@code players.id}, or {@code null} if no row exists
     */
    public Integer findIdByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            return db.getInt(
                    "players",
                    "id",
                    "uuid = ?",
                    List.of(uuid.toString())
            );
        } catch (SQLException e) {
            logger.severe("Failed to resolve id by uuid=" + uuid + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Resolves the Mojang UUID for a given database id.
     *
     * @param id database primary key (players.id)
     * @return UUID, or {@code null} if not found or invalid
     */
    public UUID getUuid(int id) {
        if (id <= 0) {
            return null;
        }
        try {
            String raw = db.getString(
                    "players",
                    "uuid",
                    "id = ?",
                    List.of(id)
            );
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ex) {
                logger.severe("Invalid UUID format in players.uuid for id=" + id + ": " + raw);
                return null;
            }
        } catch (SQLException e) {
            logger.severe("Failed to resolve uuid by id=" + id + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Loads the stored IGN from {@code players.name}.
     *
     * @param id database primary key (players.id)
     * @return IGN string, or {@code null} if not found
     */
    public String getIgn(int id) {
        if (id <= 0) {
            return null;
        }
        try {
            return db.getString(
                    "players",
                    "name",
                    "id = ?",
                    List.of(id)
            );
        } catch (SQLException e) {
            logger.severe("Failed to load IGN for id=" + id + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Loads the stored nick from {@code players.nick}.
     *
     * @param id database primary key (players.id)
     * @return nick string, or {@code null} if none is set
     */
    public String getNick(int id) {
        if (id <= 0) {
            return null;
        }
        try {
            return db.getString(
                    "players",
                    "nick",
                    "id = ?",
                    List.of(id)
            );
        } catch (SQLException e) {
            logger.severe("Failed to load nick for id=" + id + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Convenience helper: loads the stored nick for a given UUID.
     * <p>
     * Internally resolves the id first and then delegates to {@link #getNick(int)}.
     *
     * @param uuid Mojang UUID
     * @return nick string, or {@code null} if none is set or the UUID is unknown
     */
    public String getNickByUuid(UUID uuid) {
        Integer id = findIdByUuid(uuid);
        if (id == null || id <= 0) {
            return null;
        }
        return getNick(id);
    }

    /**
     * Convenience wrapper around {@link #findIdByUuid(UUID)} that returns 0 if no row is found.
     *
     * @param uuid Mojang UUID
     * @return players.id or 0 if none exists
     */
    public int getIdByUuid(UUID uuid) {
        Integer id = findIdByUuid(uuid);
        return (id != null) ? id : 0;
    }

 // inside ProfileStorage

    /**
     * Returns the stored group key/name from the players table.
     * Example: "default", "vip", "mod".
     *
     * @param id players.id
     * @return group key, or null if not set
     */
    public String getGroupKey(int id) {
        if (id <= 0) return null;
        try {
            return db.getString(
                    "players",
                    "group_key", // adjust to your actual column name
                    "id = ?",
                    List.of(id)
            );
        } catch (SQLException e) {
            logger.severe("Failed to load group_key for id=" + id + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Convenience helper: loads the group key for a given UUID.
     *
     * @param uuid Mojang UUID
     * @return group key, or null if unknown
     */
    public String getGroupKeyByUuid(UUID uuid) {
        Integer id = findIdByUuid(uuid);
        if (id == null || id <= 0) {
            return null;
        }
        return getGroupKey(id);
    }


	/**
	 * Loads per-player permission flags (if you ever decide to store them in the database).
	 * <p>
	 * At the moment this returns an empty list as a placeholder. If you later add a table
	 * such as {@code players_flags(player_id, flag_key)}, you can implement the loading
	 * logic here and keep the rest of the code unchanged.
	 *
	 * @param playerId players.id
	 * @return list of flags, never null
	 */
	public List<Perm> loadFlags(int playerId) {
	    // TODO: implement if you add a players_flags table
	    return new ArrayList<>();
	}
	
	

}