package enums;

/**
 * Describes which color codes may be stored inside a player's nickname.
 *
 * <p>Visual effects supplied by a chat style, such as bold moderator names,
 * are deliberately not part of nickname data.</p>
 */
public enum NicknameFormattingLevel {

    /** No color codes are accepted. */
    PLAIN,

    /** Legacy Minecraft colors ({@code &0} through {@code &f}) are accepted. */
    LEGACY_COLORS,

    /** Legacy colors and RGB colors in {@code &#RRGGBB} form are accepted. */
    RGB_COLORS
}
