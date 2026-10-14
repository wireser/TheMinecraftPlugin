package com.wireser.minecraft.utils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared text and display helpers used by TMP.
 *
 * <p>The class intentionally contains several small presentation-oriented helpers
 * that predate the current module structure. Non-obvious legacy conventions are
 * documented at the method that owns them so their behavior remains discoverable.</p>
 */
public final class Strings {

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Map<String, Long> TIME_UNITS = Map.ofEntries(
            Map.entry("y", 31_536_000L),
            Map.entry("year", 31_536_000L),
            Map.entry("years", 31_536_000L),
            Map.entry("mm", 2_592_000L),
            Map.entry("month", 2_592_000L),
            Map.entry("months", 2_592_000L),
            Map.entry("d", 86_400L),
            Map.entry("day", 86_400L),
            Map.entry("days", 86_400L),
            Map.entry("h", 3_600L),
            Map.entry("hour", 3_600L),
            Map.entry("hours", 3_600L),
            Map.entry("m", 60L),
            Map.entry("minute", 60L),
            Map.entry("minutes", 60L),
            Map.entry("s", 1L),
            Map.entry("second", 1L),
            Map.entry("seconds", 1L)
    );

    private Strings() {
        // Utility class.
    }

    /**
     * Cleans player-provided text for uses such as chat, mail, warp descriptions,
     * and player-created names.
     *
     * <p>All-uppercase text is normalized to lowercase, unsupported characters
     * are removed, and runs of editing characters are limited to three.</p>
     *
     * @param input raw player input
     * @return sanitized text, or {@code null} if no valid text remains
     */
    public static String sanitizeString(String input) {
        if (!Validator.checkString(input)) {
            return null;
        }

        if (input.equals(input.toUpperCase(Locale.ROOT))) {
            input = input.toLowerCase(Locale.ROOT);
        }

        StringBuilder text = new StringBuilder();
        int editingRun = 0;

        for (int i = 0; i < input.length(); i++) {
            String character = String.valueOf(input.charAt(i));

            if (Validator.isOnlyEditingChars(character)) {
                editingRun++;

                if (editingRun <= 3) {
                    text.append(character);
                }

                continue;
            }

            editingRun = 0;

            if (Validator.isAlphanumericAndSpace(character)) {
                text.append(character);
            }
        }

        return text.isEmpty() ? null : text.toString();
    }

    /**
     * Parses a human-readable duration such as {@code "2h 30m"} into seconds.
     *
     * <p>TMP historically stores command-side duration intent in a single integer:
     * {@code Integer.MAX_VALUE} means a permanent duration, while
     * {@code -Integer.MAX_VALUE} means that an existing date/duration should be
     * removed. The aliases {@code forever}/{@code max} and
     * {@code null}/{@code empty}/{@code delete}/{@code remove} preserve that
     * command contract.</p>
     *
     * <p>Months are treated as 30 days and years as 365 days. Invalid values and
     * values that cannot be represented safely as an {@code int} return
     * {@code null} instead of throwing into a command handler.</p>
     *
     * @param text duration expression
     * @return total seconds, a supported sentinel value, or {@code null}
     */
    public static Integer timeTagsToSeconds(String text) {
        if (!Validator.checkString(text)) {
            return null;
        }

        // Positive sentinel: callers interpret this as "permanent / no expiry".
        if (text.equalsIgnoreCase("forever") || text.equalsIgnoreCase("max")) {
            return Integer.MAX_VALUE;
        }

        // Negative sentinel: callers interpret this as "remove the stored date".
        if (text.equalsIgnoreCase("null")
                || text.equalsIgnoreCase("empty")
                || text.equalsIgnoreCase("delete")
                || text.equalsIgnoreCase("remove")) {
            return -Integer.MAX_VALUE;
        }

        boolean negative = text.startsWith("-");

        if (negative) {
            text = text.substring(1);
        }

        if (text.isEmpty()) {
            return null;
        }

        long totalSeconds = 0;

        for (String part : text.split("\\s+")) {
            if (!Validator.isAlphanumeric(part)) {
                return null;
            }

            String unit = part.replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT);
            String value = part.replaceAll("[^0-9]", "");

            if (value.isEmpty() || unit.isEmpty()) {
                return null;
            }

            Long amount = Variables.parseLongOrNull(value);
            Long unitSeconds = TIME_UNITS.get(unit);

            if (amount == null || unitSeconds == null) {
                return null;
            }

            try {
                // Exact arithmetic prevents huge player input from wrapping around
                // before we get a chance to reject it.
                long seconds = Math.multiplyExact(amount, unitSeconds);
                totalSeconds = Math.addExact(totalSeconds, seconds);
            } catch (ArithmeticException exception) {
                return null;
            }

            // The public contract intentionally remains Integer-based because the
            // sentinel values above share the same return type.
            if (totalSeconds > Integer.MAX_VALUE) {
                return null;
            }
        }

