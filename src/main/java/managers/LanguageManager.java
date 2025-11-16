package managers;

import org.jetbrains.annotations.NotNull;

/**
 * Provides access to localized messages from config or resource files.
 * Implementations should be safe to call frequently from the main thread.
 */
public interface LanguageManager {

    /**
     * Gets a message by key, or the fallback if not found.
     *
     * @param key      Language key (e.g. "command.no_permission")
     * @param fallback Fallback value if the key is missing
     * @return Localized text or fallback
     */
    @NotNull
    String get(@NotNull String key, @NotNull String fallback);

    /**
     * Gets and formats a message by key, or uses the fallback format string.
     * Arguments are passed to String.format (or similar) internally.
     *
     * @param key       Language key
     * @param fallback  Fallback format string
     * @param arguments Arguments to interpolate
     * @return Localized and formatted message
     */
    @NotNull
    String getFormatted(@NotNull String key, @NotNull String fallback, Object... arguments);
    
}