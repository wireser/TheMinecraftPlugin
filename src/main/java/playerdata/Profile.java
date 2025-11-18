package playerdata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import enums.Currency;
import enums.Perm;
import main.Main;
import model.Group;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Represents a player within the plugin domain.
 * <p>
 * A {@code Profile} can exist for:
 * <ul>
 *   <li>an online player (with a non-null {@link Player})</li>
 *   <li>an offline player (no {@link Player}, only id/uuid loaded from DB)</li>
 * </ul>
 *
 * Responsibilities:
 * <ul>
 *   <li>Expose core identity (database id, UUID, IGN, nick/display name)</li>
 *   <li>Provide convenience accessors for vanilla state (world, item in hand, XP, gamemode)</li>
 *   <li>Delegate persistent operations (balances, locations, social lists, stats, settings, timers)
 *       to {@link ProfileStorage}</li>
 *   <li>Offer a clear API for higher-level code: e.g. {@code isTrustedBy(ownerProfile)},
 *       {@code hasTicker("fly")}, {@code getBalance(Currency.MONEY)}</li>
 * </ul>
 *
 * This class does not perform low-level SQL. All persistence concerns should be handled in
 * {@link ProfileStorage}.
 */
public final class Profile {

    // ======================================================================
    // Core identity
    // ======================================================================

    /**
     * Primary key from {@code players.id}.
     * <p>
     * A value of {@code 0} means the profile is not yet persisted/resolved.
     */
    private final int id;

    /**
     * Mojang UUID, corresponding to {@code players.uuid}.
     * <p>
     * May be {@code null} for profiles created by id only.
     */
    private final UUID uuid;

    /**
     * Last-known in-game name, corresponding to {@code players.name}.
     */
    private final String ign;

    /**
     * Stored nick/display name text from {@code players.nick}.
     * <p>
     * May be {@code null} if the player has no nick set.
     */
    private final String nick;

    /**
     * Current permission group / rank, resolved externally (e.g. via a GroupManager).
     * May be {@code null} if not yet assigned.
     */
    private Group group;

    /**
     * Online Bukkit player instance, or {@code null} if this is an offline profile.
     */
    private final Player player;

    /**
     * Additional internal flags for plugin-specific capabilities.
     */
    private final List<Perm> flags = new ArrayList<>();

    /**
     * Component representation of the IGN.
     */
    private final Component nameComponent;

    /**
     * Component representation of the display name, derived from {@link #nick} if present,
     * otherwise from {@link #ign}.
     */
    private final Component displayNameComponent;

    // ======================================================================
    // Construction
    // ======================================================================

    /**
     * Creates a profile for an online player.
     * <p>
     * This constructor is intended to be the main entry point when a player is online.
     * Higher-level code (e.g. a {@code ProfileManager}) is expected to populate database-id,
     * nick, group, and flags based on stored data and pass them in here or via an
     * alternative constructor if you prefer.
     *
     * @param player the online Bukkit player
     */
    public Profile(Player player) {
        this(
            /* id     */ 0,
            /* uuid   */ Objects.requireNonNull(player, "player").getUniqueId(),
            /* ign    */ player.getName(),
            /* nick   */ null,
            /* group  */ null,
            /* player */ player,
            /* flags  */ null
        );
    }

    /**
     * Creates a profile anchored by a database id.
     * <p>
     * This is suitable for offline operations where only {@code players.id} is known.
     * The caller is responsible for loading further details (IGN, nick, group) from
     * {@link ProfileStorage} and creating a richer instance if needed.
     *
     * @param id database primary key (players.id)
     */
    public Profile(int id) {
        this(id, null, null, null, null, null, null);
    }

    /**
     * Creates a profile anchored by a UUID.
     * <p>
     * This is suitable for offline operations where only the UUID is known.
     * The caller is responsible for loading further details (database id, IGN, nick, group)
     * from {@link ProfileStorage} if needed.
     *
     * @param uuid Mojang UUID
     */
    public Profile(UUID uuid) {
        this(0, Objects.requireNonNull(uuid, "uuid"), null, null, null, null, null);
    }

