package playerdata;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import enums.Perm;
import model.Group;

/**
 * Central manager responsible for creating, resolving and destroying
 * {@link Profile} instances.
 *
 * <p>The manager distinguishes between two kinds of profiles:</p>
 *
 * <ul>
 *     <li>
 *         <strong>Online profiles</strong> are created when a player joins,
 *         hydrated from the database, cached in memory and reused for the
 *         player's entire online session.
 *     </li>
 *     <li>
 *         <strong>Offline profiles</strong> are temporary objects created when
 *         code needs to operate on an offline player. They are never inserted
 *         into the online cache.
 *     </li>
 * </ul>
 *
 * <p>This distinction is important because an online player must never have
 * multiple independent {@code Profile} instances. If that happened, one
 * instance could contain stale cached data while another modifies the
 * database.</p>
 *
 * <p>All long-lived profile caching should therefore be owned exclusively by
 * this class.</p>
 */
public final class ProfileManager {

    /**
     * Profiles belonging to players who are currently online.
     *
     * <p>The UUID is the authoritative runtime key because it is stable and
     * directly available from Bukkit's {@link Player} object.</p>
     */
    private final Map<UUID, Profile> online = new HashMap<>();

    /**
     * Shared persistence layer used to hydrate profiles and perform
     * player-related database operations.
     */
    private final ProfileStorage storage;

