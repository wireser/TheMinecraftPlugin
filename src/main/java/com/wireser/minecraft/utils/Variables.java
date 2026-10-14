package com.wireser.minecraft.utils;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Utility methods for parsing numeric input and validating MySQL integer ranges.
 *
 * <p>Parsing methods return {@code null} when a value cannot be converted,
 * allowing callers such as command handlers to handle invalid user input
 * without repeating exception-handling code.</p>
 *
 * <p>The {@code fits...} methods validate whether a numeric value can be stored
 * in the corresponding MySQL integer type. The overloads default to the signed
 * range unless {@code unsigned} is explicitly requested.</p>
 */
public final class Variables {

    private static final BigInteger SIGNED_BIGINT_MIN =
            BigInteger.valueOf(Long.MIN_VALUE);

    private static final BigInteger SIGNED_BIGINT_MAX =
            BigInteger.valueOf(Long.MAX_VALUE);

    private static final BigInteger UNSIGNED_BIGINT_MAX =
            new BigInteger("18446744073709551615");

    private Variables() {
        // Utility class.
    }

    /**
     * Attempts to parse a string as a {@link Byte}.
     *
     * @param number numeric string to parse
     * @return parsed value, or {@code null} if the input is null, empty,
     *         or not a valid byte
     */
    public static Byte parseByteOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            return Byte.parseByte(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as a {@link Short}.
     *
     * @param number numeric string to parse
     * @return parsed value, or {@code null} if the input is null, empty,
     *         or not a valid short
     */
    public static Short parseShortOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            return Short.parseShort(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as an {@link Integer}.
     *
     * @param number numeric string to parse
     * @return parsed value, or {@code null} if the input is null, empty,
     *         or not a valid integer
     */
    public static Integer parseIntOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as a {@link Long}.
     *
     * @param number numeric string to parse
     * @return parsed value, or {@code null} if the input is null, empty,
     *         or not a valid long
     */
    public static Long parseLongOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            return Long.parseLong(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as a {@link Float}.
     *
     * <p>Non-finite values such as {@code NaN} and infinity are rejected.</p>
     *
     * @param number numeric string to parse
     * @return parsed finite value, or {@code null} if the input is null,
     *         empty, invalid, or non-finite
     */
    public static Float parseFloatOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            float value = Float.parseFloat(number);
            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as a {@link Double}.
     *
     * <p>Non-finite values such as {@code NaN} and infinity are rejected.</p>
     *
     * @param number numeric string to parse
     * @return parsed finite value, or {@code null} if the input is null,
     *         empty, invalid, or non-finite
     */
    public static Double parseDoubleOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            double value = Double.parseDouble(number);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as a {@link BigInteger}.
     *
     * @param number numeric string to parse
     * @return parsed value, or {@code null} if the input is null, empty,
     *         or not a valid integer
     */
    public static BigInteger parseBigIntegerOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            return new BigInteger(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Attempts to parse a string as a {@link BigDecimal}.
     *
     * <p>This is the preferred parser for exact decimal values such as money,
     * where floating-point rounding should be avoided.</p>
     *
     * @param number numeric string to parse
     * @return parsed value, or {@code null} if the input is null, empty,
     *         or not a valid decimal number
     */
    public static BigDecimal parseBigDecimalOrNull(String number) {
        if (!Validator.checkString(number)) {
            return null;
        }

        try {
            return new BigDecimal(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Checks whether a value falls within an inclusive range.
     *
     * @param number value to check
     * @param min inclusive minimum
     * @param max inclusive maximum
     * @return {@code true} when {@code number} is within the range
     */
    private static boolean in(long number, long min, long max) {
        return number >= min && number <= max;
    }

    /**
     * Checks whether a {@link BigInteger} falls within an inclusive range.
     *
     * @param number value to check
     * @param min inclusive minimum
     * @param max inclusive maximum
     * @return {@code true} when {@code number} is non-null and within the range
     */
    private static boolean in(BigInteger number, BigInteger min, BigInteger max) {
        return number != null
                && number.compareTo(min) >= 0
                && number.compareTo(max) <= 0;
    }

    /**
     * Determines whether a value fits in a signed MySQL {@code TINYINT}.
     *
     * @param number value to check
     * @return {@code true} for values from -128 through 127
     */
    public static boolean fitsTinyInt(int number) {
        return fitsTinyInt(number, false);
    }

    /**
     * Determines whether a value fits in a MySQL {@code TINYINT}.
     *
     * @param number value to check
     * @param unsigned whether to use the unsigned range
     * @return {@code true} when the value fits the selected range:
     *         -128 through 127 signed, or 0 through 255 unsigned
     */
    public static boolean fitsTinyInt(int number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 255)
                : in(number, -128, 127);
    }

    /**
     * Determines whether a value fits in a signed MySQL {@code SMALLINT}.
     *
     * @param number value to check
     * @return {@code true} for values from -32,768 through 32,767
     */
    public static boolean fitsSmallInt(int number) {
        return fitsSmallInt(number, false);
    }

    /**
     * Determines whether a value fits in a MySQL {@code SMALLINT}.
     *
     * @param number value to check
     * @param unsigned whether to use the unsigned range
     * @return {@code true} when the value fits the selected range:
     *         -32,768 through 32,767 signed, or 0 through 65,535 unsigned
     */
    public static boolean fitsSmallInt(int number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 65_535)
                : in(number, -32_768, 32_767);
    }

    /**
     * Determines whether a value fits in a signed MySQL {@code MEDIUMINT}.
     *
     * @param number value to check
     * @return {@code true} for values from -8,388,608 through 8,388,607
     */
    public static boolean fitsMediumInt(int number) {
        return fitsMediumInt(number, false);
    }

    /**
     * Determines whether a value fits in a MySQL {@code MEDIUMINT}.
     *
     * @param number value to check
     * @param unsigned whether to use the unsigned range
     * @return {@code true} when the value fits the selected range:
     *         -8,388,608 through 8,388,607 signed, or
     *         0 through 16,777,215 unsigned
     */
    public static boolean fitsMediumInt(int number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 16_777_215)
                : in(number, -8_388_608, 8_388_607);
    }

    /**
     * Determines whether a value fits in a signed MySQL {@code INT}.
     *
     * @param number value to check
     * @return {@code true} for values from -2,147,483,648 through 2,147,483,647
     */
    public static boolean fitsInt(long number) {
        return fitsInt(number, false);
    }

    /**
     * Determines whether a value fits in a MySQL {@code INT}.
     *
     * @param number value to check
     * @param unsigned whether to use the unsigned range
     * @return {@code true} when the value fits the selected range:
     *         -2,147,483,648 through 2,147,483,647 signed, or
     *         0 through 4,294,967,295 unsigned
     */
    public static boolean fitsInt(long number, boolean unsigned) {
        return unsigned
                ? in(number, 0L, 4_294_967_295L)
                : in(number, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Determines whether a value fits in a signed MySQL {@code BIGINT}.
     *
     * @param number value to check
     * @return {@code true} for values from -9,223,372,036,854,775,808 through
     *         9,223,372,036,854,775,807
     */
    public static boolean fitsBigInt(BigInteger number) {
        return fitsBigInt(number, false);
    }

    /**
     * Determines whether a value fits in a MySQL {@code BIGINT}.
     *
     * @param number value to check
     * @param unsigned whether to use the unsigned range
     * @return {@code true} when the non-null value fits the selected range:
     *         -9,223,372,036,854,775,808 through
     *         9,223,372,036,854,775,807 signed, or
     *         0 through 18,446,744,073,709,551,615 unsigned
     */
    public static boolean fitsBigInt(BigInteger number, boolean unsigned) {
        return unsigned
                ? in(number, BigInteger.ZERO, UNSIGNED_BIGINT_MAX)
                : in(number, SIGNED_BIGINT_MIN, SIGNED_BIGINT_MAX);
    }

}
