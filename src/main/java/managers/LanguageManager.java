package managers;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;

/**
 * Provides access to language messages cached in memory.
 */
public interface LanguageManager {

    /**
     * Maximum permitted length of one language message.
     */
    int MAX_MESSAGE_LENGTH = 512;

    /**
     * Reloads all language data from its configured sources.
     */
    void reload();

    /**
     * Returns a formatted language message as an Adventure component.
     *
     * @param key language key
     * @param arguments values replacing %1, %2, and so on
     * @return resolved message
     */
    @NotNull
    Component line(@NotNull String key, Object... arguments);

    /**
     * Returns a formatted language message without formatting.
     *
     * @param key language key
     * @param arguments values replacing %1, %2, and so on
     * @return resolved plain-text message
     */
    @NotNull
    String plain(@NotNull String key, Object... arguments);

    /**
     * Returns a bounded copy of a string list cached from the language file.
     *
     * <p>Invalid, empty or missing lists produce a one-item list containing
     * {@code defaultItem}. If the default is {@code null} or blank, an empty
     * list is returned instead. Items beyond {@code maximumItems} are ignored.</p>
     *
     * @param key language-list key
     * @param maximumItems greatest number of items returned; must be positive
     * @param defaultItem fallback item, or {@code null} for no fallback
     * @return immutable bounded list from the in-memory language snapshot
     */
    @NotNull
    List<String> list(@NotNull String key, int maximumItems, @Nullable String defaultItem);

    /**
     * Formats a raw language template as an Adventure component.
     *
     * <p>This is useful for values selected from {@link #list(String, int,
     * String)} because they are templates rather than dotted language keys.</p>
     *
     * @param template raw MiniMessage template
     * @param arguments values replacing %1, %2, and so on
     * @return formatted component
     */
    @NotNull
    Component render(@NotNull String template, Object... arguments);

}