    /**
     * Internal full constructor used by other constructors and by higher-level code
     * (e.g. a ProfileManager) to create a fully hydrated profile instance.
     *
     * @param id          database id, or 0 if not yet persisted
     * @param uuid        Mojang UUID, may be null for id-only profiles
     * @param ign         in-game name, may be null for id-only profiles
     * @param nick        stored nick (players.nick), may be null
     * @param group       group / rank, may be null
     * @param player      online player instance, may be null for offline profiles
     * @param initialFlags initial flags, may be null
     */
    public Profile(int id,
                   UUID uuid,
                   String ign,
                   String nick,
                   Group group,
                   Player player,
                   List<Perm> initialFlags) {

        this.id = id;
        this.uuid = uuid;
        this.ign = ign;
        this.nick = nick;
        this.group = group;
        this.player = player;

        if (initialFlags != null && !initialFlags.isEmpty()) {
            this.flags.addAll(initialFlags);
        }

        // Components may be null if ign/nick are not yet known; callers should avoid
        // using these accessors until identity has been loaded.
        this.nameComponent = ign != null ? Component.text(ign) : Component.empty();
        this.displayNameComponent = (nick != null && !nick.isEmpty())
                ? Component.text(nick)
                : (ign != null ? Component.text(ign) : Component.empty());
    }

    // ======================================================================
    // Internal helpers
    // ======================================================================

    /**
     * Convenience accessor for the shared {@link ProfileStorage} instance.
     *
     * @return storage helper used for all persistent operations
     */
    private ProfileStorage storage() {
        return Main.getInstance().getProfileStorage();
    }

    // ======================================================================
    // Management tools – identity, group, flags
    // ======================================================================

    /**
     * @return database id from {@code players.id}, or {@code 0} if unknown.
     */
    public int getId() {
        return id;
    }

