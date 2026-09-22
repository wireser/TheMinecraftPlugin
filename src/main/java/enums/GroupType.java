package enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import model.Group;

/**
 * Identifies every built-in permission group.
 *
 * <p>The enum is the stable identity used by Java code and the database.
 * Each enum constant owns exactly one canonical {@link Group} object containing
 * mutable runtime data such as registered commands and permission flags.</p>
 *
 * <p>Group IDs are persisted in {@code players.group_id}; therefore existing
 * IDs must never be reordered or reused after deployment.</p>
 */
public enum GroupType {

    PUNISHED(
        0,
        "Punished",
        "",
        "#555555",
        null
    ),

    VISITOR(
        1,
        "Visitor",
        "",
        "#AAAAAA",
        PUNISHED
    ),

    PLAYER(
        2,
        "Player",
        "",
        "#FFFFFF",
        VISITOR
    ),

    DONATOR(
        3,
        "Donator",
        "[DONATOR] ",
        "#55FFFF",
        PLAYER
    ),

    MODERATOR(
        4,
        "Moderator",
        "[MOD] ",
        "#55FF55",
        DONATOR
    ),

    SENIOR_MODERATOR(
        5,
        "Senior Moderator",
        "[SR MOD] ",
        "#00AA00",
        MODERATOR
    ),

    ADMIN(
        6,
        "Admin",
        "[ADMIN] ",
        "#FF5555",
        SENIOR_MODERATOR
    ),

    SENIOR_ADMIN(
        7,
        "Senior Admin",
        "[SR ADMIN] ",
        "#AA0000",
        ADMIN
    ),

    OWNER(
        8,
        "Owner",
        "[OWNER] ",
        "#FFAA00",
        SENIOR_ADMIN
    );

    /**
     * Fast lookup table used when hydrating profiles from players.group_id.
     */
    private static final Map<Integer, GroupType> GROUP_TYPES_BY_DATABASE_ID;

    static {
        Map<Integer, GroupType> groupsByDatabaseId = new HashMap<>();

        for (GroupType groupType : values()) {
            GroupType duplicate = groupsByDatabaseId.put(
                groupType.databaseId,
                groupType
            );

            if (duplicate != null) {
                throw new IllegalStateException(
                    "Duplicate group database ID "
                    + groupType.databaseId
                    + " used by "
                    + duplicate.name()
                    + " and "
                    + groupType.name()
                );
            }
        }

        GROUP_TYPES_BY_DATABASE_ID =
            Collections.unmodifiableMap(groupsByDatabaseId);
    }

    private final int databaseId;
    private final Group group;

    GroupType(
        int databaseId,
        String displayName,
        String prefix,
        String hexadecimalColor,
        GroupType parentType
    ) {
        Group parentGroup = parentType != null
            ? parentType.getGroup()
            : null;

        this.databaseId = databaseId;
        this.group = new Group(
            databaseId,
            displayName,
            prefix,
            hexadecimalColor,
            parentGroup
        );
    }

    /**
     * Returns the numeric value persisted in {@code players.group_id}.
     */
    public int getDatabaseId() {
        return databaseId;
    }

    /**
     * Returns the canonical runtime object for this group.
     *
     * <p>The same object is returned throughout the entire plugin session.</p>
     */
    public Group getGroup() {
        return group;
    }

    /**
     * Safely attempts to resolve a persisted database ID.
     *
     * <p>No fallback is applied. An unknown value represents corrupt or
     * unsupported persistent data and must be handled explicitly.</p>
     */
    public static Optional<GroupType> findByDatabaseId(int databaseId) {
        return Optional.ofNullable(
            GROUP_TYPES_BY_DATABASE_ID.get(databaseId)
        );
    }

    /**
     * Resolves a persisted database ID or fails immediately.
     *
     * @throws IllegalArgumentException when the database contains an unknown ID
     */
    public static GroupType requireByDatabaseId(int databaseId) {
        return findByDatabaseId(databaseId).orElseThrow(
            () -> new IllegalArgumentException(
                "Unknown player group database ID: " + databaseId
            )
        );
    }

    /**
     * Optional administrative lookup for commands accepting a group name.
     *
     * <p>Normal plugin code should use enum constants directly instead of
     * repeatedly resolving strings.</p>
     */
    public static Optional<GroupType> findByName(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return Optional.empty();
        }

        String normalizedName = groupName
            .trim()
            .toUpperCase(Locale.ROOT)
            .replace(' ', '_');

        try {
            return Optional.of(GroupType.valueOf(normalizedName));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}