package playerdata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import enums.Currency;
import enums.Perm;
import model.Group;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Represents a player inside the plugin domain.
 *
 * <p>A {@code Profile} can represent either:</p>
 *
 * <ul>
 *     <li>
 *         an <strong>online session profile</strong>, containing a Bukkit
 *         {@link Player} and cached persistent values, or
 *     </li>
 *     <li>
 *         a <strong>temporary offline profile</strong>, containing persistent
 *         identity but no Bukkit {@link Player}.
 *     </li>
 * </ul>
 *
 * <p>Online profiles are created and owned by {@link ProfileManager}. There
 * should be exactly one live {@code Profile} instance for each online UUID.</p>
 *
 * <p>Offline profiles are temporary. Code should not place offline
 * {@code Profile} objects into long-lived maps or fields. Store the UUID or
 * database id instead and resolve the profile again when required.</p>
 *
 * <h2>Caching policy</h2>
 *
 * <p>The following values are cached for online profiles because they are
 * expected to be read frequently:</p>
 *
 * <ul>
 *     <li>nickname</li>
 *     <li>permission flags</li>
 *     <li>balances</li>
 *     <li>reply target</li>
 *     <li>welcome message</li>
 *     <li>friends</li>
 *     <li>trusted players</li>
 *     <li>ignored players</li>
 *     <li>statistics after their first access</li>
 * </ul>
 *
 * <p>Stored locations, timers, bans and arbitrary boolean settings remain
 * database-backed for now. Those values either change infrequently, have
 * time-sensitive semantics, or require additional storage APIs before caching
 * them safely.</p>
 */
public final class Profile {

    // ======================================================================
    // Persistence
    // ======================================================================

    /**
     * Storage instance used by this profile.
     *
     * <p>The storage dependency is injected by {@link ProfileManager}. This
     * deliberately avoids reaching through {@code Main.getInstance()} from
     * inside the domain object.</p>
     */
    private final ProfileStorage storage;

    // ======================================================================
    // Identity
    // ======================================================================

    /**
     * Primary key from {@code players.id}.
     */
    private final int id;

    /**
     * Mojang UUID.
     */
    private final UUID uuid;

    /**
     * Current/last-known Minecraft username.
     */
    private final String ign;

    /**
     * Plugin-defined nickname.
     *
     * <p>Mutable because a nickname may change during an online session.</p>
     */
    private String nick;

    /**
     * Current group/rank.
     */
    private Group group;

    /**
     * Bukkit player while this profile represents an online session.
     *
     * <p>{@code null} for temporary offline profiles.</p>
     */
    private final Player player;

    /**
     * True when this object is the session profile of an online player and
     * therefore owns persistent-value caches.
     *
     * <p>This represents the kind of profile that was created, rather than
     * repeatedly calling {@link Player#isOnline()}.</p>
     */
    private final boolean onlineCacheEnabled;

    // ======================================================================
    // Permission cache
    // ======================================================================

    /**
     * Plugin-specific permission/capability flags.
     */
    private final List<Perm> flags = new ArrayList<>();

    // ======================================================================
    // Persistent online caches
    // ======================================================================

    /**
     * Cached balances keyed by currency.
     *
     * <p>Fully populated when an online profile is created.</p>
     */
    private final EnumMap<Currency, BigDecimal> balances =
            new EnumMap<>(Currency.class);

    /**
     * Cached reply target for online profiles.
     */
    private Integer replyTargetId;

    /**
     * Cached welcome message for online profiles.
     */
    private String welcome;

    /**
     * Cached friend ids for online profiles.
     */
    private final Set<Integer> friendIds = new HashSet<>();

    /**
     * Cached trust targets for online profiles.
     */
    private final Set<Integer> trustedIds = new HashSet<>();

    /**
     * Cached ignore targets for online profiles.
     */
    private final Set<Integer> ignoredIds = new HashSet<>();

    /**
     * Lazy stat cache.
     *
     * <p>Unlike balances, arbitrary stat keys are not known ahead of time.
     * Therefore a stat is loaded on first access and then retained for the
     * remainder of the online session.</p>
     */
    private final Map<String, Long> statCache = new HashMap<>();

