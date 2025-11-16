package utils;

import java.math.BigInteger;

public class Variables {

	private static final BigInteger UNSIGNED_BIGINT_MAX = new BigInteger("18446744073709551615");
	
	/**
	 * Attempts to parse a {@code String} into an {@code Integer}.
	 * <p>
	 * If the input is {@code null}, empty, or not a valid integer, this method
	 * returns {@code null} instead of throwing an exception.
	 *
	 * @param number the string representation of the integer
	 * @return the parsed {@code Integer}, or {@code null} if parsing fails
	 */
	public static Integer forceInt(String number) {
		if(!Validator.checkString(number))
	        return null;
		
		try {
			return Integer.parseInt(number);
		} catch(Exception ex) {
			return null;
		}
	}

	/**
	 * Attempts to parse a {@code String} into a {@code Double}.
	 * <p>
	 * If the input is {@code null}, empty, or not a valid double, this method
	 * returns {@code null} instead of throwing an exception.
	 *
	 * @param number the string representation of the double
	 * @return the parsed {@code Double}, or {@code null} if parsing fails
	 */
	public static Double forceDouble(String number) {
		if(!Validator.checkString(number))
	        return null;
		
		try {
			return Double.parseDouble(number);
		} catch(Exception ex) {
			return null;
		}
	}
	
	/**
	 * Attempts to parse a {@code String} into a {@code Float}.
	 * <p>
	 * If the input is {@code null}, empty, or not a valid float, this method
	 * returns {@code null} instead of throwing an exception.
	 *
	 * @param number the string representation of the float
	 * @return the parsed {@code Float}, or {@code null} if parsing fails
	 */
	public static Float forceFloat(String number) {
		if(!Validator.checkString(number))
	        return null;
		
		try {
			return Float.parseFloat(number);
		} catch(Exception ex) {
			return null;
		}
	}
	
	/**
     * Checks whether a number is within a specific range (inclusive).
     *
     * @param number the number being evaluated
     * @param min the minimum allowed value
     * @param max the maximum allowed value
     * @return {@code true} if {@code number} is between {@code min} and {@code max}, otherwise {@code false}
     */
    private static boolean in(long number, long min, long max) {
        return number >= min && number <= max;
    }

    // -------------------------------------------------
    // TINYINT
    // -------------------------------------------------

    /**
     * Determines whether a number fits into a signed TINYINT.
     * Signed range: -128 to 127.
     *
     * @param number the number to check
     * @return {@code true} if the number fits in signed TINYINT, otherwise {@code false}
     */
    public static boolean isTinyInt(int number) {
        return isTinyInt(number, false);
    }

    /**
     * Determines whether a number fits into a TINYINT.
     * Signed range: -128 to 127.
     * Unsigned range: 0 to 255.
     *
     * @param number the number to check
     * @param unsigned {@code true} to evaluate against the unsigned range, {@code false} for signed
     * @return {@code true} if the number fits within the specified TINYINT range, otherwise {@code false}
     */
    public static boolean isTinyInt(int number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 255)
                : in(number, -128, 127);
    }

    // -------------------------------------------------
    // SMALLINT
    // -------------------------------------------------

    /**
     * Determines whether a number fits into a signed SMALLINT.
     * Signed range: -32,768 to 32,767.
     *
     * @param number the number to check
     * @return {@code true} if the number fits in signed SMALLINT, otherwise {@code false}
     */
    public static boolean isSmallInt(int number) {
        return isSmallInt(number, false);
    }

    /**
     * Determines whether a number fits into a SMALLINT.
     * Signed range: -32,768 to 32,767.
     * Unsigned range: 0 to 65,535.
     *
     * @param number the number to check
     * @param unsigned {@code true} to evaluate against the unsigned range, {@code false} for signed
     * @return {@code true} if the number fits within the specified SMALLINT range, otherwise {@code false}
     */
    public static boolean isSmallInt(int number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 65535)
                : in(number, -32768, 32767);
    }

    // -------------------------------------------------
    // MEDIUMINT
    // -------------------------------------------------

    /**
     * Determines whether a number fits into a signed MEDIUMINT.
     * Signed range: -8,388,608 to 8,388,607.
     *
     * @param number the number to check
     * @return {@code true} if the number fits in signed MEDIUMINT, otherwise {@code false}
     */
    public static boolean isMediumInt(int number) {
        return isMediumInt(number, false);
    }

    /**
     * Determines whether a number fits into a MEDIUMINT.
     * Signed range: -8,388,608 to 8,388,607.
     * Unsigned range: 0 to 16,777,215.
     *
     * @param number the number to check
     * @param unsigned {@code true} to evaluate against the unsigned range, {@code false} for signed
     * @return {@code true} if the number fits within the specified MEDIUMINT range, otherwise {@code false}
     */
    public static boolean isMediumInt(int number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 16777215)
                : in(number, -8388608, 8388607);
    }

    // -------------------------------------------------
    // INT
    // -------------------------------------------------

    /**
     * Determines whether a number fits into a signed INT.
     * Signed range: Integer.MIN_VALUE to Integer.MAX_VALUE.
     *
     * @param number the number to check
     * @return {@code true} if the number fits in signed INT, otherwise {@code false}
     */
    public static boolean isInt(long number) {
        return isInt(number, false);
    }

    /**
     * Determines whether a number fits into an INT.
     * Signed range: Integer.MIN_VALUE to Integer.MAX_VALUE.
     * Unsigned range: 0 to 4,294,967,295.
     *
     * @param number the number to check
     * @param unsigned {@code true} to evaluate against the unsigned range, {@code false} for signed
     * @return {@code true} if the number fits within the specified INT range, otherwise {@code false}
     */
    public static boolean isInt(long number, boolean unsigned) {
        return unsigned
                ? in(number, 0, 4294967295L)
                : in(number, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // -------------------------------------------------
    // BIGINT
    // -------------------------------------------------

    /**
     * Determines whether a number fits into a signed BIGINT.
     * Signed BIGINT supports any value representable by Java's {@code long}.
     *
     * @param number the number to check
     * @return always {@code true} for signed BIGINT, since {@code long} fully represents signed BIGINT
     */
    public static boolean isBigInt(BigInteger number) {
        return isBigInt(number, false);
    }

    /**
     * Determines whether a number fits into a BIGINT.
     * Signed BIGINT supports any value representable by Java's {@code long}.
     * Unsigned BIGINT supports 0 to 18,446,744,073,709,551,615.
     *
     * @param number the number to check
     * @param unsigned {@code true} to evaluate against the unsigned range, {@code false} for signed
     * @return {@code true} if signed (always), or if {@code number} fits the unsigned BIGINT range
     */
    public static boolean isBigInt(BigInteger number, boolean unsigned) {
        if (!unsigned) {
            // signed BIGINT fits any long -> always true
            return true;
        }

        // unsigned BIGINT: 0 to 2^64 - 1
        return number.compareTo(BigInteger.ZERO) >= 0 &&
               number.compareTo(UNSIGNED_BIGINT_MAX) <= 0;
    }
	
}