    /**
     * @return the Mojang UUID, or {@code null} if this profile was created by id only.
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * @return the Mojang UUID as a string, or {@code null} if there is no UUID.
     */
    public String getUuidString() {
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * @return the stored in-game name (IGN), or {@code null} if not yet loaded.
     */
    public String getIgn() {
        return ign;
    }

    /**
     * @return the raw nick string from {@code players.nick}, or {@code null} if none.
     */
    public String getNick() {
        return nick;
    }

    /**
     * @return effective display name as {@link Component}:
     *         nick if present, otherwise IGN, otherwise an empty component.
     */
    public Component getDisplayName() {
        return displayNameComponent;
    }

    /**
     * @return the name component representing the IGN, or empty if unknown.
     */
    public Component getNameComponent() {
        return nameComponent;
    }

    /**
     * @return the currently assigned group / rank, or {@code null} if not yet resolved.
     */
    public Group getGroup() {
        return group;
    }

    /**
     * Assigns a group / rank to this profile.
     *
     * @param group the group to assign, may be {@code null}
     */
    public void setGroup(Group group) {
        this.group = group;
    }

    /**
     * @return an unmodifiable view of the internal permission flags for this profile.
     */
    public List<Perm> getFlags() {
        return Collections.unmodifiableList(flags);
    }

    /**
     * Adds a permission flag to this profile if it is not already present.
     *
     * @param flag flag to add, ignored if {@code null}
     */
    public void addFlag(Perm flag) {
        if (flag == null) return;
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
    }

    /**
     * Removes a permission flag from this profile.
     *
     * @param flag flag to remove, ignored if {@code null}
     */
    public void removeFlag(Perm flag) {
        if (flag == null) return;
        flags.remove(flag);
    }

    /**
     * Clears all permission flags for this profile.
     */
    public void clearFlags() {
        flags.clear();
    }

    /**
     * Checks whether the profile has the given permission flag.
     *
     * @param flag flag to check
     * @return {@code true} if the flag is present, otherwise {@code false}
     */
    public boolean hasFlag(Perm flag) {
        return flag != null && flags.contains(flag);
    }

    // ======================================================================
    // Vanilla tools – player, world, gamemode, XP, items
    // ======================================================================

    /**
     * @return the online {@link Player} instance, or {@code null} if this profile is offline.
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the world of the player if online, otherwise {@code null}.
     */
    public World getWorld() {
        return player != null ? player.getWorld() : null;
    }

    /**
     * Checks whether the player is currently in the specified world.
     *
     * @param world target world
     * @return {@code true} if the player is online and in the given world
     */
    public boolean isInWorld(World world) {
        return player != null && world != null && player.getWorld().equals(world);
    }

    /**
     * Checks whether the player is currently in the specified world by name.
     *
     * @param worldName case-insensitive world name
     * @return {@code true} if the player is online and located in the named world
     */
    public boolean isInWorld(String worldName) {
        return player != null && worldName != null
                && player.getWorld().getName().equalsIgnoreCase(worldName);
    }

    /**
     * @return the current location of the player if online, otherwise {@code null}.
     */
    public Location getLocation() {
        return player != null ? player.getLocation() : null;
    }

    /**
     * @return the item currently held in the main hand if the player is online,
     *         otherwise {@code null}.
     */
    public ItemStack getItemInHand() {
        return player != null ? player.getInventory().getItemInMainHand() : null;
    }

    /**
     * @return the vanilla experience level of the player, or 0 if offline.
     */
    public int getVanillaLevel() {
        return player != null ? player.getLevel() : 0;
    }

    /**
     * @return the total vanilla experience of the player, or 0 if offline.
     */
    public int getVanillaXP() {
        return player != null ? player.getTotalExperience() : 0;
    }

    /**
     * @return {@code true} if the player is online and in SURVIVAL mode.
     */
    public boolean isInSurvival() {
        return player != null && player.getGameMode() == GameMode.SURVIVAL;
    }

    /**
     * @return {@code true} if the player is online and in CREATIVE mode.
     */
    public boolean isInCreative() {
        return player != null && player.getGameMode() == GameMode.CREATIVE;
    }

    /**
     * @return {@code true} if the player is online and in ADVENTURE mode.
     */
    public boolean isInAdventure() {
        return player != null && player.getGameMode() == GameMode.ADVENTURE;
    }

    /**
     * @return {@code true} if the player is online and in SPECTATOR mode.
     */
    public boolean isInSpectator() {
        return player != null && player.getGameMode() == GameMode.SPECTATOR;
    }

    /**
     * Returns the current IP address of the player.
     * <p>
     * This information is runtime-only and is not persisted to the database.
     *
     * @return IP address string, or {@code null} if the player is offline or the address is unavailable
     */
    public String getIp() {
        if (player == null || player.getAddress() == null) {
            return null;
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    /**
     * Checks permissions using Bukkit's permission system, OP status and optionally
     * the assigned group.
     *
     * @param node permission node to check
     * @return {@code true} if the player is online and has the permission,
     *         or if the group grants it; otherwise {@code false}
     */
    public boolean hasPermission(String node) {
        if (node == null || node.isEmpty()) {
            return false;
        }

        if (player != null) {
            if (player.isOp() || player.hasPermission(node)) {
                return true;
            }
        }

        // Optional: treat group command access as a permission mapping.
        if (group != null && group.hasCommand(node)) {
            return true;
        }

        return false;
    }

    // ======================================================================
    // Messaging helpers
    // ======================================================================

    /**
     * Sends a pre-built component message to the player if online.
     *
     * @param component message to send
     */
    public void sendMessage(Component component) {
        if (player == null || component == null) return;
        player.sendMessage(component);
    }

    /**
     * Sends a plain text message formatted with the specified color.
     *
     * @param color text color
     * @param text  raw message text
     */
    public void sendMessage(NamedTextColor color, String text) {
        if (text == null || text.isEmpty()) return;
        sendMessage(Component.text(text, color));
    }

    /**
     * Sends a formatted message constructed using {@link String#format(String, Object...)}.
     *
     * @param color    text color
     * @param template format template
     * @param args     format arguments
     */
    public void sendMessage(NamedTextColor color, String template, Object... args) {
        if (template == null || template.isEmpty()) return;
        String formatted = (args == null || args.length == 0)
                ? template
                : String.format(template, args);
        sendMessage(Component.text(formatted, color));
    }

    // Legacy-style aliases

    public void msg(NamedTextColor color, String template) {
        sendMessage(color, template);
    }

    public void msg(NamedTextColor color, String template, String arg) {
        sendMessage(color, template, arg);
    }

    public void msg(NamedTextColor color, String template, String arg1, String arg2) {
        sendMessage(color, template, arg1, arg2);
    }

    public void msg(NamedTextColor color, String template, double remaining) {
        sendMessage(color, template, remaining);
    }

    // ======================================================================
    // Special tools – backed by ProfileStorage
    // ======================================================================

    // ---------- Generic locations ----------

    /**
     * Retrieves a stored location for the given logical key from the database.
     * <p>
     * Some typical keys:
     * <ul>
     *   <li>{@code "home:main"}</li>
     *   <li>{@code "death"}</li>
     *   <li>{@code "back"}</li>
     * </ul>
     *
     * @param key logical location key
     * @return stored {@link Location}, or {@code null} if none is stored or an error occurs
     */
    public Location getStoredLocation(String key) {
        return storage().getLocation(this, key);
    }

    /**
     * Stores or updates a location under the given logical key.
     *
     * @param key      logical location key
     * @param location location to store, or {@code null} to remove
     */
    public void setStoredLocation(String key, Location location) {
        if (location == null) {
            storage().deleteLocation(this, key);
        } else {
            storage().setLocation(this, key, location);
        }
    }

    /**
     * Deletes a stored location for the given key.
     *
     * @param key logical location key
     */
    public void deleteStoredLocation(String key) {
        storage().deleteLocation(this, key);
    }

    // ---------- Balances (BigDecimal) ----------

    /**
     * Returns the current balance for the given currency.
     *
     * @param currency currency key
     * @return non-null {@link BigDecimal} balance (zero if not set or on error)
     */
    public BigDecimal getBalance(Currency currency) {
        return storage().getBalance(this, currency);
    }

    /**
     * Sets the balance for the given currency.
     *
     * @param currency currency key
     * @param amount   new balance value (non-null)
     */
    public void setBalance(Currency currency, BigDecimal amount) {
        storage().setBalance(this, currency, amount);
    }

    /**
     * Adds a delta to the balance for the given currency.
     *
     * @param currency currency key
     * @param delta    amount to add (may be negative)
     */
    public void addBalance(Currency currency, BigDecimal delta) {
        if (delta == null || BigDecimal.ZERO.compareTo(delta) == 0) return;
        storage().addBalance(this, currency, delta);
    }

    /**
     * Subtracts a delta from the balance for the given currency.
     *
     * @param currency currency key
     * @param delta    amount to subtract (ignored if negative or zero)
     */
    public void subBalance(Currency currency, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) <= 0) return;
        storage().addBalance(this, currency, delta.negate());
    }

    // ---------- Reply & welcome ----------

    /**
     * @return reply target player id ({@code players.id}) or {@code null} if none is set.
     */
    public Integer getReplyTargetId() {
        return storage().getReplyTargetId(this);
    }

    /**
     * Updates reply target player id in the database.
     *
     * @param targetId new reply target (may be {@code null} to clear)
     */
    public void setReplyTargetId(Integer targetId) {
        storage().setReplyTargetId(this, targetId);
    }

    /**
     * @return stored welcome message or {@code null} if none is set.
     */
    public String getWelcome() {
        return storage().getWelcome(this);
    }

    /**
     * Sets or clears the stored welcome message.
     *
     * @param welcome new welcome message, or {@code null} to clear
     */
    public void setWelcome(String welcome) {
        storage().setWelcome(this, welcome);
    }

    // ---------- Banned & timers ----------

    /**
     * Indicates whether this profile is banned according to the database.
     * <p>
     * Implementation is provided by {@link ProfileStorage} and may take into account
     * both hard bans and temporary bans implemented via timers.
     *
     * @return {@code true} if the player is currently banned
     */
    public boolean isBanned() {
        return storage().isBanned(this);
    }

    /**
     * Checks whether a logical "ticker" or timer (e.g. {@code "fly"}, {@code "tempban"})
     * is currently active for this profile.
     *
     * @param key timer key
     * @return {@code true} if the timer is active, otherwise {@code false}
     */
    public boolean hasTicker(String key) {
        return storage().hasActiveTimer(this, key);
    }

    // ---------- Social lists: friends / trust / ignore ----------

    /**
     * Adds another player to this profile's friend list.
     *
     * @param other target profile
     */
    public void addFriend(Profile other) {
        if (other == null) return;
        storage().addFriend(this, other.getId());
    }

    /**
     * Removes another player from this profile's friend list.
     *
     * @param other target profile
     */
    public void removeFriend(Profile other) {
        if (other == null) return;
        storage().removeFriend(this, other.getId());
    }

    /**
     * Checks whether this profile has the specified profile in its friend list.
     *
     * @param other target profile
     * @return {@code true} if this profile has {@code other} as a friend
     */
    public boolean isFriendWith(Profile other) {
        if (other == null) return false;
        return storage().isFriend(this.getId(), other.getId());
    }

    /**
     * Returns a list of friend ids ({@code players.id}) associated with this profile.
     *
     * @return unmodifiable list of friend ids (never {@code null})
     */
    public List<Integer> getFriendIds() {
        return storage().getFriendIds(this);
    }

    /**
     * Marks another profile as trusted by this profile (e.g. for land/claim access).
     *
     * @param other target profile
     */
    public void addTrustedPlayer(Profile other) {
        if (other == null) return;
        storage().addTrusted(this, other.getId());
    }

    /**
     * Removes trust for another profile.
     *
     * @param other target profile
     */
    public void removeTrustedPlayer(Profile other) {
        if (other == null) return;
        storage().removeTrusted(this, other.getId());
    }

    /**
     * Checks whether this profile trusts another profile.
     *
     * @param other target profile
     * @return {@code true} if this profile trusts {@code other}
     */
    public boolean isTrustingPlayer(Profile other) {
        if (other == null) return false;
        return storage().isTrusted(this.getId(), other.getId());
    }

    /**
     * Checks whether this profile is trusted by the specified owner profile.
     *
     * <p>Typical usage:
     * <pre>
     *     if (breaker.isTrustedBy(claimOwner)) { ... }
     * </pre>
     *
     * @param owner profile that might have granted trust
     * @return {@code true} if {@code owner} trusts this profile
     */
    public boolean isTrustedBy(Profile owner) {
        if (owner == null) return false;
        return storage().isTrusted(owner.getId(), this.getId());
    }

    /**
     * Returns a list of trusted player ids ({@code players.id}) for this profile.
     *
     * @return unmodifiable list of trusted ids
     */
    public List<Integer> getTrustedIds() {
        return storage().getTrustedIds(this);
    }

    /**
     * Adds another profile to this profile's ignore list (e.g. hide their chat).
     *
     * @param other target profile
     */
    public void addIgnoredPlayer(Profile other) {
        if (other == null) return;
        storage().addIgnored(this, other.getId());
    }

    /**
     * Removes another profile from this profile's ignore list.
     *
     * @param other target profile
     */
    public void removeIgnoredPlayer(Profile other) {
        if (other == null) return;
        storage().removeIgnored(this, other.getId());
    }

    /**
     * Checks whether this profile is ignoring another profile.
     *
     * @param other target profile
     * @return {@code true} if this profile ignores {@code other}
     */
    public boolean isIgnoringPlayer(Profile other) {
        if (other == null) return false;
        return storage().isIgnored(this.getId(), other.getId());
    }

    /**
     * Checks whether this profile is being ignored by another profile.
     *
     * @param other potential ignoring profile
     * @return {@code true} if {@code other} ignores this profile
     */
    public boolean isIgnoredByPlayer(Profile other) {
        if (other == null) return false;
        return storage().isIgnored(other.getId(), this.getId());
    }

    /**
     * Returns a list of ignored player ids ({@code players.id}) for this profile.
     *
     * @return unmodifiable list of ignored ids
     */
    public List<Integer> getIgnoredIds() {
        return storage().getIgnoredIds(this);
    }

    // ---------- Settings ----------

    /**
     * Retrieves a boolean setting for this profile.
     *
     * @param key          logical setting key
     * @param defaultValue value returned when no setting is stored
     * @return setting value or {@code defaultValue} if not present
     */
    public boolean getSetting(String key, boolean defaultValue) {
        return storage().getSetting(this, key, defaultValue);
    }

    /**
     * Updates or creates a boolean setting for this profile.
     *
     * @param key   logical setting key
     * @param value value to store
     */
    public void setSetting(String key, boolean value) {
        storage().setSetting(this, key, value);
    }

    /**
     * Toggles a boolean setting and returns the new value.
     *
     * @param key          logical setting key
     * @param defaultValue value assumed if no setting is present yet
     * @return new toggled value
     */
    public boolean toggleSetting(String key, boolean defaultValue) {
        boolean newValue = !getSetting(key, defaultValue);
        setSetting(key, newValue);
        return newValue;
    }

    // ---------- Stats (add/sub/set/get) ----------

    /**
     * Retrieves a numeric statistic from {@code players_stats}.
     * <p>
     * Note: {@code long} is used to match a BIGINT column and safely handle large counters.
     *
     * @param key logical stat key
     * @return stored value, or 0 if not present
     */
    public long getStat(String key) {
        return storage().getStat(this, key);
    }

    /**
     * Sets a numeric statistic to an exact value.
     *
     * @param key   logical stat key
     * @param value new stat value
     */
    public void setStat(String key, long value) {
        storage().setStat(this, key, value);
    }

    /**
     * Adds a delta to a numeric statistic.
     *
     * @param key   logical stat key
     * @param delta value to add (may be negative)
     */
    public void addStat(String key, long delta) {
        if (delta == 0L) return;
        storage().addStat(this, key, delta);
    }

    /**
     * Subtracts a delta from a numeric statistic.
     *
     * @param key   logical stat key
     * @param delta value to subtract (ignored if non-positive)
     */
    public void subStat(String key, long delta) {
        if (delta <= 0L) return;
        storage().addStat(this, key, -delta);
    }

}