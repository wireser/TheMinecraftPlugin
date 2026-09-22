package managers;

import org.jetbrains.annotations.NotNull;

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
    Component line(
            @NotNull String key,
            Object... arguments
    );

    /**
     * Returns a formatted language message without formatting.
     *
     * @param key language key
     * @param arguments values replacing %1, %2, and so on
     * @return resolved plain-text message
     */
    @NotNull
    String plain(
            @NotNull String key,
            Object... arguments
    );

}