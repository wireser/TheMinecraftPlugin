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
 * Utility methods for text sanitizing, duration parsing and formatting,
 * date formatting, capitalization, and line wrapping.
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
     * Parses a human-readable duration into seconds.
     *
     * <p>Supported units are years ({@code y}, {@code year}, {@code years}),
     * months ({@code mm}, {@code month}, {@code months}), days, hours, minutes,
     * and seconds. Multiple values may be separated by whitespace, for example
     * {@code "2h 30m"}.</p>
     *
     * <p>{@code forever} and {@code max} return {@link Integer#MAX_VALUE}.
     * {@code null}, {@code empty}, {@code delete}, and {@code remove} return
     * {@code -Integer.MAX_VALUE}. A leading minus sign negates a normal parsed
     * duration.</p>
     *
     * <p>Months are defined as 30 days and years as 365 days. Invalid syntax,
     * unknown units, numeric overflow, and totals outside the {@code int} range
     * return {@code null}.</p>
     *
     * @param text duration expression to parse
     * @return total seconds, a sentinel value, or {@code null} if parsing fails
     */
    public static Integer timeTagsToSeconds(String text) {
        if (!Validator.checkString(text)) {
            return null;
        }

        // Integer.MAX_VALUE represents a permanent duration.
        if (text.equalsIgnoreCase("forever") || text.equalsIgnoreCase("max")) {
            return Integer.MAX_VALUE;
        }

        // -Integer.MAX_VALUE represents removal of an existing duration/date.
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
                // Reject multiplication/addition overflow instead of allowing wraparound.
                long seconds = Math.multiplyExact(amount, unitSeconds);
                totalSeconds = Math.addExact(totalSeconds, seconds);
            } catch (ArithmeticException exception) {
                return null;
            }

            // Parsed durations and sentinel values share the Integer return type.
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
     * Formats the absolute difference between the current time and a target date.
     *
     * <p>The result contains days, hours, minutes, and seconds. The direction of
     * the difference is not included in the returned text.</p>
     *
     * @param time target date; must not be {@code null}
     * @param useCommas whether lists of three or more units should use commas
     * @return human-readable absolute duration
     * @throws NullPointerException if {@code time} is {@code null}
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
     * <p>Negative values are formatted using their absolute magnitude.</p>
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
     * <p>Zero-valued units are omitted. Negative values are formatted using their
     * absolute magnitude. With comma formatting enabled, three or more units are
     * rendered as {@code "A, B and C"}; otherwise units are joined with
     * {@code " and "}.</p>
     *
     * @param seconds duration in seconds
     * @param useCommas whether lists of three or more units should use commas
     * @return human-readable duration
     */
    public static String formatSeconds(long seconds, boolean useCommas) {
        // Math.abs(Long.MIN_VALUE) overflows, so use the largest representable magnitude.
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
     * <p>If {@code input} is {@code null}, {@code amount <= 0}, or the string is
     * shorter than {@code amount}, the original value is returned unchanged.</p>
     *
     * @param amount number of trailing characters to remove
     * @param input input string
     * @return shortened string, or the original value when removal is not possible
     */
    public static String removeLastChars(int amount, String input) {
        if (input == null || amount <= 0 || input.length() < amount) {
            return input;
        }

        return input.substring(0, input.length() - amount);
    }

    /**
     * Wraps text at word boundaries by inserting a separator between lines.
     *
     * <p>Words are kept intact. When adding the next word would make the current
     * line exceed {@code max}, {@code character} is inserted before that word.
     * A single word longer than {@code max} is not split.</p>
     *
     * <p>If {@code input}, {@code max}, or {@code character} is invalid
     * ({@code input == null}, {@code max == null}, {@code max <= 0}, or
     * {@code character == null}), the original input is returned unchanged.</p>
     *
     * @param input text to wrap
     * @param max maximum line width before inserting a separator
     * @param character separator inserted between wrapped lines
     * @return wrapped text, or the original input when parameters are invalid
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
     * Converts an uppercase underscore-separated identifier into a display name.
     *
     * <p>The input is converted to lowercase, underscores are replaced with spaces,
     * and the first letter of each word is capitalized. For example,
     * {@code CAVE_SPIDER} becomes {@code Cave Spider}.</p>
     *
     * @param input identifier to format
     * @return formatted name, or the original value if {@code null} or empty
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
     * Wraps text into multiple display lines.
     *
     * <p>Existing line breaks and {@code @} characters are treated as explicit
     * line breaks before wrapping. Natural breaks use whitespace or hyphens.
     * In non-strict mode the method may look ahead by up to two characters past
     * {@code maxWidth} for a cleaner break. If no break is available, long words
     * are split and hyphenated when possible.</p>
     *
     * @param input text to wrap
     * @param maxWidth preferred maximum line width; must be greater than 0
     * @param strict whether to disable forward lookahead past {@code maxWidth}
     * @return wrapped lines, or an empty list when {@code input} is invalid
     * @throws IllegalArgumentException if {@code maxWidth <= 0}
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
     * Splits text into lines.
     *
     * <p>Recognizes {@code \n}, {@code \r}, {@code \r\n}, and {@code @} as line
     * separators. A {@code null} input returns an empty list. An empty string returns
     * a list containing one empty string.</p>
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

        // Treat '@' as an explicit in-band line separator.
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
     * Wraps one logical line and appends the resulting segments to a list.
     *
     * <p>Whitespace and hyphens are preferred break points. If no break exists at
     * or before {@code maxWidth}, non-strict mode searches up to two characters
     * beyond the limit. Otherwise the line is force-split; segments longer than
     * three characters are hyphenated at the split point.</p>
     *
     * <p>If {@code input} or {@code list} is {@code null}, the method returns
     * without modifying the destination list.</p>
     *
     * @param input line to wrap
     * @param list destination list
     * @param maxWidth preferred maximum line width; must be greater than 0
     * @param strict whether to disable forward lookahead past {@code maxWidth}
     * @throws IllegalArgumentException if {@code maxWidth <= 0}
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
     * Finds the nearest whitespace or hyphen at or before a position.
     *
     * <p>If {@code start} is beyond the end of the string, searching begins at the
     * final character. {@code null} and empty input return {@code -1}.</p>
     *
     * @param input text to inspect
     * @param start starting position
     * @return index of a valid break character, or {@code -1} if none exists
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
