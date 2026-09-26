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

    private static final int MAX_FORMATTED_NICKNAME_LENGTH = 255;
    private static final int MAX_WELCOME_CODE_POINTS = 70;
    private static final int MAX_WELCOME_LINES = 2;

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

    private static final Pattern NICKNAME_RGB_COLOR = Pattern.compile("(?i)&#[0-9a-f]{6}");
    private static final Pattern NICKNAME_COLOR_CODE = Pattern.compile("(?i)(?:&#[0-9a-f]{6}|&[0-9a-fr])");
    private static final Pattern NICKNAME_VISIBLE_TEXT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._]{1,18}[A-Za-z0-9]");

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
     * <p>The visible nickname contains 3-20 ASCII letters, numbers, dots or
     * underscores, begins and ends with a letter or number, and uses at most
     * three dots/underscores in total. Color codes do not count toward the
     * visible limit. The raw limit leaves enough room for RGB colors.</p>
     *
     * @param nickname formatted nickname to validate
     * @param formattingLevel color syntax available to the nickname owner
     * @return {@code true} when both its formatting and visible name are valid
     */
    public static boolean isValidNickname(String nickname, NicknameFormattingLevel formattingLevel) {
        if (nickname == null || nickname.isBlank() || nickname.length() > MAX_FORMATTED_NICKNAME_LENGTH) {
            return false;
        }

        if (formattingLevel == null) return false;

        String visibleNickname = stripNicknameColors(nickname);
        if (!matches(visibleNickname, NICKNAME_VISIBLE_TEXT)) return false;
        if (countNicknameSeparators(visibleNickname) > 3) return false;

        boolean containsFormatting = !visibleNickname.equals(nickname);
        if (formattingLevel == NicknameFormattingLevel.PLAIN && containsFormatting) return false;
        if (formattingLevel == NicknameFormattingLevel.LEGACY_COLORS
                && NICKNAME_RGB_COLOR.matcher(nickname).find()) return false;

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
     * not searchable player identifiers. They may contain one or two non-empty
     * lines and at most 70 Unicode code points in total. Color is deliberately
     * not handled here; the join-message renderer applies one server-owned
     * color to the complete message.</p>
     *
     * @param welcomeMessage welcome text to validate
     * @return {@code true} when the normalized message is safe to store
     */
    public static boolean isValidWelcomeMessage(String welcomeMessage) {
        String normalizedMessage = normalizeWelcomeMessage(welcomeMessage);
        if (normalizedMessage == null || normalizedMessage.isEmpty()) {
            return false;
        }

        String[] lines = normalizedMessage.split("\n", -1);
        if (lines.length > MAX_WELCOME_LINES) {
            return false;
        }
        for (String line : lines) {
            if (line.isBlank()) {
                return false;
            }
        }

        if (normalizedMessage.codePointCount(0, normalizedMessage.length()) > MAX_WELCOME_CODE_POINTS) {
            return false;
        }

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
