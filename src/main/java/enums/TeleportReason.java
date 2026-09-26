package enums;

/**
 * Explains why the plugin is moving a player and whether the departure point
 * should become the player's {@code /back} location.
 *
 * <p>This is intentionally separate from Bukkit's teleport cause. Bukkit
 * describes the transport mechanism; this enum describes our gameplay intent.</p>
 */
public enum TeleportReason {

    /** A player deliberately used a command such as /home, /spawn or /warp. */
    PLAYER_REQUESTED(true),

    /** A staff member moved the player as a moderation or support action. */
    STAFF_FORCED(false),

    /** The jail system moved the player into confinement. */
    SYSTEM_JAIL_ENTRY(false),

    /** The jail system released the player from confinement. */
    SYSTEM_JAIL_RELEASE(false),

    /** The server recovered the player from an invalid or dangerous location. */
    SYSTEM_RECOVERY(false),

    /** A protection system removed the player from a forbidden region. */
    SYSTEM_REGION_EJECTION(false),

    /** Login restoration returned the player to the last authoritative location. */
    SYSTEM_LOGIN_RESTORE(false);

    private final boolean saveBackLocation;

    TeleportReason(boolean saveBackLocation) {
        this.saveBackLocation = saveBackLocation;
    }

    /**
     * Returns whether the player's departure point should replace their saved
     * {@code /back} location before this teleport occurs.
     *
     * @return {@code true} when the departure point should be saved
     */
    public boolean shouldSaveBackLocation() {
        return saveBackLocation;
    }
}