        return (int) (negative ? -totalSeconds : totalSeconds);
    }

    /**
     * Formats the difference between now and the supplied date.
     *
     * @param time target date
     * @return human-readable duration
     */
    public static String formatDateDifference(Date time) {
        return formatDateDifference(time, false);
    }

    /**
     * Formats the absolute difference between now and the supplied date.
     *
     * <p>The formatter intentionally discards whether the date is in the past or
     * future; callers use this helper for display text such as remaining/elapsed
     * time and provide that context themselves.</p>
     *
     * @param time target date
     * @param useCommas whether lists of three or more units should use commas
     * @return human-readable duration
     */
    public static String formatDateDifference(Date time, boolean useCommas) {
        long seconds = Duration.between(Instant.now(), time.toInstant()).getSeconds();
        return formatSeconds(seconds, useCommas);
    }

    /**
     * Formats a duration in seconds using days, hours, minutes, and seconds.
     *
     * @param seconds duration in seconds
     * @return human-readable duration
     */
    public static String formatSeconds(int seconds) {
        return formatSeconds((long) seconds, false);
    }

    /**
     * Formats a duration in seconds using days, hours, minutes, and seconds.
     *
     * @param seconds duration in seconds
     * @param useCommas whether lists of three or more units should use commas
     * @return human-readable duration
     */
    public static String formatSeconds(int seconds, boolean useCommas) {
        return formatSeconds((long) seconds, useCommas);
    }

    /**
     * Formats a duration in seconds using days, hours, minutes, and seconds.
     *
     * <p>The sign of a negative duration is intentionally ignored. TMP uses this
     * helper to format a magnitude; surrounding text supplies "ago", "remaining",
     * or other direction-specific wording.</p>
     *
     * @param seconds duration in seconds
     * @return human-readable duration
     */
    public static String formatSeconds(long seconds) {
        return formatSeconds(seconds, false);
    }

    /**
     * Formats a duration in seconds using days, hours, minutes, and seconds.
     *
     * <p>The sign of a negative duration is ignored, matching the historic TMP
     * display behavior.</p>
     *
     * @param seconds duration in seconds
     * @param useCommas whether lists of three or more units should use commas
     * @return human-readable duration
     */
    public static String formatSeconds(long seconds, boolean useCommas) {
        // Math.abs(Long.MIN_VALUE) overflows back to a negative number. Saturating
        // this one impossible-to-abs value keeps the formatter safe and predictable.
        long total = seconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(seconds);

        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        long remainingSeconds = total % 60;

        List<String> parts = new ArrayList<>(4);

        addDurationPart(parts, days, "day");
        addDurationPart(parts, hours, "hour");
        addDurationPart(parts, minutes, "minute");
        addDurationPart(parts, remainingSeconds, "second");

        if (parts.isEmpty()) {
            return "0 seconds";
        }

        if (!useCommas || parts.size() < 3) {
            return String.join(" and ", parts);
        }

        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and "
                + parts.get(parts.size() - 1);
    }

    private static void addDurationPart(List<String> parts, long value, String unit) {
        if (value > 0) {
            parts.add(value + " " + unit + (value == 1 ? "" : "s"));
        }
    }

    /**
     * Converts a value from 1 through 3999 to a Roman numeral.
     *
     * @param input value to convert
     * @return Roman numeral, or {@code null} when outside the supported range
     */
    public static String numberToRoman(int input) {
        if (input < 1 || input > 3999) {
            return null;
        }

        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] literals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

        StringBuilder roman = new StringBuilder();

        for (int i = 0; i < values.length; i++) {
            while (input >= values[i]) {
                input -= values[i];
                roman.append(literals[i]);
            }
        }

        return roman.toString();
    }

    /**
     * Removes one character from the end of a string.
     *
     * @param input input string
     * @return shortened string, or the original value if it cannot be shortened
     */
    public static String removeLastChar(String input) {
        return removeLastChars(1, input);
    }

    /**
     * Removes a number of characters from the end of a string.
     *
     * <p>This remains useful for lightweight display-building code where a loop
     * naturally leaves a trailing delimiter (for example {@code "dog, cat, horse, "}).
     * Invalid removal requests deliberately leave the original value unchanged.</p>
     *
     * @param amount number of characters to remove
     * @param input input string
     * @return shortened string, or the original value when the request is invalid
     */
    public static String removeLastChars(int amount, String input) {
        if (input == null || amount <= 0 || input.length() < amount) {
            return input;
        }

        return input.substring(0, input.length() - amount);
    }

    /**
     * Inserts a marker when adding the next word would exceed the requested width.
     *
     * @param input text to wrap
     * @param max maximum line width
     * @param character marker inserted between wrapped lines
     * @return wrapped text
     */
    public static String breakText(String input, Integer max, String character) {
        if (input == null || max == null || max <= 0 || character == null) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        String[] words = input.split(" ");
        int count = 0;

        for (String word : words) {
            if (count + word.length() > max && count > 0) {
                result.append(character);
                count = 0;
            }

            if (count > 0) {
                result.append(' ');
                count++;
            }

            result.append(word);
            count += word.length();
        }

        return result.toString();
    }

    /**
     * Returns the current local date and time as {@code yyyy-MM-dd HH:mm:ss}.
     *
     * @return formatted current local date and time
     */
    public static String getDate() {
        return LocalDateTime.now().format(DATE_TIME_FORMAT);
    }

    /**
     * Returns the current local date as {@code yyyy-MM-dd}.
     *
     * @return formatted current local date
     */
    public static String getJustDate() {
        return LocalDate.now().format(DATE_FORMAT);
    }

    /**
     * Returns the current minute of the hour.
     *
     * @return minute from 0 through 59
     */
    public static int getJustMinute() {
        return LocalTime.now().getMinute();
    }

    /**
     * Converts enum/material-style Minecraft identifiers into display names.
     *
     * <p>For example, {@code CAVE_SPIDER} becomes {@code Cave Spider}. This is
     * intended for names originating from Minecraft/API identifiers rather than
     * arbitrary natural-language text.</p>
     *
     * @param input identifier to format
     * @return human-readable name, or the original null/empty value
     */
    public static String niceName(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        return capitalizeFirstLetters(
                input.toLowerCase(Locale.ROOT).replace('_', ' ')
        );
    }

    /**
     * Capitalizes the first character of a string.
     *
     * @param input input string
     * @return capitalized string, or the original null/empty value
     */
    public static String capitalizeFirst(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1);
    }

    /**
     * Capitalizes the first character of each whitespace-separated word.
     *
     * <p>Consecutive whitespace is normalized to a single space.</p>
     *
     * @param input input string
     * @return capitalized text, or the original null/empty value
     */
    public static String capitalizeFirstLetters(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String[] words = input.trim().split("\\s+");
        StringBuilder value = new StringBuilder(input.length());

        for (String word : words) {
            if (!value.isEmpty()) {
                value.append(' ');
            }

            value.append(capitalizeFirst(word));
        }

        return value.toString();
    }

    /**
     * Wraps player-controlled text into display lines suitable for item lore.
     *
     * <p>TMP historically stores longer descriptions such as warps and shops in
     * item lore, where a single unbounded line is unreadable. Existing line breaks
     * and the historic TMP {@code @} explicit-break marker are therefore honored.
     * In non-strict mode the wrapper may look ahead by up to two characters for a
     * cleaner word boundary before forcing a split.</p>
     *
     * @param input text to wrap
     * @param maxWidth preferred maximum line width
     * @param strict whether to enforce the width without forward lookahead
     * @return wrapped lines
     */
    public static List<String> wrap(String input, int maxWidth, boolean strict) {
        if (!Validator.checkString(input)) {
            return new ArrayList<>();
        }

        if (maxWidth <= 0) {
            throw new IllegalArgumentException("maxWidth must be greater than 0.");
        }

        List<String> wrapped = new ArrayList<>();

        for (String line : splitIntoLines(input)) {
            wrapLineInto(line, wrapped, maxWidth, strict);
        }

        return wrapped;
    }

    /**
     * Splits text on standard line endings and TMP's historic {@code @} break marker.
     *
     * <p>The {@code @} convention is intentionally application-specific: player
     * descriptions historically used it to request an explicit new lore line.</p>
     *
     * @param input text to split
     * @return lines in encounter order
     */
    public static List<String> splitIntoLines(String input) {
        List<String> lines = new ArrayList<>();

        if (input == null) {
            return lines;
        }

        if (input.isEmpty()) {
            lines.add("");
            return lines;
        }

        // Preserve TMP's long-standing in-band marker for explicit lore breaks.
        String normalized = input.replace('@', '\n');
        int lineStart = 0;

        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);

            if (character == '\r' || character == '\n') {
                lines.add(normalized.substring(lineStart, i));

                if (character == '\r'
                        && i + 1 < normalized.length()
                        && normalized.charAt(i + 1) == '\n') {
                    i++;
                }

                lineStart = i + 1;
            }
        }

        if (lineStart < normalized.length()) {
            lines.add(normalized.substring(lineStart));
        }

        return lines;
    }

    /**
     * Wraps one logical lore line and appends its segments to a destination list.
     *
     * <p>Natural whitespace/hyphen breaks are preferred. When no useful break
     * exists, strict mode forces a split at the limit; non-strict mode first looks
     * up to two characters ahead to avoid ugly splits. Long unbroken words are
     * hyphenated so generated lore cannot grow without bound.</p>
     *
     * @param input line to wrap
     * @param list destination list
     * @param maxWidth preferred maximum line width
     * @param strict whether to enforce the width without forward lookahead
     */
    public static void wrapLineInto(
            String input,
            List<String> list,
            int maxWidth,
            boolean strict
    ) {
        if (input == null || list == null) {
            return;
        }

        if (maxWidth <= 0) {
            throw new IllegalArgumentException("maxWidth must be greater than 0.");
        }

        while (input.length() > maxWidth) {
            int position = findBreakBefore(input, maxWidth);

            if (position >= 0) {
                list.add(input.substring(0, position).trim());
                input = input.substring(position).trim();
                continue;
            }

            if (!strict) {
                int forwardBreak = findBreakAfter(input, maxWidth, 2);

                if (forwardBreak >= 0) {
                    list.add(input.substring(0, forwardBreak).trim());
                    input = input.substring(forwardBreak).trim();
                    continue;
                }
            }

            int split = maxWidth > 3 ? maxWidth - 1 : maxWidth;
            String part = input.substring(0, split);

            list.add(maxWidth > 3 ? part + "-" : part);
            input = input.substring(split).trim();
        }

        if (!input.isEmpty()) {
            list.add(input);
        }
    }

    /**
     * Finds a whitespace or hyphen break at or before the supplied position.
     *
     * @param input text to inspect
     * @param start starting position
     * @return break index, or {@code -1} if none exists
     */
    public static int findBreakBefore(String input, int start) {
        if (input == null || input.isEmpty()) {
            return -1;
        }

        for (int i = Math.min(start, input.length() - 1); i >= 0; i--) {
            char character = input.charAt(i);

            if (Character.isWhitespace(character) || character == '-') {
                return i;
            }
        }

        return -1;
    }

    private static int findBreakAfter(String input, int start, int lookahead) {
        int end = Math.min(input.length() - 1, start + lookahead);

        for (int i = start + 1; i <= end; i++) {
            char character = input.charAt(i);

            if (Character.isWhitespace(character) || character == '-') {
                return i;
            }
        }

        return -1;
    }

}
