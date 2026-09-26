package utils;

import java.util.Locale;
import java.util.regex.Pattern;

import enums.NicknameFormattingLevel;

/**
 * Shared validation and normalization rules for player-provided data.
 *
 * <p>Validation answers whether a value may be accepted. Normalization creates
 * the stable form used for comparisons and database lookups.</p>
 */
public final class Validator {

    private static final int DEFAULT_MAXIMUM_WELCOME_CODE_POINTS = 70;
    private static final int DEFAULT_MAXIMUM_WELCOME_LINES = 2;

    private static final Pattern NUMERIC = Pattern.compile("[0-9]+");
    private static final Pattern ALPHABETIC = Pattern.compile("[A-Za-z]+");
    private static final Pattern EXTENDED_ALPHABETIC = Pattern.compile("\\p{L}+");
    private static final Pattern ALPHANUMERIC = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern ALPHANUMERIC_SPACE = Pattern.compile("[A-Za-z0-9 ]+");
    private static final Pattern EXTENDED_ALPHANUMERIC = Pattern.compile("[\\p{L}\\p{Nd}]+");
    private static final Pattern EXTENDED_ALPHANUMERIC_SPACE = Pattern.compile("[\\p{L}\\p{Nd} ]+");
    private static final Pattern ONLY_EDITING_CHARS = Pattern.compile("[,?;\\.:\\-_\\$\\|\\[\\]\\{\\}@&#<>€+\"!%/=()]+");
    private static final Pattern ID = Pattern.compile("[0-9]{1,9}");
    private static final Pattern STRING_16 = Pattern.compile("[A-Za-z0-9 ]{1,16}");
    private static final Pattern STRING_32 = Pattern.compile("[A-Za-z0-9 ]{1,32}");
    private static final Pattern STRING_64 = Pattern.compile("[A-Za-z0-9 ]{1,64}");
    private static final Pattern STRING_128 = Pattern.compile("[A-Za-z0-9 ]{1,128}");

    /* Minecraft account names contain 3-16 ASCII letters, numbers or underscores. */
    private static final Pattern MINECRAFT_USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final Pattern NICKNAME_COLOR_CODE = Pattern.compile("(?i)(?:&#[0-9a-f]{6}|&[0-9a-fr])");

    private Validator() {
        throw new UnsupportedOperationException("Validator cannot be instantiated.");
    }

