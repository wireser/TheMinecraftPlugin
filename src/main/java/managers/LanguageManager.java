package managers;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Per-scope language accessor (e.g. per module).
 *
 * Implementations are expected to:
 * - load messages from configuration (YAML, etc.)
 * - cache them in memory
 * - return Components with colors already parsed
 *
 * Keys are treated as case-insensitive.
 */
public interface LanguageManager {

    /**
     * Maximum allowed length of a single message.
     * Longer values are treated as invalid and replaced by a placeholder.
     */
    int MAX_MESSAGE_LENGTH = 512;

    /**
     * Gets a localized message as a Component by key.
     *
     * @param key language key (e.g. "error_warp_not_found")
     * @return localized Component; never {@code null}.
     *         Missing/invalid keys return a red %KEY% placeholder.
     */
    @NotNull
    Component get(@NotNull String key);

    /**
     * Gets a localized message as a Component and applies positional placeholders.
     *
     * Placeholders:
     *   %1, %2, ..., %N
     *
     * Example:
     *   lang.yml: error_warp_not_found: "&7Error: &cWe cant find warp &f%1&7."
     *   lang.get("error_warp_not_found", warpName);
     *
     * @param key       language key
     * @param arguments positional arguments (%1..%N)
     * @return localized Component; never {@code null}
     */
    @NotNull
    Component get(@NotNull String key, Object... arguments);

    /**
     * Plain-text version of the message with colors stripped.
     *
     * @param key language key
     * @return plain text; never {@code null}
     */
    @NotNull
    String getString(@NotNull String key);

    /**
     * Plain-text version with positional arguments applied.
     *
     * @param key       language key
     * @param arguments positional arguments
     * @return plain text; never {@code null}
     */
    @NotNull
    String getString(@NotNull String key, Object... arguments);

}