    // ======================================================================
    // Construction
    // ======================================================================

    /**
     * Creates a fully identified profile.
     *
     * <p>Profiles should normally be created by {@link ProfileManager}, rather
     * than directly by command/event code.</p>
     *
     * @param storage persistent storage implementation
     * @param id database player id
     * @param uuid Mojang UUID
     * @param ign current/last-known username
     * @param nick stored nickname, may be {@code null}
     * @param group current group, may be {@code null}
     * @param player Bukkit player for online profiles, otherwise {@code null}
     * @param initialFlags initial plugin flags, may be {@code null}
     */
    Profile(
            ProfileStorage storage,
            int id,
            UUID uuid,
            String ign,
            String nick,
            Group group,
            Player player,
            List<Perm> initialFlags
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");

        this.id = id;
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.ign = ign;
        this.nick = nick;
        this.group = group;
        this.player = player;

        this.onlineCacheEnabled = player != null;

        if (initialFlags != null && !initialFlags.isEmpty()) {
            this.flags.addAll(initialFlags);
        }
    }

    // ======================================================================
    // Cache lifecycle
    // ======================================================================

    /**
     * Loads all persistent values that should remain cached while this player
     * is online.
     *
     * <p>This method is package-private because {@link ProfileManager} owns the
     * profile lifecycle and should decide when the initial cache is populated.</p>
     *
     * <p>Calling this method for a temporary offline profile does nothing.</p>
     */
    void loadOnlineCache() {
        if (!onlineCacheEnabled) {
            return;
        }

        /*
         * Balances are ideal cache candidates:
         * small fixed key set and potentially very frequent reads.
         */
        balances.clear();

        for (Currency currency : Currency.values()) {
            balances.put(
                    currency,
                    storage.getBalance(this, currency)
            );
        }

        /*
         * Tiny player-table values that may be used repeatedly by commands.
         */
        replyTargetId = storage.getReplyTargetId(this);
        welcome = storage.getWelcome(this);

        /*
         * Relationship lists are particularly important to cache because they
         * may eventually be checked inside high-frequency Bukkit events such as
         * block interaction or chat.
         */
        friendIds.clear();
        friendIds.addAll(storage.getFriendIds(this));

        trustedIds.clear();
        trustedIds.addAll(storage.getTrustedIds(this));

        ignoredIds.clear();
        ignoredIds.addAll(storage.getIgnoredIds(this));

        /*
         * Arbitrary stats are lazy-loaded, so refreshing invalidates anything
         * previously remembered.
         */
        statCache.clear();
    }

    /**
     * Reloads this online profile's cached persistent data from the database.
     *
     * <p>This should normally not be necessary because all plugin mutations
     * should pass through {@code Profile} methods, which update the database
     * and cache together.</p>
     *
     * <p>It is useful when something outside the normal profile API has changed
     * persistent data and the online cache must be resynchronized.</p>
     */
    public void refreshCache() {
        if (!onlineCacheEnabled) {
            return;
        }

        this.nick = storage.getNick(id);

        this.flags.clear();
        this.flags.addAll(storage.loadFlags(id));

        loadOnlineCache();
    }

    /**
     * @return {@code true} when this object owns an online-session cache
     */
    public boolean isCachedOnlineProfile() {
        return onlineCacheEnabled;
    }

    // ======================================================================
    // Identity
    // ======================================================================

    /**
     * @return primary key from {@code players.id}
     */
    public int getId() {
        return id;
    }

    /**
     * @return Mojang UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * @return UUID as a string
     */
    public String getUuidString() {
        return uuid.toString();
    }

    /**
     * @return current/last-known Minecraft username
     */
    public String getIgn() {
        return ign;
    }

    /**
     * Returns the plugin nickname.
     *
     * <p>Nickname is held directly by the profile and therefore does not cause
     * a database lookup.</p>
     *
     * @return nickname, or {@code null} if none exists
     */
    public String getNick() {
        return nick;
    }

