package playerdata;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;

import database.DatabaseAccess;
import enums.FriendshipStatus;
import enums.GroupType;
import enums.Perm;
import utils.LocationSerializer;

/**
 * Centralized data access for all profile-related persistence.
 * <p>
 * This class encapsulates all SQL interaction for player-specific data:
 * <ul>
 *     <li>Stored locations</li>
 *     <li>Nickname, reply target and welcome message</li>
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

    private static final String PLAYER_LOCATION_TABLE = "player_locations";
    private static final String PLAYER_PROFILE_DATA_TABLE = "player_profile_data";
    private static final String PLAYER_TRUST_TABLE = "player_trust";
    private static final String PLAYER_FRIENDSHIP_TABLE = "player_friendships";

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

    /**
     * Resolves or creates the permanent database identity for a player.
     *
     * <p>The {@code players.id} column is the plugin's canonical internal player
     * identifier. UUID is used here only to locate the player's row in the
     * {@code players} table. Once the id has been resolved, all other
     * player-related database operations should use that integer id.</p>
     *
     * <p>If the UUID already exists in the database, the existing
     * {@code players.id} is returned. The stored Minecraft username is also
     * synchronized with the current name supplied by Bukkit, because Minecraft
     * usernames may change while UUIDs remain stable.</p>
     *
     * <p>If the UUID does not yet exist, a new row is inserted into
     * {@code players} using the UUID and current Minecraft username. The newly
     * generated {@code players.id} is then resolved and returned.</p>
     *
     * <p>This method never intentionally returns a partially resolved player.
     * A return value of {@code 0} means that the player could not be resolved or
     * created and a {@link Profile} should therefore not be constructed.</p>
     *
     * @param uuid Mojang/Bukkit UUID of the player
     * @param name current Minecraft username
     * @return valid {@code players.id}, or {@code 0} if resolution/creation fails
     */
    public int getOrCreatePlayerId(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            logger.warning("ProfileStorage: cannot resolve player because UUID or name is invalid.");
            return 0;
        }

        Integer playerId = findIdByUuid(uuid);

        if (playerId == null) {
            try {
                /* Name is claimed afterwards so a recycled username can be released first. */
                db.insert("players", List.of("uuid"), List.of(uuid.toString()));
                playerId = findIdByUuid(uuid);
            } catch (SQLException exception) {
                logger.log(Level.SEVERE, "Failed to create player record for " + name
                        + " (" + uuid + ").", exception);
                return 0;
            }

            if (playerId == null || playerId <= 0) {
                logger.severe("Created player record for " + name
                        + " but could not resolve its generated id.");
                return 0;
            }

            logger.info("Created player database record: " + name + " -> playerId=" + playerId);
        }

        if (!claimCurrentUsername(playerId, name) || !ensurePlayerProfileDataRow(playerId)) {
            return 0;
        }

        return playerId;
    }

    /**
     * Gives the joining UUID ownership of its current Minecraft username.
     *
     * <p>A real account name outranks a nickname. Any matching nickname owned
     * by another player is cleared before the username is assigned.</p>
     */
    private boolean claimCurrentUsername(int playerId, String username) {
        try {
            Integer conflictingNicknameOwnerId = db.getInt(
                    PLAYER_PROFILE_DATA_TABLE, "player_id",
                    "nickname_plain = ? AND player_id <> ?", List.of(username, playerId));

            if (conflictingNicknameOwnerId != null) {
                db.update(PLAYER_PROFILE_DATA_TABLE,
                        List.of("nickname_formatted", "nickname_plain"),
                        java.util.Arrays.asList(null, null),
                        "player_id = ?", conflictingNicknameOwnerId);
                logger.info("Cleared nickname owned by playerId=" + conflictingNicknameOwnerId
                        + " because the Minecraft username '" + username + "' was claimed.");
            }

            db.update("players", List.of("name"), Collections.singletonList(null),
                    "name = ? AND id <> ?", username, playerId);

            return db.update("players", List.of("name", "last_login_at"),
                    List.of(username, LocalDateTime.now()), "id = ?", playerId) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to claim username '" + username
                    + "' for playerId=" + playerId + ".", exception);
            return false;
        }
    }

    /** Creates the one-to-one profile-data row required by core profile fields. */
    private boolean ensurePlayerProfileDataRow(int playerId) {
        try {
            db.insertIfAbsent(PLAYER_PROFILE_DATA_TABLE,
                    List.of("player_id"), List.of(playerId));
            return true;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to create profile data for playerId="
                    + playerId + ".", exception);
            return false;
        }
    }
    
    // ======================================================================
    // Locations
    // ======================================================================

    /**
     * Retrieves a stored location for the given profile and key.
     *
     * <p>This method addresses system-managed locations only. Player homes
     * use the same table with {@code is_player_home = 1}, but will receive a
     * separate API when home behaviour is implemented.</p>
     *
     * @param profile profile whose location to load
     * @param key system location name, such as {@code death} or {@code last}
     * @return stored location, or {@code null} if none is present or an error occurs
     */
    public Location getLocation(Profile profile, String key) {
        if (!ensureValidId(profile, "getLocation(" + key + ")")) {
            return null;
        }
        try {
            String data = db.getString(
                    PLAYER_LOCATION_TABLE,
                    "location_data",
                    "player_id = ? AND is_player_home = ? AND location_name = ?",
                    profile.getId(), false, key
            );
            return LocationSerializer.deserialize(data);
        } catch (SQLException exception) {
            logger.log(
                    Level.SEVERE,
                    "Failed to load location '"
                            + key
                            + "' for playerId="
                            + profile.getId()
                            + ": "
                            + exception.getMessage(),
                    exception
            );
            return null;
        }
    }

    /**
     * Stores or updates a location for the given profile and key.
     *
     * @param profile  profile whose location to store
     * @param key system location name
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

        String data = LocationSerializer.serialize(location);
        if (data == null) {
            logger.warning(
                    "Refused to store invalid location '"
                            + key
                            + "' for playerId="
                            + profile.getId()
            );
            return;
        }

        try {
            db.insertIfAbsent(
                    PLAYER_LOCATION_TABLE,
                    List.of(
                            "player_id",
                            "is_player_home",
                            "location_name",
                            "location_data"
                    ),
                    List.of(
                            profile.getId(),
                            false,
                            key,
                            data
                    )
            );

            db.update(
                    PLAYER_LOCATION_TABLE,
                    List.of(
                            "location_data",
                            "recorded_at"
                    ),
                    List.of(
                            data,
                            LocalDateTime.now()
                    ),
                    "player_id = ? AND is_player_home = ? AND location_name = ?",
                    profile.getId(), false, key
            );
        } catch (SQLException exception) {
            logger.log(
                    Level.SEVERE,
                    "Failed to store location '"
                            + key
                            + "' for playerId="
                            + profile.getId()
                            + ": "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * Deletes a stored location for the given profile and key.
     *
     * @param profile profile whose location to delete
     * @param key system location name
     */
    public void deleteLocation(Profile profile, String key) {
        if (!ensureValidId(profile, "deleteLocation(" + key + ")")) {
            return;
        }
        try {
            db.delete(
                    PLAYER_LOCATION_TABLE,
                    "player_id = ? AND is_player_home = ? AND location_name = ?",
                    profile.getId(), false, key
            );
        } catch (SQLException exception) {
            logger.log(
                    Level.SEVERE,
                    "Failed to delete location '"
                            + key
                            + "' for playerId="
                            + profile.getId()
                            + ": "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    // ======================================================================
    // Reply target & welcome message
    // ======================================================================

    /**
     * Loads the last player this profile can answer with {@code /reply}.
     *
     * @param profile profile whose reply target should be loaded
     * @return target {@code players.id}, or {@code null} when no conversation exists
     */
    public Integer getReplyTargetId(Profile profile) {
        if (!ensureValidId(profile, "getReplyTargetId")) return null;

        try {
            return db.getInt(PLAYER_PROFILE_DATA_TABLE, "reply_target_player_id",
                    "player_id = ?", List.of(profile.getId()));
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load reply target for playerId="
                    + profile.getId() + ": " + exception.getMessage(), exception);
            return null;
        }
    }

    /**
     * Stores the last player this profile can answer with {@code /reply}.
     *
     * @param profile profile whose reply target should be changed
     * @param targetId target {@code players.id}, or {@code null} to clear it
     * @return {@code true} when the player row was updated
     */
    public boolean setReplyTargetId(Profile profile, Integer targetId) {
        if (!ensureValidId(profile, "setReplyTargetId")) return false;
        if (!ensurePlayerProfileDataRow(profile.getId())) return false;

        try {
            return db.update(PLAYER_PROFILE_DATA_TABLE, List.of("reply_target_player_id"),
                    Collections.singletonList(targetId), "player_id = ?", profile.getId()) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to update reply target for playerId="
                    + profile.getId() + ": " + exception.getMessage(), exception);
            return false;
        }
    }

    /**
     * Loads the message displayed immediately before the player's join line.
     *
     * @param profile profile whose welcome message to load
     * @return welcome message, or {@code null} if none is set
     */
    public String getWelcome(Profile profile) {
        if (!ensureValidId(profile, "getWelcome")) return null;

        try {
            return db.getString(PLAYER_PROFILE_DATA_TABLE, "welcome_message",
                    "player_id = ?", profile.getId());
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load welcome message for playerId="
                    + profile.getId() + ": " + exception.getMessage(), exception);
            return null;
        }
    }

    /**
     * Stores the message displayed immediately before the player's join line.
     *
     * @param profile profile to update
     * @param welcome new message, or {@code null} to clear
     * @return {@code true} when the player row was updated
     */
    public boolean setWelcome(Profile profile, String welcome) {
        if (!ensureValidId(profile, "setWelcome")) return false;
        if (!ensurePlayerProfileDataRow(profile.getId())) return false;

        try {
            return db.update(PLAYER_PROFILE_DATA_TABLE, List.of("welcome_message"),
                    Collections.singletonList(welcome), "player_id = ?", profile.getId()) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to update welcome message for playerId="
                    + profile.getId() + ": " + exception.getMessage(), exception);
            return false;
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
     * Returns the relationship state between a profile and another player.
     *
     * <p>The two ids are sorted before querying because one unordered player
     * pair owns exactly one row in {@code player_friendships}. Request direction
     * remains available through {@code requester_player_id}.</p>
     *
     * @param profile player from whose perspective the state is requested
     * @param otherPlayerId other {@code players.id}
     * @return current state relative to {@code profile}
     */
    public FriendshipStatus getFriendshipStatus(Profile profile, int otherPlayerId) {
        if (!ensureValidRelation(profile, otherPlayerId, "getFriendshipStatus")) {
            return FriendshipStatus.NONE;
        }

        int firstPlayerId = Math.min(profile.getId(), otherPlayerId);
        int secondPlayerId = Math.max(profile.getId(), otherPlayerId);

        try {
            Map<String, Object> row = db.getRow(
                    PLAYER_FRIENDSHIP_TABLE,
                    List.of("requester_player_id", "accepted_at"),
                    "first_player_id = ? AND second_player_id = ?",
                    List.of(firstPlayerId, secondPlayerId)
            );

            if (row.isEmpty()) return FriendshipStatus.NONE;
            if (row.get("accepted_at") != null) return FriendshipStatus.ACCEPTED;

            int requesterPlayerId = ((Number) row.get("requester_player_id")).intValue();
            return requesterPlayerId == profile.getId()
                    ? FriendshipStatus.OUTGOING_REQUEST
                    : FriendshipStatus.INCOMING_REQUEST;
        } catch (SQLException | ClassCastException exception) {
            logger.log(Level.SEVERE, "Failed to load friendship between playerId="
                    + profile.getId() + " and playerId=" + otherPlayerId + ": "
                    + exception.getMessage(), exception);
            return FriendshipStatus.UNAVAILABLE;
        }
    }

    /**
     * Creates a pending friend request when no row exists for the pair.
     *
     * @param requester profile sending the request
     * @param targetPlayerId player receiving the request
     * @return {@code true} when a new request row was inserted
     */
    public boolean createFriendRequest(Profile requester, int targetPlayerId) {
        if (!ensureValidRelation(requester, targetPlayerId, "createFriendRequest")) return false;

        int firstPlayerId = Math.min(requester.getId(), targetPlayerId);
        int secondPlayerId = Math.max(requester.getId(), targetPlayerId);

        try {
            return db.insert(
                    PLAYER_FRIENDSHIP_TABLE,
                    List.of("first_player_id", "second_player_id", "requester_player_id"),
                    List.of(firstPlayerId, secondPlayerId, requester.getId())
            ) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to create friend request: playerId="
                    + requester.getId() + ", targetPlayerId=" + targetPlayerId + ": "
                    + exception.getMessage(), exception);
            return false;
        }
    }

    /**
     * Accepts a request only when it was sent by the supplied requester.
     *
     * @param receiver profile accepting the request
     * @param requesterPlayerId player who originally sent the request
     * @return {@code true} when a pending request became an accepted friendship
     */
    public boolean acceptFriendRequest(Profile receiver, int requesterPlayerId) {
        if (!ensureValidRelation(receiver, requesterPlayerId, "acceptFriendRequest")) return false;

        int firstPlayerId = Math.min(receiver.getId(), requesterPlayerId);
        int secondPlayerId = Math.max(receiver.getId(), requesterPlayerId);

        try {
            return db.update(
                    PLAYER_FRIENDSHIP_TABLE,
                    List.of("accepted_at"),
                    List.of(LocalDateTime.now()),
                    "first_player_id = ? AND second_player_id = ? "
                            + "AND requester_player_id = ? AND accepted_at IS NULL",
                    firstPlayerId, secondPlayerId, requesterPlayerId
            ) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to accept friend request: playerId="
                    + receiver.getId() + ", requesterPlayerId=" + requesterPlayerId + ": "
                    + exception.getMessage(), exception);
            return false;
        }
    }

    /**
     * Deletes the one row shared by two players.
     *
     * <p>The same operation supports declining a request, cancelling a sent
     * request and removing an accepted friend. Command code decides which of
     * those actions is currently valid by checking the friendship state first.</p>
     *
     * @param profile one participant
     * @param otherPlayerId the other participant
     * @return {@code true} when a row was deleted
     */
    public boolean deleteFriendship(Profile profile, int otherPlayerId) {
        if (!ensureValidRelation(profile, otherPlayerId, "deleteFriendship")) return false;

        int firstPlayerId = Math.min(profile.getId(), otherPlayerId);
        int secondPlayerId = Math.max(profile.getId(), otherPlayerId);

        try {
            return db.delete(
                    PLAYER_FRIENDSHIP_TABLE,
                    "first_player_id = ? AND second_player_id = ?",
                    firstPlayerId, secondPlayerId
            ) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to delete friendship between playerId="
                    + profile.getId() + " and playerId=" + otherPlayerId + ": "
                    + exception.getMessage(), exception);
            return false;
        }
    }

    /**
     * Returns accepted friends only; pending requests are deliberately excluded.
     *
     * @param profile profile whose accepted friends should be loaded
     * @return database ids of accepted friends
     */
    public List<Integer> getFriendIds(Profile profile) {
        if (!ensureValidId(profile, "getFriendIds")) return Collections.emptyList();

        try {
            List<Integer> friendIds = new ArrayList<>();
            friendIds.addAll(db.getIntList(
                    PLAYER_FRIENDSHIP_TABLE,
                    "second_player_id",
                    "first_player_id = ? AND accepted_at IS NOT NULL",
                    List.of(profile.getId()),
                    0
            ));
            friendIds.addAll(db.getIntList(
                    PLAYER_FRIENDSHIP_TABLE,
                    "first_player_id",
                    "second_player_id = ? AND accepted_at IS NOT NULL",
                    List.of(profile.getId()),
                    0
            ));
            return friendIds;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load friends for playerId="
                    + profile.getId() + ": " + exception.getMessage(), exception);
            return Collections.emptyList();
        }
    }

    /**
     * Returns pending requests sent to the supplied profile.
     *
     * @param profile request recipient
     * @return database IDs of players waiting for this profile's answer
     */
    public List<Integer> getIncomingFriendRequestIds(Profile profile) {
        return getPendingFriendRequestIds(profile, false);
    }

    /**
     * Returns pending requests sent by the supplied profile.
     *
     * @param profile request sender
     * @return database IDs of players who have not answered yet
     */
    public List<Integer> getOutgoingFriendRequestIds(Profile profile) {
        return getPendingFriendRequestIds(profile, true);
    }

    /** Loads pending request counterparts from both sides of the canonical pair. */
    private List<Integer> getPendingFriendRequestIds(Profile profile, boolean sentByProfile) {
        if (!ensureValidId(profile, "getPendingFriendRequestIds")) return Collections.emptyList();

        String requesterCondition = sentByProfile
                ? "requester_player_id = ?"
                : "requester_player_id <> ?";

        try {
            List<Integer> playerIds = new ArrayList<>();
            playerIds.addAll(db.getIntList(PLAYER_FRIENDSHIP_TABLE, "second_player_id",
                    "first_player_id = ? AND " + requesterCondition + " AND accepted_at IS NULL",
                    List.of(profile.getId(), profile.getId()), 0));
            playerIds.addAll(db.getIntList(PLAYER_FRIENDSHIP_TABLE, "first_player_id",
                    "second_player_id = ? AND " + requesterCondition + " AND accepted_at IS NULL",
                    List.of(profile.getId(), profile.getId()), 0));
            return playerIds;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load pending friend requests for playerId="
                    + profile.getId() + ".", exception);
            return Collections.emptyList();
        }
    }

    /** Validates both ids before a relationship operation reaches SQL. */
    private boolean ensureValidRelation(Profile profile, int otherPlayerId, String context) {
        if (!ensureValidId(profile, context)) return false;
        if (otherPlayerId > 0 && otherPlayerId != profile.getId()) return true;

        logger.warning("ProfileStorage: attempted " + context + " with invalid target player id: "
                + otherPlayerId);
        return false;
    }

    // ----- Trust -----

    public boolean addTrusted(Profile profile, int targetId) {
        if (!ensureValidRelation(profile, targetId, "addTrusted")) return false;

        try {
            return db.insert(PLAYER_TRUST_TABLE,
                    List.of("trusting_player_id", "trusted_player_id"),
                    List.of(profile.getId(), targetId)) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to add trust: playerId=" + profile.getId()
                    + ", targetId=" + targetId + ".", exception);
            return false;
        }
    }

    public boolean removeTrusted(Profile profile, int targetId) {
        if (!ensureValidRelation(profile, targetId, "removeTrusted")) return false;

        try {
            return db.delete(PLAYER_TRUST_TABLE,
                    "trusting_player_id = ? AND trusted_player_id = ?",
                    profile.getId(), targetId) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to remove trust: playerId=" + profile.getId()
                    + ", targetId=" + targetId + ".", exception);
            return false;
        }
    }

    /**
     * Checks whether {@code playerId} trusts {@code targetId}.
     */
    public boolean isTrusted(int playerId, int targetId) {
        try {
            return db.count(PLAYER_TRUST_TABLE,
                    "trusting_player_id = ? AND trusted_player_id = ?",
                    List.of(playerId, targetId)) > 0;
        } catch (SQLException exception) {
            logger.severe("Failed to check trust relation: " + playerId + " -> "
                    + targetId + ": " + exception.getMessage());
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
        try {
            return db.getIntList(PLAYER_TRUST_TABLE, "trusted_player_id",
                    "trusting_player_id = ?", List.of(profile.getId()), 0);
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load trust list for playerId="
                    + profile.getId() + ".", exception);
            return Collections.emptyList();
        }
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
     * Resolves the permanent player ID belonging to a current Minecraft name.
     *
     * <p>The {@code players.name} collation performs the intended
     * case-insensitive comparison.</p>
     *
     * @param username current Minecraft username
     * @return matching {@code players.id}, or {@code null} when unknown
     */
    public Integer findIdByIgn(String username) {
        if (username == null || username.isBlank()) return null;

        try {
            return db.getInt("players", "id", "name = ?", List.of(username));
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to resolve player by username '"
                    + username + "'.", exception);
            return null;
        }
    }

    /**
     * Resolves the permanent player ID belonging to a plain nickname.
     *
     * @param plainNickname nickname without color codes
     * @return matching {@code players.id}, or {@code null} when unknown
     */
    public Integer findIdByNickname(String plainNickname) {
        if (plainNickname == null || plainNickname.isBlank()) return null;

        try {
            return db.getInt(PLAYER_PROFILE_DATA_TABLE, "player_id",
                    "nickname_plain = ?", List.of(plainNickname));
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to resolve player by nickname '"
                    + plainNickname + "'.", exception);
            return null;
        }
    }

    /**
     * Loads the stored, color-formatted nickname from
     * {@code player_profile_data.nickname_formatted}.
     *
     * @param id database primary key ({@code players.id})
     * @return raw formatted nickname, or {@code null} if none is set
     */
    public String getNick(int id) {
        if (id <= 0) return null;

        try {
            return db.getString(PLAYER_PROFILE_DATA_TABLE, "nickname_formatted",
                    "player_id = ?", id);
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load nickname for playerId=" + id
                    + ": " + exception.getMessage(), exception);
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

    /**
     * Stores both representations of a nickname in one update.
     *
     * <p>The formatted value is used for display. The plain lowercase value is
     * used for indexed lookup and case-insensitive uniqueness.</p>
     *
     * @param profile profile whose nickname should be changed
     * @param formattedNickname raw nickname including allowed color codes, or
     *                          {@code null} to clear it
     * @param plainNickname lowercase visible nickname, or {@code null} to clear it
     * @return {@code true} when the profile-data row was updated
     */
    public boolean setNick(Profile profile, String formattedNickname, String plainNickname) {
        if (!ensureValidId(profile, "setNick")) return false;
        if (!ensurePlayerProfileDataRow(profile.getId())) return false;

        try {
            return db.update(PLAYER_PROFILE_DATA_TABLE,
                    List.of("nickname_formatted", "nickname_plain"),
                    java.util.Arrays.asList(formattedNickname, plainNickname),
                    "player_id = ?", profile.getId()) == 1;
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to update nickname for playerId="
                    + profile.getId() + ": " + exception.getMessage(), exception);
            return false;
        }
    }
    
    // ======================================================================
    // Player groups
    // ======================================================================


    /**
     * Loads the player's group type from {@code players.group_id}.
     *
     * <p>The database stores the stable numeric identifier defined by
     * {@link GroupType#getDatabaseId()}. The identifier is resolved into the
     * canonical enum value before leaving the persistence layer.</p>
     *
     * <p>No fallback is applied. A missing or unknown group ID represents
     * invalid persistent data and must not silently alter access rights.</p>
     *
     * @param playerId permanent primary key from {@code players.id}
     * @return canonical group type stored for the player
     * @throws IllegalArgumentException if {@code playerId} is not positive
     * @throws IllegalStateException if the group cannot be loaded or resolved
     */
    public GroupType loadPlayerGroupType(int playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException(
                    "Cannot load group for invalid player ID: " + playerId
            );
        }

        final Integer storedGroupId;

        try {
            storedGroupId = db.getInt(
                    "players",
                    "group_id",
                    "id = ?",
                    List.of(playerId)
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to load group_id for playerId=" + playerId,
                    exception
            );
        }

        if (storedGroupId == null) {
            throw new IllegalStateException(
                    "Player row has no group_id for playerId=" + playerId
            );
        }

        try {
            return GroupType.requireByDatabaseId(storedGroupId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Player row contains unknown group_id="
                            + storedGroupId
                            + " for playerId="
                            + playerId,
                    exception
            );
        }
    }


    /**
     * Loads the group type for a player UUID.
     *
     * <p>The UUID is resolved to the permanent numeric player ID before the
     * group is loaded.</p>
     *
     * @param playerUuid permanent Mojang UUID
     * @return canonical group type stored for the player
     * @throws IllegalArgumentException if {@code playerUuid} is {@code null}
     * @throws IllegalStateException if the player or group cannot be resolved
     */
    public GroupType loadPlayerGroupType(UUID playerUuid) {
        if (playerUuid == null) {
            throw new IllegalArgumentException(
                    "Cannot load group for a null player UUID."
            );
        }

        Integer playerId = findIdByUuid(playerUuid);

        if (playerId == null || playerId <= 0) {
            throw new IllegalStateException(
                    "Cannot load group because no player row exists for UUID "
                            + playerUuid
            );
        }

        return loadPlayerGroupType(playerId);
    }
	

    /**
     * Persists a new group for a player.
     *
     * @param playerId permanent primary key from {@code players.id}
     * @param newGroupType group that should be assigned
     * @return {@code true} when exactly one player row was updated
     */
    public boolean updatePlayerGroup(
            int playerId,
            GroupType newGroupType
    ) {
        if (playerId <= 0) {
            logger.warning(
                    "ProfileStorage: attempted to update group for invalid playerId="
                            + playerId
            );
            return false;
        }

        if (newGroupType == null) {
            throw new IllegalArgumentException(
                    "New player group cannot be null."
            );
        }

        try {
            int updatedRows = db.update(
                    "players",
                    List.of("group_id"),
                    List.of(newGroupType.getDatabaseId()),
                    "id = ?",
                    List.of(playerId)
            );

            if (updatedRows != 1) {
                logger.warning(
                        "Expected to update one player group row, but updated "
                                + updatedRows
                                + " rows for playerId="
                                + playerId
                );
                return false;
            }

            return true;
        } catch (SQLException exception) {
            logger.severe(
                    "Failed to update group for playerId="
                            + playerId
                            + " to "
                            + newGroupType.name()
                            + " (group_id="
                            + newGroupType.getDatabaseId()
                            + "): "
                            + exception.getMessage()
            );
            exception.printStackTrace();
            return false;
        }
    }
	

    /**
     * Persists a new group for a resolved profile and updates its cached group.
     *
     * @param profile resolved player profile
     * @param newGroupType group that should be assigned
     * @return {@code true} when persistent and cached state were updated
     */
    public boolean updatePlayerGroup(
            Profile profile,
            GroupType newGroupType
    ) {
        if (!ensureValidId(profile, "updatePlayerGroup")) {
            return false;
        }

        if (newGroupType == null) {
            throw new IllegalArgumentException(
                    "New player group cannot be null."
            );
        }

        boolean databaseUpdated = updatePlayerGroup(
                profile.getId(),
                newGroupType
        );

        if (!databaseUpdated) {
            return false;
        }

        profile.setGroup(newGroupType.getGroup());
        return true;
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
