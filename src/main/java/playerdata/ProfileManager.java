package playerdata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import enums.Perm;
import model.Group;

/**
 * Central manager for {@link Profile} instances.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Keep a cache of online profiles keyed by UUID.</li>
 *     <li>Resolve profiles by {@link Player}, {@link UUID} or database id.</li>
 *     <li>Hydrate {@link Profile} instances from {@link ProfileStorage}.</li>
 * </ul>
 *
 * Group handling is intentionally left out here for now. If you later introduce a
 * dedicated GroupManager, this class can be extended to resolve and inject
 * {@link Group} instances during hydration.
 */
public final class ProfileManager {

    /** Online profile cache, keyed by Mojang UUID. */
    private final Map<UUID, Profile> online = new HashMap<>();

    /** Storage helper for all persistent profile-related data. */
    private final ProfileStorage storage;

    /**
     * Creates a new {@code ProfileManager}.
     *
     * @param storage backing storage helper
     */
    public ProfileManager(ProfileStorage storage) {
        this.storage = storage;
    }

    // ------------------------------------------------------------
    // ONLINE PROFILES
    // ------------------------------------------------------------

    /**
     * Resolves a profile for an online player.
     * <p>
     * If a profile already exists in the cache, it is returned. Otherwise a new
     * profile is hydrated from the database and returned, but not automatically
     * stored in the cache – call {@link #registerOnline(Profile)} after creation
     * if you want it cached.
     *
     * @param player online Bukkit player
     * @return hydrated {@link Profile}, or {@code null} if the player is not known in DB
     */
    public Profile resolveOnline(Player player) {
        UUID uuid = player.getUniqueId();
        Profile cached = online.get(uuid);
        if (cached != null) {
            return cached;
        }

        // Look up the database id for this UUID
        int id = storage.getIdByUuid(uuid);
        if (id <= 0) {
            // No row in players table for this UUID
            return null;
        }

        // Load nick and any persistent data we have
        String ign = player.getName();                    // trust live IGN
        String nick = storage.getNickByUuid(uuid);        // may be null

        // Group resolution is not wired yet – pass null for now
        Group group = null;

        return loadFullProfile(
                id,
                uuid,
                ign,
                nick,
                group,
                player
        );
    }

    /**
     * Registers a profile as online in the internal cache.
     *
     * @param profile profile to register
     */
    public void registerOnline(Profile profile) {
        if (profile == null || profile.getUuid() == null) {
            return;
        }
        online.put(profile.getUuid(), profile);
    }

    /**
     * Removes a player from the online cache.
     *
     * @param player player that has gone offline
     */
    public void unregisterOnline(Player player) {
        if (player == null) return;
        online.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------
    // OFFLINE PROFILES BY UUID
    // ------------------------------------------------------------

    /**
     * Resolves a profile by UUID.
     * <p>
     * If the player is online, the cached profile is returned. Otherwise a new
     * offline profile is hydrated from {@link ProfileStorage}.
     *
     * @param uuid Mojang UUID
     * @return hydrated {@link Profile}, or {@code null} if not found
     */
    public Profile resolveByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        // If online, return hot cache
        Profile cached = online.get(uuid);
        if (cached != null) {
            return cached;
        }

        // Offline load
        return loadOfflineByUuid(uuid);
    }

    /**
     * Safe wrapper around {@link #resolveByUuid(UUID)}.
     *
     * @param uuid Mojang UUID
     * @return optional profile
     */
    public Optional<Profile> optionalByUuid(UUID uuid) {
        return Optional.ofNullable(resolveByUuid(uuid));
    }

    /**
     * Checks whether a player with the given UUID exists in {@code players}.
     *
     * @param uuid Mojang UUID
     * @return true if a row exists
     */
    public boolean exists(UUID uuid) {
        return storage.uuidExists(uuid);
    }

    // ------------------------------------------------------------
    // OFFLINE PROFILES BY DB ID
    // ------------------------------------------------------------

    /**
     * Resolves a profile by database id.
     * <p>
     * If the player is online, the cached profile is returned. Otherwise a new
     * offline profile is hydrated from {@link ProfileStorage}.
     *
     * @param id players.id
     * @return hydrated {@link Profile}, or {@code null} if not found
     */
    public Profile resolveById(int id) {
        if (id <= 0) {
            return null;
        }

        // Check if someone online has this id
        for (Profile p : online.values()) {
            if (p.getId() == id) {
                return p;
            }
        }

        return loadOfflineById(id);
    }

    /**
     * Safe wrapper around {@link #resolveById(int)}.
     *
     * @param id players.id
     * @return optional profile
     */
    public Optional<Profile> optionalById(int id) {
        return Optional.ofNullable(resolveById(id));
    }

    /**
     * Checks whether a player with the given database id exists in {@code players}.
     *
     * @param id players.id
     * @return true if a row exists
     */
    public boolean exists(int id) {
        return storage.idExists(id);
    }

    // ------------------------------------------------------------
    // HYDRATION
    // ------------------------------------------------------------

    /**
     * Hydrates an offline profile by UUID.
     *
     * @param uuid Mojang UUID
     * @return hydrated {@link Profile}, or {@code null} if not found
     */
    private Profile loadOfflineByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        int id = storage.getIdByUuid(uuid);
        if (id <= 0) {
            return null;
        }

        String ign = storage.getIgn(id);
        String nick = storage.getNick(id);
        Group group = null; // to be resolved later via a GroupManager if desired

        return loadFullProfile(
                id,
                uuid,
                ign,
                nick,
                group,
                null // no player object – offline profile
        );
    }

    /**
     * Hydrates an offline profile by database id.
     *
     * @param id players.id
     * @return hydrated {@link Profile}, or {@code null} if not found
     */
    private Profile loadOfflineById(int id) {
        if (id <= 0) {
            return null;
        }

        UUID uuid = storage.getUuid(id);
        if (uuid == null) {
            return null;
        }

        String ign = storage.getIgn(id);
        String nick = storage.getNick(id);
        Group group = null; // to be resolved later via a GroupManager if desired

        return loadFullProfile(
                id,
                uuid,
                ign,
                nick,
                group,
                null
        );
    }

    /**
     * Builds a fully hydrated {@link Profile} instance from the basic identity
     * fields and player context.
     *
     * @param id     players.id
     * @param uuid   Mojang UUID
     * @param ign    in-game name
     * @param nick   optional nick from players.nick
     * @param group  current group / rank (may be null)
     * @param player online player instance, or null if offline
     * @return hydrated profile
     */
    private Profile loadFullProfile(
            int id,
            UUID uuid,
            String ign,
            String nick,
            Group group,
            Player player
    ) {
        List<Perm> flags = storage.loadFlags(id); // currently returns an empty list
        return new Profile(id, uuid, ign, nick, group, player, flags);
    }
    
    /**
     * Clears all cached online profiles.
     * Called when the plugin shuts down.
     */
    public void clear() {
        online.clear();
    }

}
