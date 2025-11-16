package command;

import enums.Currency;
import model.Group;

import java.util.*;

/**
 * Represents a command with all its configuration options.
 * <p>
 * Instances of this class are immutable once created via {@link Builder}.
 * It encapsulates metadata (label, aliases, syntax, description, permissions,
 * module, group, usages) as well as runtime behavior (executor, cost, cooldown,
 * optional tab-completion handler).
 */
public final class CommandRegistry {

    /**
     * The original label as provided to the builder (preserves casing).
     */
    private final String rawLabel;

    /**
     * The normalized, lowercase primary label used as the canonical key.
     */
    private final String label;

    /**
     * Normalized, lowercase aliases. Never {@code null}, never modifiable.
     */
    private final List<String> aliases;

    /**
     * Optional syntax string for help/usage messages.
     * Never {@code null}; defaults to empty string.
     */
    private final String syntax;

    /**
     * Optional description string for help menus.
     * Never {@code null}; defaults to empty string.
     */
    private final String description;

    /**
     * Cost required to execute the command (always &gt;= 0).
     * A value of 0 means "no cost".
     */
    private final double cost;

    /**
     * Currency type for the cost.
     * May be {@code null} to indicate "no currency defined" even if cost is &gt; 0;
     * in that case, higher-level logic decides how to handle it.
     */
    private final Currency currency;

    /**
     * Cooldown in seconds (always &gt;= 0).
     * A value of 0 means "no cooldown".
     */
    private final int cooldownSeconds;

    /**
     * Optional Bukkit-style permission node.
     * {@code null} means "no permission required".
     */
    private final String permissionNode;

    /**
     * Name of the module this command belongs to.
     * Never {@code null}; defaults to {@code "global"}.
     */
    private final String moduleName;

    /**
     * Optional group that this command is associated with.
     * May be {@code null} if it is not bound to a specific group.
     */
    private final Group group;

    /**
     * Command execution logic.
     * Never {@code null}.
     */
    private final CommandHandler executor;

    /**
     * Whether this command is currently enabled.
     */
    private final boolean enabled;

    /**
     * Optional advanced usage metadata.
     * <p>
     * Keys are raw usage strings (e.g. {@code "/home [player]"}),
     * values are human-readable descriptions. The map is unmodifiable.
     */
    private final Map<String, String> usages;

    /**
     * Optional per-command tab-completion handler.
     * May be {@code null} if no custom tab completion is defined.
     */
    private final TabHandler tabHandler;

    /**
     * Private constructor used by the {@link Builder}.
     *
     * @param builder the builder containing configuration values
     */
    private CommandRegistry(Builder builder) {
        // Basic validation
        if (builder.label == null || builder.label.trim().isEmpty()) {
            throw new IllegalArgumentException("Command label cannot be null or empty.");
        }
        if (builder.executor == null) {
            throw new IllegalArgumentException("Command executor cannot be null for label: " + builder.label);
        }

        // Original + normalized label
        this.rawLabel = builder.label;
        this.label = builder.label.trim().toLowerCase(Locale.ROOT);

        // Normalize aliases: lowercase, trimmed, non-null, non-empty, unique, not equal to label
        List<String> normalizedAliases = new ArrayList<>();
        if (builder.aliases != null) {
            for (String alias : builder.aliases) {
                if (alias == null) continue;
                String trimmed = alias.trim();
                if (trimmed.isEmpty()) continue;

                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.equals(this.label)) continue; // skip alias identical to main label
                if (!normalizedAliases.contains(lower)) {
                    normalizedAliases.add(lower);
                }
            }
        }
        this.aliases = Collections.unmodifiableList(normalizedAliases);

        // Normalize optional strings
        this.syntax = builder.syntax != null ? builder.syntax : "";
        this.description = builder.description != null ? builder.description : "";

        // Usage metadata & tab handler
        this.usages = Collections.unmodifiableMap(new LinkedHashMap<>(builder.usages));
        this.tabHandler = builder.tabHandler;

        // Cost & currency
        this.cost = Math.max(0.0, builder.cost); // no negative costs
        this.currency = builder.currency;        // may be null if "no currency" is desired

