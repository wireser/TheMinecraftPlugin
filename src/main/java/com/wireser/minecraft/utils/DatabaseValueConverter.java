package com.wireser.minecraft.utils;

/**
 * Converts loosely typed values returned by the database layer into the
 * primitive representations expected by plugin code.
 *
 * <p>This class exists at the JDBC boundary where numeric and boolean SQL
 * values may be exposed through different Java wrapper types depending on the
 * driver and query path.</p>
 */
public final class DatabaseValueConverter {

    private DatabaseValueConverter() {}

    /**
     * Converts a numeric database value to an {@code int}.
     *
     * @param value database value
     * @return numeric value as an int, or {@code 0} when {@code null}
     * @throws IllegalArgumentException if the value is non-null and not numeric
     */
    public static int asInt(Object value) {
        if (value == null) {
            return 0;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        throw new IllegalArgumentException(
                "Expected numeric database value but got "
                        + value.getClass().getName()
        );
    }

    /**
     * Converts a nullable database value to a boolean.
     *
     * <p>Boolean values are returned directly. Numeric values use zero as
     * {@code false} and any non-zero value as {@code true}.</p>
     *
     * @param value database value
     * @return converted boolean, or {@code null} when the value is null
     * @throws IllegalArgumentException if the value type is unsupported
     */
    public static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof Number number) {
            return number.intValue() != 0;
        }

        throw new IllegalArgumentException(
                "Expected boolean-compatible database value but got "
                        + value.getClass().getName()
        );
    }

}