    /**
     * Checks a value against a compiled pattern without accepting {@code null}.
     *
     * @param value value to test
     * @param pattern validation pattern
     * @return {@code true} when the complete value matches the pattern
     */
    private static boolean matches(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    public static boolean isNumeric(String value) {
        return matches(value, NUMERIC);
    }

    public static boolean isAlphabetic(String value) {
        return matches(value, ALPHABETIC);
    }

    public static boolean isExtendedAlphabetic(String value) {
        return matches(value, EXTENDED_ALPHABETIC);
    }

    public static boolean isAlphanumeric(String value) {
        return matches(value, ALPHANUMERIC);
    }

    public static boolean isAlphanumericAndSpace(String value) {
        return matches(value, ALPHANUMERIC_SPACE);
    }

    public static boolean isExtendedAlphanumeric(String value) {
        return matches(value, EXTENDED_ALPHANUMERIC);
    }

    public static boolean isExtendedAlphanumericAndSpace(String value) {
        return matches(value, EXTENDED_ALPHANUMERIC_SPACE);
    }

    public static boolean isOnlyEditingChars(String value) {
        return matches(value, ONLY_EDITING_CHARS);
    }

    public static boolean isID(String value) {
        return matches(value, ID);
    }

    /** Alias using normal Java word casing. */
    public static boolean isId(String value) {
        return isID(value);
    }

    public static boolean isString16(String value) {
        return matches(value, STRING_16);
    }

    public static boolean isString32(String value) {
        return matches(value, STRING_32);
    }

    public static boolean isString64(String value) {
        return matches(value, STRING_64);
    }

    public static boolean isString128(String value) {
        return matches(value, STRING_128);
    }

    /**
     * Validates a real Minecraft account name, including names beginning with
     * an underscore.
     *
     * @param username account name to validate
     * @return {@code true} for 3-16 ASCII letters, numbers or underscores
     */
    public static boolean isValidMinecraftUsername(String username) {
        return matches(username, MINECRAFT_USERNAME);
    }

    /** Alias retained for callers which prefer the shorter name. */
    public static boolean isMinecraftUsername(String username) {
        return isValidMinecraftUsername(username);
    }

    /**
     * Validates a formatted nickname.
     *
     * <p>The owning module supplies its current limits and patterns. This class
     * applies those rules without deciding gameplay policy itself.</p>
     *
     * @param nickname formatted nickname to validate
     * @param formattingLevel color syntax available to the nickname owner
     * @param maximumFormattedLength maximum raw length including color codes
     * @param maximumSeparators maximum combined dots and underscores
     * @param visibleTextPattern complete pattern for the nickname after colors are removed
     * @param rgbColorPattern pattern matching one supported RGB color code
     * @param colorCodePattern pattern matching every supported color code
     * @return {@code true} when both its formatting and visible name are valid
     */
    public static boolean isValidNickname(String nickname, NicknameFormattingLevel formattingLevel,
            int maximumFormattedLength, int maximumSeparators, Pattern visibleTextPattern,
            Pattern rgbColorPattern, Pattern colorCodePattern) {
        if (nickname == null || nickname.isBlank() || formattingLevel == null
                || maximumFormattedLength < 1 || maximumSeparators < 0
                || visibleTextPattern == null || rgbColorPattern == null || colorCodePattern == null
                || nickname.length() > maximumFormattedLength) return false;

        String visibleNickname = stripNicknameColors(nickname, colorCodePattern);
        if (!matches(visibleNickname, visibleTextPattern)) return false;
        if (countNicknameSeparators(visibleNickname) > maximumSeparators) return false;

        boolean containsFormatting = !visibleNickname.equals(nickname);
        if (formattingLevel == NicknameFormattingLevel.PLAIN && containsFormatting) return false;
        if (formattingLevel == NicknameFormattingLevel.LEGACY_COLORS
                && rgbColorPattern.matcher(nickname).find()) return false;

        return true;
    }

    /** Counts the dots and underscores used as visible nickname separators. */
    private static long countNicknameSeparators(String nickname) {
        return nickname.chars()
                .filter(character -> character == '.' || character == '_')
                .count();
    }

    /**
     * Removes the supported color codes from a nickname while preserving its
     * visible spelling and capitalization.
     *
     * @param nickname formatted nickname
     * @return nickname without color codes, or {@code null} for {@code null}
     */
    public static String stripNicknameColors(String nickname) {
        return nickname == null ? null : NICKNAME_COLOR_CODE.matcher(nickname).replaceAll("");
    }

    /** Removes color codes using the syntax supplied by the owning module. */
    public static String stripNicknameColors(String nickname, Pattern colorCodePattern) {
        if (nickname == null) return null;
        if (colorCodePattern == null) return nickname;
        return colorCodePattern.matcher(nickname).replaceAll("");
    }

    /**
     * Produces the case-insensitive lookup key for a nickname. For example,
     * {@code &cPeter}, {@code peter} and {@code PETER} all become {@code peter}.
     *
     * @param nickname formatted nickname
     * @return lowercase visible nickname, or {@code null} for {@code null}
     */
    public static String normalizeNicknameForLookup(String nickname) {
        String visibleNickname = stripNicknameColors(nickname);
        return visibleNickname == null ? null : visibleNickname.toLowerCase(Locale.ROOT);
    }

    /** Produces a lookup key using the color syntax supplied by the module. */
    public static String normalizeNicknameForLookup(String nickname, Pattern colorCodePattern) {
        String visibleNickname = stripNicknameColors(nickname, colorCodePattern);
        return visibleNickname == null ? null : visibleNickname.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes line endings and removes surrounding whitespace from a welcome
     * message before it is validated or stored.
     *
     * @param welcomeMessage welcome text supplied by staff
     * @return normalized text, or {@code null} for {@code null}
     */
    public static String normalizeWelcomeMessage(String welcomeMessage) {
        return welcomeMessage == null ? null : welcomeMessage.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /**
     * Validates a staff-managed welcome message.
     *
     * <p>Welcome messages may contain Unicode because they are supervised prose,
     * not searchable player identifiers. This overload applies the standard
     * 70-code-point and two-line limits.</p>
     *
     * @param welcomeMessage welcome text to validate
     * @return {@code true} when the normalized message is safe to store
     */
    public static boolean isValidWelcomeMessage(String welcomeMessage) {
        return isValidWelcomeMessage(welcomeMessage, DEFAULT_MAXIMUM_WELCOME_CODE_POINTS,
                DEFAULT_MAXIMUM_WELCOME_LINES);
    }

    /**
     * Validates a welcome message using caller-supplied limits.
     *
     * @param welcomeMessage welcome text to validate
     * @param maximumCodePoints maximum Unicode code points across every line
     * @param maximumLines maximum non-empty lines
     * @return {@code true} when the normalized message satisfies the limits
     */
    public static boolean isValidWelcomeMessage(String welcomeMessage, int maximumCodePoints,
            int maximumLines) {
        String normalizedMessage = normalizeWelcomeMessage(welcomeMessage);
        if (normalizedMessage == null || normalizedMessage.isEmpty()
                || maximumCodePoints < 1 || maximumLines < 1) return false;

        String[] lines = normalizedMessage.split("\n", -1);
        if (lines.length > maximumLines) return false;

        for (String line : lines) {
            if (line.isBlank()) return false;
        }

        if (normalizedMessage.codePointCount(0, normalizedMessage.length()) > maximumCodePoints)
            return false;

        return normalizedMessage.codePoints()
                .noneMatch(codePoint -> codePoint != '\n' && Character.isISOControl(codePoint));
    }

    public static boolean checkString(String value) {
        return validateString(value) && between(value, 1, 2048);
    }

    public static boolean validateString(String value) {
        return value != null && !value.isEmpty();
    }

    public static boolean checkString(String value, int minimumLength, int maximumLength) {
        return validateString(value) && between(value, minimumLength, maximumLength);
    }

    public static boolean validateString(String value, int minimumLength, int maximumLength) {
        return checkString(value, minimumLength, maximumLength);
    }

    public static boolean isNotNull(String value) {
        return value != null;
    }

    public static boolean between(String value, int minimumLength, int maximumLength) {
        return value != null && value.length() >= minimumLength && value.length() <= maximumLength;
    }

    public static boolean between(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