    /**
     * Changes the player's nickname.
     *
     * <p>The database is updated first, then this profile's local representation
     * is changed. The storage method required by this function is included
     * below this class.</p>
     *
     * @param nick new nickname, or {@code null}/blank to remove it
     */
    public void setNick(String nick) {
        String normalized =
                nick == null || nick.isBlank()
                        ? null
                        : nick;

        storage.setNick(this, normalized);
        this.nick = normalized;
    }

    /**
     * Returns the Minecraft username as an Adventure component.
     *
     * @return username component, or an empty component if unavailable
     */
    public Component getNameComponent() {
        return ign != null
                ? Component.text(ign)
                : Component.empty();
    }

    /**
     * Returns the effective display name.
     *
     * <p>The nickname is evaluated when this method is called rather than
     * storing a second immutable component that could become stale after
     * {@link #setNick(String)}.</p>
     *
     * @return nickname if present, otherwise Minecraft username
     */
    public Component getDisplayName() {
        if (nick != null && !nick.isBlank()) {
            return Component.text(nick);
        }

        return getNameComponent();
    }

    /**
     * @return current group/rank, or {@code null} if unresolved
     */
    public Group getGroup() {
        return group;
    }

    /**
     * Updates the runtime group reference.
     *
     * @param group new group
     */
    public void setGroup(Group group) {
        this.group = group;
    }

    // ======================================================================
    // Flags
    // ======================================================================

    /**
     * @return read-only view of plugin permission flags
     */
    public List<Perm> getFlags() {
        return Collections.unmodifiableList(flags);
    }

    /**
     * Adds a permission flag if absent.
     *
     * @param flag flag to add
     */
    public void addFlag(Perm flag) {
        if (flag != null && !flags.contains(flag)) {
            flags.add(flag);
        }
    }

    /**
     * Removes a permission flag.
     *
     * @param flag flag to remove
     */
    public void removeFlag(Perm flag) {
        if (flag != null) {
            flags.remove(flag);
        }
    }

    /**
     * Removes every plugin permission flag.
     */
    public void clearFlags() {
        flags.clear();
    }

    /**
     * Checks whether this profile has a plugin-specific flag.
     *
     * @param flag flag to check
     * @return true when present
     */
    public boolean hasFlag(Perm flag) {
        return flag != null && flags.contains(flag);
    }

    // ======================================================================
    // Bukkit / online state
    // ======================================================================

    /**
     * @return Bukkit player, or {@code null} for offline profiles
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Checks whether the Bukkit player represented by this profile is currently
     * online.
     *
     * @return true if a live online Bukkit player is available
     */
    public boolean isOnline() {
        return player != null && player.isOnline();
    }

    /**
     * @return player's world, or null if offline
     */
    public World getWorld() {
        return player != null
                ? player.getWorld()
                : null;
    }

    /**
     * Checks whether the player is currently in a specific world.
     *
     * @param world target world
     * @return true when online and inside that world
     */
    public boolean isInWorld(World world) {
        return player != null
                && world != null
                && player.getWorld().equals(world);
    }

    /**
     * Checks whether the player is currently in a world by name.
     *
     * @param worldName target world name
     * @return true when names match case-insensitively
     */
    public boolean isInWorld(String worldName) {
        return player != null
                && worldName != null
                && player.getWorld()
                         .getName()
                         .equalsIgnoreCase(worldName);
    }

    /**
     * @return current Bukkit location, or null if offline
     */
    public Location getLocation() {
        return player != null
                ? player.getLocation()
                : null;
    }

    /**
     * @return main-hand item, or null if offline
     */
    public ItemStack getItemInHand() {
        return player != null
                ? player.getInventory().getItemInMainHand()
                : null;
    }

    /**
     * @return vanilla level, or 0 if offline
     */
    public int getVanillaLevel() {
        return player != null
                ? player.getLevel()
                : 0;
    }

    /**
     * @return total vanilla experience, or 0 if offline
     */
    public int getVanillaXP() {
        return player != null
                ? player.getTotalExperience()
                : 0;
    }