    /**
     * Creates a new profile manager.
     *
     * @param storage storage implementation used for profile persistence
     */
    public ProfileManager(ProfileStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    // ======================================================================
    // Online profile lifecycle
    // ======================================================================

    /**
     * Creates and registers the live profile for an online player.
     *
     * <p>This method is intended to be called when a player joins the server.
     * There must be exactly one live {@link Profile} instance for each online
     * player.</p>
     *
     * <p>The player's Bukkit UUID is used only to resolve their permanent
     * database identity. If the player has never joined before, a new row is
     * created in {@code players} and its {@code players.id} becomes the
     * canonical player id used by the plugin.</p>
     *
     * <p>The method performs the following steps:</p>
     *
     * <ol>
     *     <li>Checks whether the player already has a cached online profile.</li>
     *     <li>Resolves or creates the player's permanent {@code players.id}.</li>
     *     <li>Loads persistent identity data such as nickname.</li>
     *     <li>Constructs and hydrates the live profile.</li>
     *     <li>Stores the profile in the online cache.</li>
     * </ol>
     *
     * <p>If called multiple times for the same online player, the existing
     * cached profile is returned rather than creating a duplicate.</p>
     *
     * @param player Bukkit player who is currently online
     * @return live cached profile, or {@code null} if the permanent database
     *         identity could not be resolved or created
     */
    public Profile createOnlineProfile(Player player) {
        if (player == null) {
            return null;
        }

        UUID uuid = player.getUniqueId();

        /*
         * Never create two Profile instances for the same online player.
         *
         * All code operating on an online player must receive the same live
         * Profile object so its cached values remain authoritative.
         */
        Profile cached = online.get(uuid);

        if (cached != null) {
            return cached;
        }

        /*
         * Resolve the player's permanent database identity.
         *
         * UUID is used only at this boundary to locate the player in the
         * players table.
         *
         * If no database row exists yet, getOrCreatePlayerId() creates one and
         * returns the newly assigned AUTO_INCREMENT players.id.
         *
         * After this point the integer player id is the canonical identity used
         * for player-related SQL operations.
         */
        int playerId = storage.getOrCreatePlayerId(
                uuid,
                player.getName()
        );

        /*
         * A Profile must never exist without a valid players.id.
         *
         * playerId <= 0 means persistent identity resolution failed, so profile
         * creation must stop here.
         */
        if (playerId <= 0) {
            return null;
        }

        /*
         * Bukkit provides the authoritative current Minecraft username for an
         * online player.
         *
         * getOrCreatePlayerId() also synchronizes players.name when an existing
         * player's Minecraft username has changed.
         */
        String ign = player.getName();

        /*
         * Nickname is plugin-controlled persistent data and therefore comes from
         * the database.
         */
        String nick = storage.getNick(playerId);

        /*
         * Group resolution is intentionally left nullable until the group/rank
         * system is connected to ProfileManager.
         */
        Group group = null;

        /*
         * Construct the fully identified profile.
         *
         * buildProfile() is also responsible for loading the data that should be
         * cached for the duration of the player's online session.
         */
        Profile profile = buildProfile(
                playerId,
                uuid,
                ign,
                nick,
                group,
                player
        );

        /*
         * Register the live session profile.
         *
         * Offline profiles are never inserted into this map.
         */
        online.put(uuid, profile);

        return profile;
    }

    /**
     * Removes an online player's profile from the runtime cache.
     *
     * <p>This does NOT delete anything from the database. It only ends the
     * lifetime of the in-memory session object.</p>
     *
     * <p>Because profile mutations currently use write-through persistence,
     * there is nothing that must be flushed here. If write-behind caching is
     * introduced later, this method would become the natural place to flush
     * dirty data before removing the profile.</p>
     *
     * @param player player who disconnected
     * @return removed profile, or {@code null} if none was cached
     */
    public Profile deleteOnlineProfile(Player player) {
        if (player == null) {
            return null;
        }

        return online.remove(player.getUniqueId());
    }

    /**
     * Returns the already-created online profile for a Bukkit player.
     *
     * <p>This method deliberately performs no database lookup. A player's live
     * profile should have been created by {@link #createOnlineProfile(Player)}
     * when they joined.</p>
     *
     * @param player Bukkit player
     * @return cached profile, or {@code null} if no live profile exists
     */
    public Profile resolveOnline(Player player) {
        if (player == null) {
            return null;
        }

        return online.get(player.getUniqueId());
    }

    /**
     * Returns the cached online profile for a UUID without performing any
     * database access.
     *
     * @param uuid Mojang UUID
     * @return cached profile, or {@code null} if the player is not cached
     */
    public Profile getOnlineProfile(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        return online.get(uuid);
    }

    /**
     * Checks whether an online profile is currently cached.
     *
     * @param uuid Mojang UUID
     * @return {@code true} if the UUID currently has a live profile
     */
    public boolean isOnline(UUID uuid) {
        return uuid != null && online.containsKey(uuid);
    }

    /**
     * Returns a read-only view of all currently cached online profiles.
     *
     * @return collection of live profiles
     */
    public Collection<Profile> getOnlineProfiles() {
        return Collections.unmodifiableCollection(online.values());
    }

    // ----------------------------------------------------------------------
    // Compatibility helpers
    // ----------------------------------------------------------------------

    /**
     * Registers an already-created online profile.
     *
     * <p>Normally {@link #createOnlineProfile(Player)} should be used instead.
     * This method remains useful for code that explicitly constructs/hydrates a
     * profile before registration.</p>
     *
     * @param profile profile to register
     */
    public void registerOnline(Profile profile) {
        if (profile == null
                || profile.getUuid() == null
                || profile.getPlayer() == null) {
            return;
        }

        online.put(profile.getUuid(), profile);
    }

    /**
     * Compatibility alias for removing an online profile.
     *
     * @param player player leaving the server
     */
    public void unregisterOnline(Player player) {
        deleteOnlineProfile(player);
    }

    // ======================================================================
    // General profile resolution
    // ======================================================================

    /**
     * Resolves a player profile by UUID.
     *
     * <p>If that player is currently online, the existing live cached profile
     * is always returned. This prevents two profile instances from
     * representing the same online player.</p>
     *
     * <p>If the player is offline, a temporary profile is hydrated from the
     * database and returned. That temporary object is not cached by this
     * manager.</p>
     *
     * @param uuid Mojang UUID
     * @return online cached profile, temporary offline profile, or
     *         {@code null} if unknown
     */
    public Profile resolveByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        Profile cached = online.get(uuid);

        if (cached != null) {
            return cached;
        }

        return loadOfflineByUuid(uuid);
    }

    /**
     * Optional wrapper around {@link #resolveByUuid(UUID)}.
     *
     * @param uuid Mojang UUID
     * @return optional profile
     */
    public Optional<Profile> optionalByUuid(UUID uuid) {
        return Optional.ofNullable(resolveByUuid(uuid));
    }

