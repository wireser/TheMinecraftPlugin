package com.wireser.minecraft.utils;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Converts and formats date-time values returned by the persistence layer.
 *
 * <p>Database drivers may expose SQL date-time columns as different Java
 * representations depending on the query path or JDBC implementation. This
 * utility normalizes the representations supported by TheMinecraftPlugin into
 * {@link LocalDateTime} before they are used by domain or presentation code.</p>
 *
 * <p>Nanosecond precision is discarded because the plugin's persistent
 * date-time values are treated with second-level precision.</p>
 */
public final class DateTimeUtils {

    private DateTimeUtils() {
        throw new UnsupportedOperationException(
                "DateTimeUtils cannot be instantiated."
        );
    }

    /**
     * Converts a nullable database date-time value into a
     * {@link LocalDateTime}.
     *
     * <p>The following representations are supported:</p>
     *
     * <ul>
     *     <li>{@link LocalDateTime}</li>
     *     <li>{@link Timestamp}</li>
     *     <li>text in standard SQL or ISO local date-time form</li>
     * </ul>
     *
     * <p>SQL text using a space between the date and time is normalized to the
     * ISO {@code T} separator before parsing.</p>
     *
     * @param value database value, or {@code null}
     * @return normalized date-time without nanoseconds, or {@code null}
     * @throws DateTimeException if a textual value cannot be parsed
     * @throws IllegalArgumentException if the value type is unsupported
     */
    public static LocalDateTime readNullableDateTime(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof LocalDateTime dateTime) {
            return dateTime.withNano(0);
        }

        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().withNano(0);
        }

        if (value instanceof CharSequence text) {
            return LocalDateTime.parse(
                    text.toString().replace(' ', 'T')
            ).withNano(0);
        }

        throw new IllegalArgumentException(
                "Unsupported date-time value type: "
                        + value.getClass().getName()
        );
    }

    /**
     * Formats a database date-time value for display.
     *
     * <p>{@code null} values return the supplied fallback. Malformed or
     * unsupported non-null values are returned using their original textual
     * representation so diagnostic information is not lost.</p>
     *
     * @param value database date-time value
     * @param formatter formatter used for valid date-time values
     * @param fallback value returned when the database value is {@code null}
     * @return formatted date-time, fallback, or original malformed value
     */
    public static String formatDatabaseDate(Object value, DateTimeFormatter formatter, String fallback) {
        if (value == null) {
            return fallback;
        }

        try {
            LocalDateTime dateTime = readNullableDateTime(value);

            return dateTime == null
                    ? fallback
                    : dateTime.format(formatter);
        } catch (DateTimeException | IllegalArgumentException exception) {
            return String.valueOf(value);
        }
    }
    
}