        // Cooldown (never negative)
        this.cooldownSeconds = Math.max(0, builder.cooldownSeconds);

        // Permission node: normalize empty string to null
        if (builder.permissionNode != null) {
            String trimmed = builder.permissionNode.trim();
            this.permissionNode = trimmed.isEmpty() ? null : trimmed;
        } else {
            this.permissionNode = null;
        }

        // Module name: default to "global" if null/blank
        if (builder.moduleName != null && !builder.moduleName.trim().isEmpty()) {
            this.moduleName = builder.moduleName.trim();
        } else {
            this.moduleName = "global";
        }

        this.group = builder.group;
        this.executor = builder.executor;
        this.enabled = builder.enabled;
    }

    // ===================== Getters =====================

    /**
     * @return The original label as provided to the builder (preserves casing).
     */
    public String getRawLabel() {
        return rawLabel;
    }

    /**
     * @return The normalized, lowercase primary label used as canonical key.
     */
    public String getLabel() {
        return label;
    }

    /**
     * @return An unmodifiable list of normalized, lowercase aliases.
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * @return The syntax string for this command, or empty string if not set.
     */
    public String getSyntax() {
        return syntax;
    }

    /**
     * @return The description string for this command, or empty string if not set.
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return The cost required to execute this command (always &gt;= 0).
     */
    public double getCost() {
        return cost;
    }

    /**
     * @return The currency used for this command's cost, or {@code null} if none.
     */
    public Currency getCurrency() {
        return currency;
    }

    /**
     * @return The cooldown in seconds for this command (always &gt;= 0).
     */
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * @return The Bukkit-style permission node, or {@code null} if no permission is required.
     */
    public String getPermissionNode() {
        return permissionNode;
    }

    /**
     * @return The module name this command belongs to. Never {@code null}.
     */
    public String getModuleName() {
        return moduleName;
    }

    /**
     * @return The group associated with this command, or {@code null} if none.
     */
    public Group getGroup() {
        return group;
    }

    /**
     * @return The executor responsible for handling this command.
     */
    public CommandHandler getExecutor() {
        return executor;
    }

    /**
     * @return {@code true} if this command is enabled, {@code false} otherwise.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return an unmodifiable map of advanced usages:
     *         raw usage strings mapped to human-readable descriptions.
     *         May be empty if no usages were defined.
     */
    public Map<String, String> getUsages() {
        return usages;
    }

    /**
     * @return {@code true} if this command has a non-zero cost and a defined currency.
     */
    public boolean hasCost() {
        return cost > 0 && currency != null;
    }

    /**
     * @return {@code true} if this command has a cooldown greater than zero.
     */
    public boolean hasCooldown() {
        return cooldownSeconds > 0;
    }

    /**
     * @return {@code true} if this command is considered free
     *         (no cost or no currency defined).
     */
    public boolean isFree() {
        return cost <= 0 || currency == null;
    }

    /**
     * @return An unmodifiable set containing the main label and all aliases
     *         (all normalized to lowercase).
     */
    public Set<String> getAllLabels() {
        Set<String> labels = new LinkedHashSet<>();
        labels.add(label);
        labels.addAll(aliases);
        return Collections.unmodifiableSet(labels);
    }

    /**
     * @return {@code true} if this command has an associated permission node.
     */
    public boolean hasPermissionNode() {
        return permissionNode != null && !permissionNode.isEmpty();
    }

    /**
     * @return the tab-completion handler for this command, or {@code null} if none is set.
     */
    public TabHandler getTabHandler() {
        return tabHandler;
    }

    /**
     * @return {@code true} if this command has a tab-completion handler, {@code false} otherwise.
     */
    public boolean hasTabHandler() {
        return tabHandler != null;
    }

    @Override
    public String toString() {
        return "CommandRegistry{" +
               "label='" + label + '\'' +
               ", aliases=" + aliases +
               ", moduleName='" + moduleName + '\'' +
               ", enabled=" + enabled +
               '}';
    }

    // ===================== Builder =====================

    /**
     * Builder for creating immutable {@link CommandRegistry} instances.
     * <p>
     * Usage example:
     * <pre>
     * CommandRegistry cmd = new CommandRegistry.Builder("spawn", handler)
     *     .aliases("hub", "lobby")
     *     .description("Teleport to spawn")
     *     .syntax("/spawn")
     *     .cost(100, Currency.MONEY)
     *     .cooldownSeconds(10)
     *     .permissionNode("tmp.spawn.use")
     *     .moduleName("Core")
     *     .group(defaultGroup)
     *     .build();
     * </pre>
     */
    public static class Builder {

        private final String label;
        private final CommandHandler executor;

        private List<String> aliases = new ArrayList<>();
        private final Map<String, String> usages = new LinkedHashMap<>();
        private String syntax = "";
        private String description = "";
        private double cost = 0.0;
        private Currency currency = null; // null means "no currency defined"
        private int cooldownSeconds = 0;
        private String permissionNode = null; // Bukkit-style permission node
        private String moduleName = "global";
        private Group group = null;
        private boolean enabled = true;
        private TabHandler tabHandler = null;

        /**
         * Creates a new command builder.
         *
         * @param label    The primary command label (will be normalized to lowercase).
         * @param executor The command execution logic (must not be {@code null}).
         */
        public Builder(String label, CommandHandler executor) {
            this.label = label;
            this.executor = executor;
        }

        /**
         * Sets command aliases.
         *
         * @param aliases The command aliases (may be {@code null}).
         * @return This builder instance.
         */
        public Builder aliases(String... aliases) {
            if (aliases == null) return this;
            this.aliases = Arrays.asList(aliases);
            return this;
        }

        /**
         * Sets the syntax string for the command.
         *
         * @param syntax The command syntax (may be {@code null}).
         * @return This builder instance.
         */
        public Builder syntax(String syntax) {
            this.syntax = syntax;
            return this;
        }

        /**
         * Sets the description for the command.
         *
         * @param description The command description (may be {@code null}).
         * @return This builder instance.
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the execution cost for the command.
         *
         * @param cost     The cost to execute the command (will be clamped to &gt;= 0).
         * @param currency The currency type for the cost (may be {@code null}).
         * @return This builder instance.
         */
        public Builder cost(double cost, Currency currency) {
            this.cost = cost;
            this.currency = currency;
            return this;
        }

        /**
         * Sets the cooldown time in seconds for the command.
         *
         * @param cooldownSeconds Cooldown time in seconds (will be clamped to &gt;= 0).
         * @return This builder instance.
         */
        public Builder cooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
            return this;
        }

        /**
         * Sets a Bukkit-style permission node required to execute this command.
         *
         * @param permissionNode The permission node string (may be {@code null} or empty).
         * @return This builder instance.
         */
        public Builder permissionNode(String permissionNode) {
            this.permissionNode = permissionNode;
            return this;
        }

        /**
         * Sets the module name this command belongs to.
         *
         * @param moduleName The name of the module (may be {@code null} or blank).
         * @return This builder instance.
         */
        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        /**
         * Sets the associated group that can access this command by default.
         *
         * @param group The group instance (may be {@code null}).
         * @return This builder instance.
         */
        public Builder group(Group group) {
            this.group = group;
            return this;
        }

        /**
         * Sets whether the command is enabled.
         *
         * @param enabled {@code true} if enabled, {@code false} to disable.
         * @return This builder instance.
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Adds an advanced usage entry for this command.
         *
         * @param usage       The raw usage string (e.g. {@code "/home [player]"}). Must not be blank.
         * @param description A human-readable description for this usage (may be {@code null} or empty).
         * @return This builder instance.
         */
        public Builder usage(String usage, String description) {
            if (usage != null && !usage.trim().isEmpty()) {
                usages.put(usage, description != null ? description : "");
            }
            return this;
        }

        /**
         * Sets a custom tab-completion handler for this command.
         *
         * @param tabHandler The handler implementation (may be {@code null} to clear).
         * @return This builder instance.
         */
        public Builder tabHandler(TabHandler tabHandler) {
            this.tabHandler = tabHandler;
            return this;
        }

        /**
         * Builds the immutable {@link CommandRegistry} instance.
         *
         * @return The created CommandRegistry.
         * @throws IllegalArgumentException if required fields are invalid.
         */
        public CommandRegistry build() {
            return new CommandRegistry(this);
        }
    }
    
}