    /**
     * @return true when online in survival mode
     */
    public boolean isInSurvival() {
        return player != null
                && player.getGameMode() == GameMode.SURVIVAL;
    }

    /**
     * @return true when online in creative mode
     */
    public boolean isInCreative() {
        return player != null
                && player.getGameMode() == GameMode.CREATIVE;
    }

    /**
     * @return true when online in adventure mode
     */
    public boolean isInAdventure() {
        return player != null
                && player.getGameMode() == GameMode.ADVENTURE;
    }

    /**
     * @return true when online in spectator mode
     */
    public boolean isInSpectator() {
        return player != null
                && player.getGameMode() == GameMode.SPECTATOR;
    }

    /**
     * Returns the player's current network address.
     *
     * @return IP address string, or null when unavailable/offline
     */
    public String getIp() {
        if (player == null || player.getAddress() == null) {
            return null;
        }

        return player.getAddress()
                     .getAddress()
                     .getHostAddress();
    }

    /**
     * Checks command/permission access.
     *
     * @param node Bukkit permission node or group command key
     * @return true when granted
     */
    public boolean hasPermission(String node) {
        if (node == null || node.isBlank()) {
            return false;
        }

        if (player != null
                && (player.isOp() || player.hasPermission(node))) {
            return true;
        }

        return group != null && group.hasCommand(node);
    }

    // ======================================================================
    // Messaging
    // ======================================================================

    /**
     * Sends an Adventure component when the player is online.
     *
     * @param component component to send
     */
    public void sendMessage(Component component) {
        if (player == null || component == null) {
            return;
        }

        player.sendMessage(component);
    }

    /**
     * Sends plain text using the requested Adventure colour.
     *
     * @param color text colour
     * @param text message
     */
    public void sendMessage(NamedTextColor color, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        sendMessage(Component.text(text, color));
    }

    /**
     * Sends formatted text using {@link String#format(String, Object...)}.
     *
     * @param color text colour
     * @param template format template
     * @param args format arguments
     */
    public void sendMessage(
            NamedTextColor color,
            String template,
            Object... args
    ) {
        if (template == null || template.isEmpty()) {
            return;
        }

        String formatted =
                args == null || args.length == 0
                        ? template
                        : String.format(template, args);

        sendMessage(Component.text(formatted, color));
    }

    /*
     * Legacy convenience aliases retained because the short form is useful
     * throughout command code.
     */

    public void msg(NamedTextColor color, String template) {
        sendMessage(color, template);
    }

    public void msg(
            NamedTextColor color,
            String template,
            String arg
    ) {
        sendMessage(color, template, arg);
    }

    public void msg(
            NamedTextColor color,
            String template,
            String arg1,
            String arg2
    ) {
        sendMessage(color, template, arg1, arg2);
    }

    public void msg(
            NamedTextColor color,
            String template,
            double remaining
    ) {
        sendMessage(color, template, remaining);
    }

    // ======================================================================
    // Stored locations
    // ======================================================================

    /**
     * Loads a named persistent location.
     *
     * <p>Locations remain database-backed because they are normally accessed
     * far less frequently than balances or relationship checks.</p>
     *
     * @param key logical location key
     * @return stored location or null
     */
    public Location getStoredLocation(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        return storage.getLocation(this, key);
    }

    /**
     * Stores or removes a named location.
     *
     * @param key logical location key
     * @param location location, or null to delete
     */
    public void setStoredLocation(
            String key,
            Location location
    ) {
        if (key == null || key.isBlank()) {
            return;
        }

        if (location == null) {
            storage.deleteLocation(this, key);
        } else {
            storage.setLocation(this, key, location);
        }
    }

    /**
     * Removes a named persistent location.
     *
     * @param key logical location key
     */
    public void deleteStoredLocation(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        storage.deleteLocation(this, key);
    }

    // ======================================================================
    // Balances
    // ======================================================================