    /**
     * Resolves a profile by database player id.
     *
     * <p>The online cache is checked first. If no live profile owns the id,
     * a temporary offline profile is loaded from the database.</p>
     *
     * @param id primary key from {@code players.id}
     * @return resolved profile, or {@code null} if unknown
     */
    public Profile resolveById(int id) {
        if (id <= 0) {
            return null;
        }

        /*
         * Online population is normally small enough that this scan is trivial.
         *
         * If this ever becomes hot code on a very large server, a secondary
         * Map<Integer, Profile> can be added later.
         */
        for (Profile profile : online.values()) {
            if (profile.getId() == id) {
                return profile;
            }
        }

        return loadOfflineById(id);
    }

    /**
     * Optional wrapper around {@link #resolveById(int)}.
     *
     * @param id database player id
     * @return optional profile
     */
    public Optional<Profile> optionalById(int id) {
        return Optional.ofNullable(resolveById(id));
    }

    /**
     * Checks whether a database player exists with the given UUID.
     *
     * @param uuid Mojang UUID
     * @return {@code true} if the player exists in persistent storage
     */
    public boolean exists(UUID uuid) {
        return uuid != null && storage.uuidExists(uuid);
    }

    /**
     * Checks whether a database player exists with the given primary key.
     *
     * @param id database player id
     * @return {@code true} if the player exists in persistent storage
     */
    public boolean exists(int id) {
        return id > 0 && storage.idExists(id);
    }

    // ======================================================================
    // Offline profile hydration
    // ======================================================================

    /**
     * Loads a temporary profile by UUID.
     *
     * <p>This method never adds the returned profile to the online cache.</p>
     *
     * @param uuid Mojang UUID
     * @return temporary offline profile, or {@code null} if unknown
     */
    private Profile loadOfflineByUuid(UUID uuid) {
        int id = storage.getIdByUuid(uuid);

        if (id <= 0) {
            return null;
        }

        String ign = storage.getIgn(id);
        String nick = storage.getNick(id);

        Group group = null;

        return buildProfile(
                id,
                uuid,
                ign,
                nick,
                group,
                null
        );
    }

    /**
     * Loads a temporary profile by database id.
     *
     * <p>This method never adds the returned profile to the online cache.</p>
     *
     * @param id database player id
     * @return temporary offline profile, or {@code null} if unknown
     */
    private Profile loadOfflineById(int id) {
        UUID uuid = storage.getUuid(id);

        if (uuid == null) {
            return null;
        }

        String ign = storage.getIgn(id);
        String nick = storage.getNick(id);

        Group group = null;

        return buildProfile(
                id,
                uuid,
                ign,
                nick,
                group,
                null
        );
    }

    // ======================================================================
    // Profile construction
    // ======================================================================

    /**
     * Constructs a complete profile identity.
     *
     * <p>For online players this additionally loads the values that should be
     * cached for the entire online session. Offline profiles deliberately skip
     * that preload and continue to use database-backed getters.</p>
     *
     * @param id database player id
     * @param uuid Mojang UUID
     * @param ign last/current Minecraft name
     * @param nick stored nickname
     * @param group resolved group, currently nullable
     * @param player Bukkit player when online; {@code null} when offline
     * @return constructed profile
     */
    private Profile buildProfile(
            int id,
            UUID uuid,
            String ign,
            String nick,
            Group group,
            Player player
    ) {
        List<Perm> flags = storage.loadFlags(id);

        Profile profile = new Profile(
                storage,
                id,
                uuid,
                ign,
                nick,
                group,
                player,
                flags
        );

        /*
         * Only session profiles preload database values.
         *
         * Temporary offline Profile objects intentionally avoid paying the
         * cost of loading every cached field unless that field is actually
         * requested.
         */
        if (player != null) {
            profile.loadOnlineCache();
        }

        return profile;
    }

    // ======================================================================
    // Shutdown
    // ======================================================================

    /**
     * Removes every online profile from memory.
     *
     * <p>Called during plugin shutdown.</p>
     */
    public void clear() {
        online.clear();
    }
}