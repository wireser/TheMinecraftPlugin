package com.wireser.minecraft.utils;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Shared conversion and formatting for SQL date-time values. */
public final class DateTimeUtils {

    private DateTimeUtils() {}

    /** Reads a nullable JDBC timestamp, {@link LocalDateTime}, or SQL text value. */
    public static LocalDateTime readNullableDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime.withNano(0);
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().withNano(0);
        return LocalDateTime.parse(value.toString().replace(' ', 'T')).withNano(0);
    }

    /** Formats a database value or returns {@code fallback} when it is null. */
    public static String formatDatabaseDate(Object value, DateTimeFormatter formatter,
            String fallback) {
        if (value == null) return fallback;

        try {
            LocalDateTime dateTime = readNullableDateTime(value);
            return dateTime == null ? fallback : dateTime.format(formatter);
        } catch (DateTimeException exception) {
            return String.valueOf(value);
        }
    }
}