    /**
     * Returns a currency balance.
     *
     * <p>Online profiles read exclusively from the preloaded balance cache.
     * Offline profiles query persistent storage directly.</p>
     *
     * @param currency currency
     * @return balance, never null
     */
    public BigDecimal getBalance(Currency currency) {
        if (currency == null) {
            return BigDecimal.ZERO;
        }

        if (onlineCacheEnabled) {
            return balances.getOrDefault(
                    currency,
                    BigDecimal.ZERO
            );
        }

        return storage.getBalance(this, currency);
    }

    /**
     * Sets a currency balance.
     *
     * <p>Persistence is performed first. The online cache is then updated so
     * subsequent reads require no database query.</p>
     *
     * @param currency currency
     * @param amount new balance
     */
    public void setBalance(
            Currency currency,
            BigDecimal amount
    ) {
        if (currency == null || amount == null) {
            return;
        }

        storage.setBalance(this, currency, amount);

        if (onlineCacheEnabled) {
            balances.put(currency, amount);
        }
    }

    /**
     * Adds a delta to a balance.
     *
     * @param currency currency
     * @param delta amount to add; may be negative
     */
    public void addBalance(
            Currency currency,
            BigDecimal delta
    ) {
        if (currency == null
                || delta == null
                || delta.signum() == 0) {
            return;
        }

        storage.addBalance(this, currency, delta);

        if (onlineCacheEnabled) {
            balances.merge(
                    currency,
                    delta,
                    BigDecimal::add
            );
        }
    }

    /**
     * Subtracts a positive amount from a balance.
     *
     * @param currency currency
     * @param delta amount to subtract
     */
    public void subBalance(
            Currency currency,
            BigDecimal delta
    ) {
        if (currency == null
                || delta == null
                || delta.signum() <= 0) {
            return;
        }

        addBalance(currency, delta.negate());
    }

    // ======================================================================
    // Reply target / welcome
    // ======================================================================

    /**
     * Returns the stored reply target.
     *
     * @return database player id or null
     */
    public Integer getReplyTargetId() {
        if (onlineCacheEnabled) {
            return replyTargetId;
        }

        return storage.getReplyTargetId(this);
    }

    /**
     * Changes the reply target.
     *
     * @param targetId target database player id, or null to clear
     */
    public void setReplyTargetId(Integer targetId) {
        storage.setReplyTargetId(this, targetId);

        if (onlineCacheEnabled) {
            this.replyTargetId = targetId;
        }
    }

    /**
     * Returns the player's welcome message.
     *
     * @return message or null
     */
    public String getWelcome() {
        if (onlineCacheEnabled) {
            return welcome;
        }

        return storage.getWelcome(this);
    }

    /**
     * Changes the player's welcome message.
     *
     * @param welcome new message or null
     */
    public void setWelcome(String welcome) {
        storage.setWelcome(this, welcome);

        if (onlineCacheEnabled) {
            this.welcome = welcome;
        }
    }

    // ======================================================================
    // Ban / timers
    // ======================================================================

    /**
     * Checks current ban state.
     *
     * <p>Ban state remains storage-backed because temporary bans contain
     * time-sensitive expiration information.</p>
     *
     * @return true when currently banned
     */
    public boolean isBanned() {
        return storage.isBanned(this);
    }

    /**
     * Checks a current timer/ticker.
     *
     * @param key timer key
     * @return true while active
     */
    public boolean hasTicker(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        return storage.hasActiveTimer(this, key);
    }

    // ======================================================================
    // Friends
    // ======================================================================

    /**
     * Adds another player to this profile's friend list.
     *
     * @param other target profile
     */
    public void addFriend(Profile other) {
        if (!isValidRelationTarget(other)) {
            return;
        }

        int targetId = other.getId();

        if (onlineCacheEnabled && friendIds.contains(targetId)) {
            return;
        }

        storage.addFriend(this, targetId);

        if (onlineCacheEnabled) {
            friendIds.add(targetId);
        }
    }

    /**
     * Removes another player from the friend list.
     *
     * @param other target profile
     */
    public void removeFriend(Profile other) {
        if (!isValidRelationTarget(other)) {
            return;
        }

        int targetId = other.getId();

        storage.removeFriend(this, targetId);

        if (onlineCacheEnabled) {
            friendIds.remove(targetId);
        }
    }

