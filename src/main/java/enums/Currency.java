package enums;

/**
 * Represents all supported logical player currencies.
 * <p>
 * Each currency defines:
 * <ul>
 *     <li>a stable key used for persistence and configuration,</li>
 *     <li>a human-readable display name for UI,</li>
 *     <li>a numeric mode indicating whether it is treated as an integral
 *         counter or as a fractional balance.</li>
 * </ul>
 *
 * <p>Notes on storage and formatting:</p>
 * <ul>
 *     <li>The underlying database column is {@code DECIMAL(12,3)} for all
 *         currencies to keep schema simple and uniform.</li>
 *     <li>{@link NumericMode#INTEGRAL} currencies are still stored as
 *         decimals in the database, but gameplay code should treat them as
 *         whole numbers (e.g. rounds or truncates to an integer).</li>
 *     <li>{@link NumericMode#DECIMAL} currencies are intended to support
 *         fractional values.</li>
 * </ul>
 */
public enum Currency {

    /**
     * Cash carried by the player, typically shown in wallets or as the primary
     * in-hand spending currency.
     */
    MONEY("money", "Money", NumericMode.DECIMAL),

    /**
     * Bank account balance – usually accessed with deposit/withdraw mechanics.
     */
    BALANCE("balance", "Bank Balance", NumericMode.DECIMAL),

    /**
     * Premium or grindable token, usually gained from gameplay or shop
     * rewards. Treated as an integer count.
     */
    GEMS("gems", "Gems", NumericMode.INTEGRAL),

    /**
     * Higher-tier premium currency. Also treated as an integer count.
     */
    RUBIES("rubies", "Rubies", NumericMode.INTEGRAL),

    /**
     * Level-like currency, which may mirror vanilla levels or be a custom
     * progression system. Integer only.
     */
    LEVELS("levels", "Levels", NumericMode.INTEGRAL),

    /**
     * Generic point system (for minigames, achievements, etc.), stored as an
     * integer count.
     */
    POINTS("points", "Points", NumericMode.INTEGRAL),

    /**
     * Reputation / karma score. Designed as a decimal to allow small
     * adjustments over time.
     */
    KARMA("karma", "Karma", NumericMode.DECIMAL);

    /**
     * Describes how a currency should be treated in gameplay logic.
     * <p>
     * All currencies are still persisted as {@code DECIMAL(12,3)} in the
     * database; this enum only defines the intended semantics.
     */
    public enum NumericMode {
        /**
         * Values are conceptually whole numbers (0, 1, 2, …). When using
         * {@link java.math.BigDecimal}, code should use integer-style
         * operations (rounding or truncation as appropriate).
         */
        INTEGRAL,

        /**
         * Values may have a fractional part (e.g. 12.345). Formatting and
         * arithmetic should preserve decimal precision where it matters.
         */
        DECIMAL
    }

    /**
     * Stable key used for persistence and configuration.
     * <p>
     * Typical usage:
     * <ul>
     *     <li>as {@code key} in {@code players_balances} table,</li>
     *     <li>as identifiers in configuration files,</li>
     *     <li>for logging and debug output.</li>
     * </ul>
     */
    private final String key;

    /**
     * Human-readable display name for UI (menus, messages, etc.).
     */
    private final String displayName;

    /**
     * Intended numeric semantics for this currency.
     */
    private final NumericMode mode;

    Currency(String key, String displayName, NumericMode mode) {
        this.key = key;
        this.displayName = displayName;
        this.mode = mode;
    }

    /**
     * Returns the stable key for this currency.
     * <p>
     * This should be used for:
     * <ul>
     *     <li>database lookups,</li>
     *     <li>configuration keys,</li>
     *     <li>internal identifiers.</li>
     * </ul>
     *
     * @return non-null key string
     */
    public String key() {
        return key;
    }

    /**
     * Returns the human-readable label for this currency.
     *
     * @return non-null display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the numeric mode that describes how this currency is intended
     * to be treated in gameplay logic.
     *
     * @return non-null numeric mode
     */
    public NumericMode mode() {
        return mode;
    }

    /**
     * @return {@code true} if this currency is intended to be treated as a
     *         whole-number count.
     */
    public boolean isIntegral() {
        return mode == NumericMode.INTEGRAL;
    }

    /**
     * @return {@code true} if this currency is designed to support fractional
     *         values.
     */
    public boolean isDecimal() {
        return mode == NumericMode.DECIMAL;
    }

}