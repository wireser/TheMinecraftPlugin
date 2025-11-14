package utils;

import java.util.regex.Pattern;

public class Validator {

	// ===================== Patterns =====================

	// Base
	private static final Pattern NUMERIC                     = Pattern.compile("\\d+");
	private static final Pattern ALPHABETIC                  = Pattern.compile("[A-Za-z]+");
	private static final Pattern EXTENDED_ALPHABETIC         = Pattern.compile("\\p{L}+");
	private static final Pattern ALPHANUMERIC                = Pattern.compile("[A-Za-z0-9]+");
	private static final Pattern ALPHANUMERIC_SPACE          = Pattern.compile("[A-Za-z0-9 ]+");
	private static final Pattern EXTENDED_ALPHANUMERIC       = Pattern.compile("[\\p{L}\\p{Nd}]+");
	private static final Pattern EXTENDED_ALPHANUMERIC_SPACE = Pattern.compile("[\\p{L}\\p{Nd} ]+");
	private static final Pattern ONLY_EDITING_CHARS 		 = Pattern.compile("^[,?;\\.:\\-_\\$\\|\\[\\]\\{\\}@&#<>€+\"!%/=()]+$");

	// ID: 1–9 digits, 0–999,999,999
	private static final Pattern ID                          = Pattern.compile("\\d{1,9}");

	// Alphanumeric + space with lengths
	private static final Pattern STRING16                    = Pattern.compile("[A-Za-z0-9 ]{1,16}");
	private static final Pattern STRING32                    = Pattern.compile("[A-Za-z0-9 ]{1,32}");
	private static final Pattern STRING64                    = Pattern.compile("[A-Za-z0-9 ]{1,64}");
	private static final Pattern STRING128                   = Pattern.compile("[A-Za-z0-9 ]{1,128}");

	// Minecraft Username: 3–16 chars, letters/numbers/underscore, cannot start with underscore
	private static final Pattern MC_USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_]{2,15}$");

	
	// ===================== Validator helper =====================
	private static boolean validateString(String input, Pattern pattern) {
	    return input != null && pattern.matcher(input).matches();
	}


	// ===================== Methods =====================

	// Base
	public static boolean isNumeric(String input) {
	    return validateString(input, NUMERIC);
	}

	public static boolean isAlphabetic(String input) {
	    return validateString(input, ALPHABETIC);
	}

	public static boolean isExtendedAlphabetic(String input) {
	    return validateString(input, EXTENDED_ALPHABETIC);
	}

	public static boolean isAlphanumeric(String input) {
	    return validateString(input, ALPHANUMERIC);
	}
	
	public static boolean isAlphanumericAndSpace(String input) {
	    return validateString(input, ALPHANUMERIC_SPACE);
	}

	public static boolean isExtendedAlphanumeric(String input) {
	    return validateString(input, EXTENDED_ALPHANUMERIC);
	}

	public static boolean isExtendedAlphanumericAndSpace(String input) {
	    return validateString(input, EXTENDED_ALPHANUMERIC_SPACE);
	}
	
	public static boolean isOnlyEditingChars(String input) {
		return validateString(input, ONLY_EDITING_CHARS);
    }


	// ID
	public static boolean isID(String input) {
	    return validateString(input, ID);
	}


	/**
	 * Validates a Minecraft username according to the official rules:
	 * <ul>
	 *     <li>Length must be between 3 and 16 characters.</li>
	 *     <li>Allowed characters: letters (A–Z, a–z), digits (0–9), and underscores (_).</li>
	 *     <li>Usernames are case-insensitive.</li>
	 *     <li>The first character must not be an underscore.</li>
	 * </ul>
	 *
	 * @param input the username to validate
	 * @return {@code true} if the username is valid, otherwise {@code false}
	 */
	public static boolean isValidMinecraftUsername(String input) {
	    return validateString(input, MC_USERNAME);
	}
	

	// String16 / 32 / 64 / 128
	public static boolean isString16(String input) {
	    return validateString(input, STRING16);
	}

	public static boolean isString32(String input) {
	    return validateString(input, STRING32);
	}

	public static boolean isString64(String input) {
	    return validateString(input, STRING64);
	}

	public static boolean isString128(String input) {
	    return validateString(input, STRING128);
	}
	
	/* ************************************************************************************************************** */
	/* ************************************************************************************************************** */
	/*													VALIDITY													  */
	/* ************************************************************************************************************** */
	/* ************************************************************************************************************** */
	
	public static boolean checkString(String input) {
		return validateString(input) && between(input, 1, 2048);
	}
	
	public static boolean validateString(String input) {
		return input != null && input.isEmpty() == false;
	}
	
	public static boolean isNotNull(String input) {
		return input != null;
	}
	
	public static boolean between(String input, int min, int max) {
	    return input != null && input.length() >= min && input.length() <= max;
	}
	
}