    /**
     * Checks whether another player is on this profile's friend list.
     *
     * @param other target profile
     * @return true when present
     */
    public boolean isFriendWith(Profile other) {
        if (!isValidRelationTarget(other)) {
            return false;
        }

        if (onlineCacheEnabled) {
            return friendIds.contains(other.getId());
        }

        return storage.isFriend(
                this.id,
                other.getId()
        );
    }

    /**
     * Returns all friend database ids.
     *
     * @return friend ids
     */
    public List<Integer> getFriendIds() {
        if (onlineCacheEnabled) {
            return List.copyOf(friendIds);
        }

        return storage.getFriendIds(this);
    }

    // ======================================================================
    // Trust
    // ======================================================================

    /**
     * Adds another profile to this player's trust list.
     *
     * @param other target profile
     */
    public void addTrustedPlayer(Profile other) {
        if (!isValidRelationTarget(other)) {
            return;
        }

        int targetId = other.getId();

        if (onlineCacheEnabled && trustedIds.contains(targetId)) {
            return;
        }

        storage.addTrusted(this, targetId);

        if (onlineCacheEnabled) {
            trustedIds.add(targetId);
        }
    }

    /**
     * Removes another profile from this player's trust list.
     *
     * @param other target profile
     */
    public void removeTrustedPlayer(Profile other) {
        if (!isValidRelationTarget(other)) {
            return;
        }

        int targetId = other.getId();

        storage.removeTrusted(this, targetId);

        if (onlineCacheEnabled) {
            trustedIds.remove(targetId);
        }
    }

    /**
     * Checks whether this profile trusts another profile.
     *
     * @param other target profile
     * @return true when trusted
     */
    public boolean isTrustingPlayer(Profile other) {
        if (!isValidRelationTarget(other)) {
            return false;
        }

        if (onlineCacheEnabled) {
            return trustedIds.contains(other.getId());
        }

        return storage.isTrusted(
                this.id,
                other.getId()
        );
    }

    /**
     * Checks whether another profile trusts this profile.
     *
     * <p>Delegating to the owner is intentional. If the owner is online, their
     * trust cache is used. If the owner is offline, their database-backed
     * profile performs the lookup.</p>
     *
     * @param owner potential trust owner
     * @return true when owner trusts this profile
     */
    public boolean isTrustedBy(Profile owner) {
        return owner != null
                && owner.isTrustingPlayer(this);
    }

    /**
     * Returns all ids trusted by this profile.
     *
     * @return trusted player ids
     */
    public List<Integer> getTrustedIds() {
        if (onlineCacheEnabled) {
            return List.copyOf(trustedIds);
        }

        return storage.getTrustedIds(this);
    }

    // ======================================================================
    // Ignore
    // ======================================================================

    /**
     * Adds another player to the ignore list.
     *
     * @param other target profile
     */
    public void addIgnoredPlayer(Profile other) {
        if (!isValidRelationTarget(other)) {
            return;
        }

        int targetId = other.getId();

        if (onlineCacheEnabled && ignoredIds.contains(targetId)) {
            return;
        }

        storage.addIgnored(this, targetId);

        if (onlineCacheEnabled) {
            ignoredIds.add(targetId);
        }
    }

    /**
     * Removes another player from the ignore list.
     *
     * @param other target profile
     */
    public void removeIgnoredPlayer(Profile other) {
        if (!isValidRelationTarget(other)) {
            return;
        }

        int targetId = other.getId();

        storage.removeIgnored(this, targetId);

        if (onlineCacheEnabled) {
            ignoredIds.remove(targetId);
        }
    }

    /**
     * Checks whether this profile ignores another profile.
     *
     * @param other target profile
     * @return true when ignored
     */
    public boolean isIgnoringPlayer(Profile other) {
        if (!isValidRelationTarget(other)) {
            return false;
        }

        if (onlineCacheEnabled) {
            return ignoredIds.contains(other.getId());
        }

        return storage.isIgnored(
                this.id,
                other.getId()
        );
    }

