package playerdata;

import java.util.ArrayList;
import java.util.Collections;
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
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;

import enums.FriendshipStatus;
import enums.GroupType;
import enums.Perm;
import model.Group;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import utils.TextComponentParser;
import utils.Validator;

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
 *     <li>reply target</li>
 *     <li>welcome message</li>
 *     <li>friends</li>
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
     * <p>Arbitrary stat keys are not known ahead of time. Therefore a stat is
     * loaded on first access and retained for the remainder of the session.</p>
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
        if (!onlineCacheEnabled) return;

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

        /* Ignore storage still uses its older schema and is not part of this change. */

        // Arbitrary stats are lazy-loaded; refreshing invalidates remembered values.
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
     * <p>Gameplay validation belongs to the module that owns nickname policy.
     * This method receives both already-prepared database representations,
     * writes them together and then updates the live cache.</p>
     *
     * @param formattedNickname display value including allowed colors, or null
     * @param plainNickname lowercase searchable value without colors, or null
     * @return {@code true} when persistence and the live cache were updated
     */
    public boolean setNick(String formattedNickname, String plainNickname) {
        if ((formattedNickname == null) != (plainNickname == null)) return false;
        if (formattedNickname != null && (formattedNickname.isBlank() || plainNickname.isBlank()))
            return false;

        if (!storage.setNick(this, formattedNickname, plainNickname)) return false;

        this.nick = formattedNickname;
        return true;
    }

    /**
     * Clears an online nickname after a joining account claims the same real
     * Minecraft username. Persistence has already been updated by storage.
     */
    void clearCachedNickname() {
        this.nick = null;
    }

    /**
     * @return visible nickname without colors, or {@code null} when unset
     */
    public String getPlainNick() {
        return Validator.stripNicknameColors(nick);
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
     * {@link #setNick(String, String)}.</p>
     *
     * @return nickname if present, otherwise Minecraft username
     */
    public Component getDisplayName() {
        if (nick != null && !nick.isBlank()) {
            return TextComponentParser.toComponent(nick);
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

    /**
     * Checks rank inheritance without relying on numeric group IDs.
     *
     * @param minimumGroup lowest group accepted by the operation
     * @return {@code true} when this profile belongs to that group or a child
     */
    public boolean meetsMinimumGroup(GroupType minimumGroup) {
        return group != null && minimumGroup != null
                && group.inheritsFrom(minimumGroup.getGroup());
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
     * Teleports this online player through the plugin's single teleport
     * gateway and stores the location they left as {@code back}.
     *
     * <p>Gameplay code should use this method instead of calling Bukkit's
     * teleport methods directly. Teleport restrictions, logging, visual
     * effects and other shared behaviour can then be added here once and
     * applied uniformly.</p>
     *
     * @param destination destination to which the player should be moved
     * @return {@code true} only when Bukkit completed the teleport
     */
    public boolean teleport(Location destination) {
        return teleport(destination, true);
    }

    /**
     * Teleports without replacing the player's {@code back} location.
     *
     * <p>This is an explicit exception for administrative or corrective
     * movement such as restoring the last logout location, releasing a player
     * from prison or forcing a player to spawn. Normal player travel should
     * use {@link #teleport(Location)}.</p>
     *
     * @param destination destination to which the player should be moved
     * @return {@code true} only when Bukkit completed the teleport
     */
    public boolean teleportWithoutSavingBack(Location destination) {
        return teleport(destination, false);
    }

    /** Performs the common validation and Bukkit teleport operation. */
    private boolean teleport(Location destination, boolean saveBackLocation) {
        if (!isOnline() || destination == null
                || !destination.isWorldLoaded() || !destination.isFinite()) return false;

        Location previousLocation = player.getLocation();
        boolean teleported = player.teleport(destination, TeleportCause.PLUGIN);

        if (teleported && saveBackLocation) setStoredLocation("back", previousLocation);
        return teleported;
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

        return group != null && group.canUseCommand(node);
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
     * Sends a component only when this profile currently represents an online
     * player. Commands resolving offline profiles can call this without adding
     * their own repeated online-state blocks.
     *
     * @param component component to send
     * @return {@code true} when an online player received the component
     */
    public boolean sendMessageIfOnline(Component component) {
        if (!isOnline() || component == null) return false;

        player.sendMessage(component);
        return true;
    }

    /**
     * Submits a generated message through Bukkit's real player-chat pipeline.
     *
     * <p>The component is converted to plain text before submission. The chat
     * event and its renderer remain responsible for the sender's nickname,
     * group prefix, color and the selected server chat style.</p>
     *
     * <p>Commands and multiline messages are rejected because this method is
     * exclusively for generated chat text.</p>
     *
     * @param component generated message body
     * @return {@code true} when the message was submitted to player chat
     */
    public boolean sendChatMessage(Component component) {
        if (!isOnline() || component == null) return false;

        String message = PlainTextComponentSerializer.plainText().serialize(component).strip();
        if (message.isEmpty() || message.startsWith("/")
                || message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) return false;

        player.chat(message);
        return true;
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
    // System-managed locations
    // ======================================================================

    /**
     * Loads a named system-managed location.
     *
     * <p>Locations remain database-backed because they are normally accessed
     * far less frequently than balances or relationship checks.</p>
     *
     * <p>Player-created homes are deliberately separate because they occupy
     * rows where {@code is_player_home = 1} and have different rules.</p>
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
     * Stores or removes a named system-managed location.
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
     * Removes a named system-managed location.
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
    // Reply target / welcome
    // ======================================================================

    /**
     * Returns the last player this profile can answer with {@code /reply}.
     *
     * @return target {@code players.id}, or {@code null} when unset
     */
    public Integer getReplyTargetId() {
        return onlineCacheEnabled ? replyTargetId : storage.getReplyTargetId(this);
    }

    /**
     * Changes and immediately persists the {@code /reply} target.
     *
     * @param targetId target {@code players.id}, or {@code null} to clear it
     * @return {@code true} when persistence and the live cache were updated
     */
    public boolean setReplyTargetId(Integer targetId) {
        if (targetId != null && (targetId <= 0 || targetId == id)) return false;
        if (!storage.setReplyTargetId(this, targetId)) return false;

        if (onlineCacheEnabled) this.replyTargetId = targetId;
        return true;
    }

    /**
     * Returns the player's welcome message.
     *
     * @return message or null
     */
    public String getWelcome() {
        return onlineCacheEnabled ? welcome : storage.getWelcome(this);
    }

    /**
     * Changes the player's welcome message.
     *
     * <p>Gameplay limits are checked by the owning module. This method only
     * normalizes line endings, persists the value and updates the live cache.</p>
     *
     * @param welcome new message or null
     * @return {@code true} when persistence and the live cache were updated
     */
    public boolean setWelcome(String welcome) {
        String normalizedWelcome = welcome == null || welcome.isBlank()
                ? null
                : Validator.normalizeWelcomeMessage(welcome);
        if (!storage.setWelcome(this, normalizedWelcome)) return false;

        if (onlineCacheEnabled) this.welcome = normalizedWelcome;
        return true;
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
     * Sends a friend request or accepts the other player's pending request.
     *
     * <p>Sending a request back to somebody who already requested friendship
     * is treated as acceptance. Repeating an outgoing request or requesting an
     * existing friend makes no database change and returns the current state.</p>
     *
     * @param other player receiving the request
     * @return relationship state after the operation
     */
    public FriendshipStatus sendFriendRequest(Profile other) {
        if (!isValidRelationTarget(other)) return FriendshipStatus.NONE;

        FriendshipStatus currentStatus = storage.getFriendshipStatus(this, other.getId());

        if (currentStatus == FriendshipStatus.INCOMING_REQUEST) {
            if (!storage.acceptFriendRequest(this, other.getId())) {
                return storage.getFriendshipStatus(this, other.getId());
            }

            cacheAcceptedFriendship(other);
            return FriendshipStatus.ACCEPTED;
        }

        if (currentStatus == FriendshipStatus.NONE) {
            return storage.createFriendRequest(this, other.getId())
                    ? FriendshipStatus.OUTGOING_REQUEST
                    : storage.getFriendshipStatus(this, other.getId());
        }

        return currentStatus;
    }

    /**
     * Accepts a pending request sent by the supplied player.
     *
     * @param requester player who sent the request
     * @return {@code true} only when a pending request was accepted
     */
    public boolean acceptFriendRequest(Profile requester) {
        if (!isValidRelationTarget(requester)) return false;
        if (storage.getFriendshipStatus(this, requester.getId())
                != FriendshipStatus.INCOMING_REQUEST) return false;
        if (!storage.acceptFriendRequest(this, requester.getId())) return false;

        cacheAcceptedFriendship(requester);
        return true;
    }

    /**
     * Deletes the shared relationship row.
     *
     * <p>This supports declining an incoming request, cancelling an outgoing
     * request and removing an accepted friend. The future command handler can
     * inspect {@link #getFriendshipStatus(Profile)} first to choose its message.</p>
     *
     * @param other other participant
     * @return {@code true} when an existing row was deleted
     */
    public boolean deleteFriendship(Profile other) {
        if (!isValidRelationTarget(other)) return false;
        if (!storage.deleteFriendship(this, other.getId())) return false;

        if (onlineCacheEnabled) friendIds.remove(other.getId());
        if (other.onlineCacheEnabled) other.friendIds.remove(id);
        return true;
    }

    /**
     * Returns the friendship state relative to this profile.
     *
     * @param other other participant
     * @return current relationship state
     */
    public FriendshipStatus getFriendshipStatus(Profile other) {
        if (!isValidRelationTarget(other)) return FriendshipStatus.NONE;
        if (onlineCacheEnabled && friendIds.contains(other.getId())) return FriendshipStatus.ACCEPTED;
        return storage.getFriendshipStatus(this, other.getId());
    }

    /**
     * Checks whether another player is on this profile's friend list.
     *
     * @param other target profile
     * @return true when present
     */
    public boolean isFriendWith(Profile other) {
        return isValidRelationTarget(other)
                && (onlineCacheEnabled
                        ? friendIds.contains(other.getId())
                        : storage.getFriendshipStatus(this, other.getId()) == FriendshipStatus.ACCEPTED);
    }

    /**
     * Returns all friend database ids.
     *
     * @return friend ids
     */
    public List<Integer> getFriendIds() {
        return onlineCacheEnabled ? List.copyOf(friendIds) : storage.getFriendIds(this);
    }

    /** @return IDs of players whose requests are waiting for this player */
    public List<Integer> getIncomingFriendRequestIds() {
        return storage.getIncomingFriendRequestIds(this);
    }

    /** @return IDs of players who have not answered this player's requests */
    public List<Integer> getOutgoingFriendRequestIds() {
        return storage.getOutgoingFriendRequestIds(this);
    }

    /** Updates both live profile caches after friendship acceptance. */
    private void cacheAcceptedFriendship(Profile other) {
        if (onlineCacheEnabled) friendIds.add(other.getId());
        if (other.onlineCacheEnabled) other.friendIds.add(id);
    }

    // ======================================================================
    // Trust
    // ======================================================================

    /**
     * Adds another profile to this player's trust list.
     *
     * @param other target profile
     * @return {@code true} when a new explicit trust row was stored
     */
    public boolean addTrustedPlayer(Profile other) {
        if (!isValidRelationTarget(other)) return false;

        int targetId = other.getId();

        if (onlineCacheEnabled && trustedIds.contains(targetId)) return false;
        if (!storage.addTrusted(this, targetId)) return false;

        if (onlineCacheEnabled) trustedIds.add(targetId);
        return true;
    }

    /**
     * Removes another profile from this player's trust list.
     *
     * @param other target profile
     * @return {@code true} when an explicit trust row was removed
     */
    public boolean removeTrustedPlayer(Profile other) {
        if (!isValidRelationTarget(other)) return false;

        int targetId = other.getId();
        if (!storage.removeTrusted(this, targetId)) return false;

        if (onlineCacheEnabled) trustedIds.remove(targetId);
        return true;
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
     * Checks effective build/container access granted through either explicit
     * one-way trust or an accepted friendship.
     */
    public boolean allowsTrustedAccess(Profile other) {
        return isValidRelationTarget(other)
                && (isTrustingPlayer(other) || isFriendWith(other));
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
