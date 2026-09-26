package model;

import enums.Perm;
import net.kyori.adventure.text.format.TextColor;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime definition of a permission group.
 *
 * <p>A group stores only commands and flags assigned directly to it.
 * Inherited values are resolved dynamically through {@link #parent}, ensuring
 * commands registered later are immediately visible to all higher groups.</p>
 */
public final class Group {

    private final int databaseId;
    private final String displayName;
    private final Group parent;

    private String prefix;
    private TextColor nameColor;

    /**
     * Maps command labels and aliases to their canonical label.
     *
     * <p>Example:</p>
     * <pre>
     * spawn -> spawn
     * hub   -> spawn
     * </pre>
     */
    private final Map<String, String> directlyAssignedCommands =
        new LinkedHashMap<>();

    /**
     * Special capabilities assigned directly to this group.
     */
    private final Set<Perm> directlyAssignedFlags =
        EnumSet.noneOf(Perm.class);

    public Group(
        int databaseId,
        String displayName,
        String prefix,
        String hexadecimalNameColor,
        Group parent
    ) {
        if (databaseId < 0 || databaseId > 255) {
            throw new IllegalArgumentException(
                "Group database ID must fit inside TINYINT UNSIGNED: "
                + databaseId
            );
        }

        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException(
                "Group display name cannot be blank."
            );
        }

        this.databaseId = databaseId;
        this.displayName = displayName.trim();
        this.prefix = prefix != null ? prefix : "";
        this.nameColor = requireValidColor(hexadecimalNameColor);
        this.parent = parent;
    }

    public int getDatabaseId() {
        return databaseId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Group getParent() {
        return parent;
    }

    /**
     * Checks whether this group is the required group or inherits from it.
     *
     * <p>The inheritance chain is authoritative. Numeric database IDs are not
     * treated as permission levels merely because they currently increase in
     * the same order.</p>
     *
     * @param requiredGroup lowest group that should be accepted
     * @return {@code true} when this group contains the required access
     */
    public boolean inheritsFrom(Group requiredGroup) {
        if (requiredGroup == null) return false;

        for (Group current = this; current != null; current = current.parent) {
            if (current == requiredGroup) return true;
        }

        return false;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix != null ? prefix : "";
    }

    public TextColor getNameColor() {
        return nameColor;
    }

    public void setNameColor(String hexadecimalNameColor) {
        this.nameColor = requireValidColor(hexadecimalNameColor);
    }

    /**
     * Registers a command label and all its aliases directly to this group.
     *
     * @param commandLabel canonical command label without a leading slash
     * @param aliases alternative labels resolving to the canonical label
     */
    public void registerCommand(
        String commandLabel,
        Collection<String> aliases
    ) {
        String normalizedCommandLabel =
            requireValidCommandLabel(commandLabel);

        directlyAssignedCommands.put(
            normalizedCommandLabel,
            normalizedCommandLabel
        );

        if (aliases == null) {
            return;
        }

        for (String alias : aliases) {
            String normalizedAlias = normalizeCommandLabel(alias);

            if (normalizedAlias != null) {
                directlyAssignedCommands.put(
                    normalizedAlias,
                    normalizedCommandLabel
                );
            }
        }
    }

    /**
     * Determines whether this group or any inherited group grants a command.
     */
    public boolean canUseCommand(String commandLabelOrAlias) {
        String normalizedInput =
            normalizeCommandLabel(commandLabelOrAlias);

        if (normalizedInput == null) {
            return false;
        }

        if (directlyAssignedCommands.containsKey(normalizedInput)) {
            return true;
        }

        return parent != null
            && parent.canUseCommand(normalizedInput);
    }

    /**
     * Resolves a label or alias to its canonical command label.
     *
     * @return canonical label, or {@code null} when unavailable
     */
    public String resolveCommandLabel(String commandLabelOrAlias) {
        String normalizedInput =
            normalizeCommandLabel(commandLabelOrAlias);

        if (normalizedInput == null) {
            return null;
        }

        String directlyAssignedCommand =
            directlyAssignedCommands.get(normalizedInput);

        if (directlyAssignedCommand != null) {
            return directlyAssignedCommand;
        }

        return parent != null
            ? parent.resolveCommandLabel(normalizedInput)
            : null;
    }

    /**
     * Returns an immutable map containing direct and inherited commands.
     *
     * <p>Parent commands are inserted first, allowing this group's direct
     * assignments to override inherited alias mappings if necessary.</p>
     */
    public Map<String, String> getAvailableCommands() {
        Map<String, String> availableCommands =
            new LinkedHashMap<>();

        if (parent != null) {
            availableCommands.putAll(parent.getAvailableCommands());
        }

        availableCommands.putAll(directlyAssignedCommands);

        return Collections.unmodifiableMap(availableCommands);
    }

    /**
     * Removes a directly assigned command and all direct aliases pointing to it.
     *
     * <p>Inherited commands cannot be removed through this method.</p>
     */
    public void unregisterCommand(String commandLabel) {
        String normalizedCommandLabel =
            normalizeCommandLabel(commandLabel);

        if (normalizedCommandLabel == null) {
            return;
        }

        directlyAssignedCommands.entrySet().removeIf(
            entry -> entry.getValue().equals(normalizedCommandLabel)
        );
    }

    public void grantFlag(Perm permissionFlag) {
        directlyAssignedFlags.add(
            Objects.requireNonNull(
                permissionFlag,
                "Permission flag cannot be null."
            )
        );
    }

    public void revokeFlag(Perm permissionFlag) {
        if (permissionFlag != null) {
            directlyAssignedFlags.remove(permissionFlag);
        }
    }

    /**
     * Determines whether this group or any inherited group grants a flag.
     */
    public boolean hasFlag(Perm permissionFlag) {
        if (permissionFlag == null) {
            return false;
        }

        if (directlyAssignedFlags.contains(permissionFlag)) {
            return true;
        }

        return parent != null
            && parent.hasFlag(permissionFlag);
    }

    public Set<Perm> getAvailableFlags() {
        Set<Perm> availableFlags =
            EnumSet.noneOf(Perm.class);

        if (parent != null) {
            availableFlags.addAll(parent.getAvailableFlags());
        }

        availableFlags.addAll(directlyAssignedFlags);

        return Collections.unmodifiableSet(availableFlags);
    }

    private static String requireValidCommandLabel(String commandLabel) {
        String normalizedCommandLabel =
            normalizeCommandLabel(commandLabel);

        if (normalizedCommandLabel == null) {
            throw new IllegalArgumentException(
                "Command label cannot be null or blank."
            );
        }

        return normalizedCommandLabel;
    }

    private static String normalizeCommandLabel(String commandLabel) {
        if (commandLabel == null) {
            return null;
        }

        String normalizedCommandLabel = commandLabel
            .trim()
            .toLowerCase(Locale.ROOT);

        if (normalizedCommandLabel.startsWith("/")) {
            normalizedCommandLabel =
                normalizedCommandLabel.substring(1);
        }

        return normalizedCommandLabel.isEmpty()
            ? null
            : normalizedCommandLabel;
    }

    private static TextColor requireValidColor(
        String hexadecimalColor
    ) {
        if (hexadecimalColor == null) {
            throw new IllegalArgumentException(
                "Group color cannot be null."
            );
        }

        TextColor parsedColor =
            TextColor.fromHexString(hexadecimalColor);

        if (parsedColor == null) {
            throw new IllegalArgumentException(
                "Invalid hexadecimal group color: "
                + hexadecimalColor
            );
        }

        return parsedColor;
    }
}