    /**
     * Checks whether another player ignores this profile.
     *
     * @param other potential ignoring player
     * @return true when ignored by the other profile
     */
    public boolean isIgnoredByPlayer(Profile other) {
        return other != null
                && other.isIgnoringPlayer(this);
    }

    /**
     * Returns all ignored database ids.
     *
     * @return ignored player ids
     */
    public List<Integer> getIgnoredIds() {
        if (onlineCacheEnabled) {
            return List.copyOf(ignoredIds);
        }

        return storage.getIgnoredIds(this);
    }

    /**
     * Checks whether another profile is valid for a persistent player-to-player
     * relationship.
     *
     * @param other other profile
     * @return true when both profiles have valid distinct database ids
     */
    private boolean isValidRelationTarget(Profile other) {
        return other != null
                && other.getId() > 0
                && other.getId() != this.id;
    }

    // ======================================================================
    // Settings
    // ======================================================================

    /**
     * Retrieves a boolean setting.
     *
     * <p>Settings deliberately remain database-backed for now. The current
     * storage API accepts a caller-supplied default value and does not expose
     * whether a row was actually absent. Blindly caching that returned value
     * could therefore make the first supplied default become permanently
     * cached for the session.</p>
     *
     * @param key setting key
     * @param defaultValue value when no row exists
     * @return stored/default value
     */
    public boolean getSetting(
            String key,
            boolean defaultValue
    ) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }

        return storage.getSetting(
                this,
                key,
                defaultValue
        );
    }

    /**
     * Stores a boolean setting.
     *
     * @param key setting key
     * @param value new value
     */
    public void setSetting(
            String key,
            boolean value
    ) {
        if (key == null || key.isBlank()) {
            return;
        }

        storage.setSetting(this, key, value);
    }

    /**
     * Toggles a boolean setting.
     *
     * @param key setting key
     * @param defaultValue value when no row currently exists
     * @return new value
     */
    public boolean toggleSetting(
            String key,
            boolean defaultValue
    ) {
        boolean value =
                !getSetting(key, defaultValue);

        setSetting(key, value);

        return value;
    }

    // ======================================================================
    // Statistics
    // ======================================================================

    /**
     * Returns a numeric statistic.
     *
     * <p>For online profiles the first access queries storage and subsequent
     * accesses are served from the local stat cache.</p>
     *
     * <p>Offline profiles query storage directly because they are temporary
     * objects and normally do not live long enough for caching to matter.</p>
     *
     * @param key statistic key
     * @return current value
     */
    public long getStat(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }

        if (!onlineCacheEnabled) {
            return storage.getStat(this, key);
        }

        Long cached = statCache.get(key);

        if (cached != null) {
            return cached;
        }

        long value = storage.getStat(this, key);

        statCache.put(key, value);

        return value;
    }

    /**
     * Sets a statistic.
     *
     * @param key statistic key
     * @param value exact value
     */
    public void setStat(
            String key,
            long value
    ) {
        if (key == null || key.isBlank()) {
            return;
        }

        storage.setStat(this, key, value);

        if (onlineCacheEnabled) {
            statCache.put(key, value);
        }
    }

    /**
     * Adds a delta to a statistic.
     *
     * @param key statistic key
     * @param delta amount to add
     */
    public void addStat(
            String key,
            long delta
    ) {
        if (key == null
                || key.isBlank()
                || delta == 0L) {
            return;
        }

        storage.addStat(this, key, delta);

        if (onlineCacheEnabled) {
            /*
             * If the stat was already cached, update it locally.
             *
             * If it has never been read, leave it absent. The first future
             * getStat() will load the already-updated database value.
             */
            if (statCache.containsKey(key)) {
                statCache.merge(
                        key,
                        delta,
                        Long::sum
                );
            }
        }
    }

    /**
     * Subtracts a positive delta from a statistic.
     *
     * @param key statistic key
     * @param delta amount to subtract
     */
    public void subStat(
            String key,
            long delta
    ) {
        if (delta <= 0L) {
            return;
        }

        addStat(key, -delta);
    }
}