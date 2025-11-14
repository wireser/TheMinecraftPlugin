package utils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Utility container for common String manipulation and formatting helpers.
 *
 * <p>This class provides a broad collection of static methods used across the
 * plugin for:
 * <ul>
 *   <li>Sanitizing and validating chat/user input</li>
 *   <li>Wrapping text into width-limited lines</li>
 *   <li>Capitalization helpers</li>
 *   <li>Date/time formatting and parsing</li>
 *   <li>Roman numeral conversion</li>
 *   <li>Concatenating arrays of arguments back into readable sentences</li>
 * </ul>
 *
 * <p><b>Design notes:</b>
 * <ul>
 *   <li>All methods are static; the class is intended purely as a helper library.</li>
 *   <li>No internal state is stored — the class is fully stateless.</li>
 *   <li>Most functions assume non-null input unless otherwise documented. Invalid
 *       input often returns {@code null}, an empty string, or an empty collection,
 *       depending on context.</li>
 *   <li>Some helpers expect the caller to validate array bounds or indexes.</li>
 * </ul>
 *
 * <p>This class typically lives in the plugin’s shared utility package and is safe
 * to use from commands, event listeners, and other logic.</p>
 */
public class Strings {

	/**
	 * Cleans and normalizes user-provided text input.
	 *
	 * <p>This method enforces a simplified, safe chat formatting rule set:
	 * <ul>
	 *   <li>If {@code input} is {@code null}, empty, or rejected by {@link Validator#checkString(String)},
	 *       the method returns {@code null}.</li>
	 *   <li>If the entire input is uppercase (e.g. "HELLO"), it is converted to lowercase
	 *       (e.g. "hello"). Mixed-case text is left unchanged.</li>
	 *   <li>The method walks every character of the string and selectively appends it to a
	 *       {@link StringBuilder} buffer, building the sanitized output.</li>
	 *   <li>Characters considered “editing characters” (as determined by {@link Validator#isOnlyEditingChars(String)})
	 *       are allowed up to a maximum run of 3 in a row; any additional consecutive editing characters
	 *       are silently discarded.</li>
	 *   <li>Characters that are not editing characters reset the editing-run counter. Only
	 *       alphanumeric characters and spaces ({@link Validator#isAlphanumericAndSpace(String)})
	 *       are appended in this category; all other non-editing characters are ignored.</li>
	 * </ul>
	 *
	 * <p><b>Returns:</b>
	 * <ul>
	 *   <li>The sanitized string if at least one valid character remains.</li>
	 *   <li>{@code null} if no acceptable characters survive the filtering process.</li>
	 * </ul>
	 *
	 * <p><b>Typical usage:</b> This is useful for cleaning player chat, command arguments, or
	 * general user text input where repeated punctuation, abusive spacing, emojis, and
	 * unsupported characters should be removed or collapsed.
	 *
	 * @param input the raw user text to sanitize
	 * @return cleaned text, or {@code null} if invalid or empty after processing
	 */
	public static String sanitizeString(String input) {
		if(!Validator.checkString(input))
			return null;
		
		if(input.equals(input.toUpperCase()))
			input = input.toLowerCase();
		
		StringBuilder text = new StringBuilder();
		int special = 0;
		boolean limit = false;
		
		for(int i = 0; i < input.length(); i++) {
			if(!Validator.isOnlyEditingChars(input.charAt(i) + "")) {
				special = 0;
				limit = false;
				
				if(Validator.isAlphanumericAndSpace(input.charAt(i) + ""))
					text.append(input.charAt(i));
			} else {
				special++;
				
				if(special > 3)
					limit = true;
				
				if(!limit)
					text.append(input.charAt(i));
			}
			
		}
		
		if(text == null || text.length() == 0)
			return null;
		
		return text.toString();
	}
	
	/**
	 * Converts human-readable time expressions into a total number of seconds.
	 *
	 * <p>This method parses flexible text-based duration formats such as:
	 * <pre>
	 *   "5m"       → 300 seconds
	 *   "2h 30m"   → 9000 seconds
	 *   "1d 12h"   → 129600 seconds
	 *   "-3h"      → -10800 seconds
	 *   "forever"  → Integer.MAX_VALUE
	 *   "delete"   → -Integer.MAX_VALUE
	 * </pre>
	 *
	 * <p><b>Input handling rules:</b>
	 * <ul>
	 *   <li>If {@code text} is {@code null} or empty, the method returns {@code null}.</li>
	 *   <li>Special keywords:
	 *     <ul>
	 *       <li>{@code "forever"} or {@code "max"} → {@link Integer#MAX_VALUE} (used for permanent dates)</li>
	 *       <li>{@code "null"}, {@code "empty"}, {@code "delete"}, {@code "remove"} → {@code -Integer.MAX_VALUE} (used to delete dates)</li>
	 *     </ul>
	 *   </li>
	 *   <li>If the input begins with {@code '-'}, the final result will be negated.</li>
	 *   <li>The string is split on whitespace into parts, e.g. {@code "5h 10m"} → {@code ["5h","10m"]}.</li>
	 *   <li>Each part must be strictly alphanumeric; otherwise, the method returns {@code null}.</li>
	 *   <li>From each part, digits are extracted as the amount and letters as the time unit:
	 *       e.g. {@code "10days"} → value {@code "10"}, unit {@code "days"}.</li>
	 *   <li>If either the numeric portion or unit portion is missing, parsing fails and returns {@code null}.</li>
	 * </ul>
	 *
	 * <p><b>Unit resolution:</b>
	 * <ul>
	 *   <li>{@code unitToSeconds} is a lookup map that associates unit names (e.g. {@code "s"}, {@code "sec"},
	 *       {@code "m"}, {@code "h"}, {@code "d"}, {@code "week"}, {@code "year"}, etc.) to their corresponding
	 *       number of seconds.</li>
	 *   <li>If an encountered unit is not present in the map, the method returns {@code null}.</li>
	 * </ul>
	 *
	 * <p>The method multiplies each numeric amount by its unit value in seconds and accumulates the total
	 * in a {@code long}. If the resulting sum exceeds {@link Integer#MAX_VALUE}, parsing fails and
	 * {@code null} is returned.</p>
	 *
	 * <p><b>Return value:</b>
	 * <ul>
	 *   <li>The total number of seconds as an {@code Integer}.</li>
	 *   <li>Negative if the input started with {@code '-'}.</li>
	 *   <li>{@code Integer.MAX_VALUE} or {@code -Integer.MAX_VALUE} for special keywords.</li>
	 *   <li>{@code null} if parsing fails or the input format is invalid.</li>
	 * </ul>
	 *
	 * @param text human-readable time expression (e.g. "3h 15m", "-10s", "forever")
	 * @return total duration in seconds, a sentinel value for special keywords, or {@code null} on error
	 */
	public static Integer timeTagsToSeconds(String text) {
		if(text == null || text.isEmpty())
			return null;

		if(text.equalsIgnoreCase("forever") || text.equalsIgnoreCase("max"))
			return Integer.MAX_VALUE;

		if(text.equalsIgnoreCase("null") || text.equalsIgnoreCase("empty") || text.equalsIgnoreCase("delete") || text.equalsIgnoreCase("remove"))
			return -Integer.MAX_VALUE;

		long totalSeconds = 0;
		boolean isNegative = false;
		
		if(text.startsWith("-")) {
			isNegative = true;
			text = text.substring(1);
		}
		
		String[] parts = text.split("\\s+");
		Map<String, Long> unitToSeconds = createUnitToSecondsMap();

		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];

			if(part.isEmpty())
				return null;

			if(!Validator.isAlphanumeric(part))
				return null;

			String unit = part.replaceAll("[^a-zA-Z]", "");
			String value = part.replaceAll("[^0-9]", "");
			unit = unit.toLowerCase();
			
			if(value.isEmpty() || unit.isEmpty())
				return null; // Invalid format: missing value or unit

			if(!Validator.isNumeric(value))
				return null;

			long amount = Long.parseLong(value);
			Long seconds = unitToSeconds.get(unit.toLowerCase());

			if(seconds == null)
				return null;

			totalSeconds += amount * seconds;
		}

		if (totalSeconds > Integer.MAX_VALUE) {
			return null;
		}

		return (int) (isNegative ? -totalSeconds : totalSeconds);
	}

	/**
	 * Builds and returns a lookup table that maps human-readable time units to
	 * their equivalent number of seconds.
	 *
	 * <p>This map is used by {@code timeTagsToSeconds(...)} and accepts multiple
	 * aliases for each unit (singular/plural/abbreviations). All keys are lowercase
	 * and the returned map should be accessed using lowercase unit strings.</p>
	 *
	 * <p><b>Unit definitions:</b>
	 * <ul>
	 *   <li><b>Years</b> ({@code "y"}, {@code "year"}, {@code "years"}) → 31,536,000 seconds
	 *       <br>(365 days × 86,400 seconds)</li>
	 *   <li><b>Months</b> ({@code "mm"}, {@code "month"}, {@code "months"}) → 2,592,000 seconds
	 *       <br>(30 days × 86,400 seconds)</li>
	 *   <li><b>Days</b> ({@code "d"}, {@code "day"}, {@code "days"}) → 86,400 seconds</li>
	 *   <li><b>Hours</b> ({@code "h"}, {@code "hour"}, {@code "hours"}) → 3,600 seconds</li>
	 *   <li><b>Minutes</b> ({@code "m"}, {@code "minute"}, {@code "minutes"}) → 60 seconds</li>
	 *   <li><b>Seconds</b> ({@code "s"}, {@code "second"}, {@code "seconds"}) → 1 second</li>
	 * </ul>
	 *
	 * <p>These definitions are intentionally simplified. For example, all months are
	 * treated as exactly 30 days and all years as 365 days. If your application
	 * needs calendar-accurate durations, this method would need replacing with a
	 * more precise time engine.</p>
	 *
	 * @return a new {@link HashMap} where each supported unit string maps to its
	 *         duration in seconds
	 */
	private static Map<String, Long> createUnitToSecondsMap() {
		Map<String, Long> unitToSeconds = new HashMap<>();
		unitToSeconds.put("y", 31536000L);
		unitToSeconds.put("year", 31536000L);
		unitToSeconds.put("years", 31536000L);
		unitToSeconds.put("mm", 2592000L);
		unitToSeconds.put("month", 2592000L);
		unitToSeconds.put("months", 2592000L);
		unitToSeconds.put("d", 86400L);
		unitToSeconds.put("day", 86400L);
		unitToSeconds.put("days", 86400L);
		unitToSeconds.put("h", 3600L);
		unitToSeconds.put("hour", 3600L);
		unitToSeconds.put("hours", 3600L);
		unitToSeconds.put("m", 60L);
		unitToSeconds.put("minute", 60L);
		unitToSeconds.put("minutes", 60L);
		unitToSeconds.put("s", 1L);
		unitToSeconds.put("second", 1L);
		unitToSeconds.put("seconds", 1L);
		return unitToSeconds;
	}

	/**
	 * Convenience overload for {@link #formatDateDifference(Date, boolean)} that
	 * formats the difference between {@code time} and the current system time using
	 * the default non-comma style.
	 *
	 * <p>This means unit lists are joined only with {@code " and "} instead of
	 * comma-based phrasing. For example:
	 * <pre>
	 *   "1 hour and 20 minutes"
	 *   "3 days and 4 seconds"
	 * </pre>
	 *
	 * @param time the target {@link Date} to compare against the current time
	 * @return a human-readable duration string without comma-style formatting
	 */
	public static String formatDateDifference(Date time) {
	    return formatDateDifference(time, false);
	}
	
	/**
	 * Converts the time difference between "now" and a given {@link Date} into a
	 * human-readable duration string (days, hours, minutes, seconds), optionally
	 * using comma-style formatting.
	 *
	 * <p>This method computes:
	 * <pre>
	 *   differenceInSeconds = when - now
	 * </pre>
	 * A future date results in a positive duration (e.g., "2 days and 4 hours"),
	 * while a past date produces a negative duration (e.g., "-3 hours and 20 seconds").
	 *
	 * <p>The actual text formatting (singular/plural wording, unit ordering, comma
	 * rules, etc.) is deferred to {@code formatSeconds(int, boolean)}, which is
	 * expected to:
	 * <ul>
	 *   <li>Break seconds into days, hours, minutes, and seconds</li>
	 *   <li>Handle negative values</li>
	 *   <li>Render grammatically correct text</li>
	 *   <li>Respect {@code useCommas} for:
	 *       <ul>
	 *         <li>{@code true}  → "x, y and z"</li>
	 *         <li>{@code false} → "x and y and z"</li>
	 *       </ul>
	 *   </li>
	 * </ul>
	 *
	 * <p><b>Examples (assuming formatSeconds behavior):</b>
	 * <ul>
	 *   <li>{@code formatDateDifference(futureDate, false)} → "1 day and 3 hours and 9 minutes"</li>
	 *   <li>{@code formatDateDifference(pastDate, true)} → "-2 hours, 15 minutes and 7 seconds"</li>
	 * </ul>
	 *
	 * @param time       the target {@link Date} to compare to the current system time
	 * @param useCommas  if {@code true}, multi-unit output may include commas before the final "and"
	 * @return a formatted duration string produced by {@code formatSeconds}
	 */
	public static String formatDateDifference(Date time, boolean useCommas) {
	    Instant now = Instant.now();
	    Instant when = time.toInstant();

	    long secondsDifference = Duration.between(now, when).getSeconds();

	    // Use formatSeconds to handle the human-readable output
	    return formatSeconds((int) secondsDifference, useCommas);
	}
	
	/**
	 * Convenience overload of {@link #formatSeconds(int, boolean)} that formats the
	 * given duration using the default non-comma style.
	 *
	 * <p>This produces output joined only with {@code " and "}:
	 * <pre>
	 *   "45 seconds"
	 *   "2 minutes and 5 seconds"
	 *   "3 hours and 20 seconds"
	 * </pre>
	 *
	 * @param seconds total duration in seconds (may be negative)
	 * @return a human-readable duration string without comma-style formatting
	 */
	public static String formatSeconds(int seconds) {
		return formatSeconds(seconds, false);
	}
	
	/**
	 * Converts a duration in seconds into a human-readable text string such as:
	 *
	 * <pre>
	 *   "45 seconds"
	 *   "1 minute and 12 seconds"
	 *   "3 hours and 4 minutes and 9 seconds"
	 *   "1 day, 3 hours and 20 seconds"        (when useCommas = true)
	 * </pre>
	 *
	 * <p>This method breaks the absolute value of {@code seconds} into days,
	 * hours, minutes, and seconds. Only non-zero units are included in the output.
	 * For example, if the duration is less than one minute, only seconds are shown;
	 * if there are no hours or minutes, those units are omitted.
	 *
	 * <p>Formatting behavior:
	 * <ul>
	 *     <li>If {@code seconds == 0}, returns {@code "0 seconds"}.</li>
	 *     <li>If {@code useCommas == false}, units are joined using {@code " and "}
	 *         – e.g. {@code "2 hours and 30 minutes"}.</li>
	 *     <li>If {@code useCommas == true}, the method uses comma-style output for
	 *         lists with 3 or more units:
	 *         <ul>
	 *             <li>{@code "A, B and C"}</li>
	 *             <li>{@code "A, B, C and D"}</li>
	 *         </ul>
	 *         No comma appears before the final {@code "and"}.</li>
	 *     <li>Pluralization is handled automatically:
	 *         {@code "1 second"} vs. {@code "2 seconds"}, etc.</li>
	 *     <li>Negative input values are allowed — the sign is stripped before
	 *         formatting. The caller can interpret negativity if needed.</li>
	 * </ul>
	 *
	 * @param seconds    total duration in seconds (may be negative)
	 * @param useCommas  whether to format multi-unit output using commas
	 * @return a human-readable string describing the duration
	 */
	public static String formatSeconds(int seconds, boolean useCommas) {

		long total = Math.abs((long)seconds);

		long days = total / (24 * 60 * 60);
		long hours = (total % (24 * 60 * 60)) / (60 * 60);
		long minutes = (total % (60 * 60)) / 60;
		long remainingSeconds = total % 60;

		java.util.List<String> parts = new java.util.ArrayList<>(4);

		if(days > 0) {
			parts.add(days + (days == 1 ? " day" : " days"));
		}
		if(hours > 0) {
			parts.add(hours + (hours == 1 ? " hour" : " hours"));
		}
		if(minutes > 0) {
			parts.add(minutes + (minutes == 1 ? " minute" : " minutes"));
		}
		if(remainingSeconds > 0) {
			parts.add(remainingSeconds + (remainingSeconds == 1 ? " second" : " seconds"));
		}

		// If nothing was added (seconds = 0), return "0 seconds"
		if(parts.isEmpty()) {
			return "0 seconds";
		}

		// ─────────────────────────────────────────────────────────────
		// Joining logic:
		//
		// useCommas = false  → "A and B and C"
		// useCommas = true   → "A, B and C"
		//
		// Works with any number of parts (1, 2, 3, 4+)
		// ─────────────────────────────────────────────────────────────
		if(!useCommas) {
			// Original style: everything joined by " and "
			return String.join(" and ", parts);
		}

		// Comma style
		// If only 1 part: just return it
		if(parts.size() == 1) {
			return parts.get(0);
		}

		// If 2 parts: "A and B"
		if(parts.size() == 2) {
			return parts.get(0) + " and " + parts.get(1);
		}

		// 3 or more parts (comma style, but NO comma before the final "and")
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {

			if (i == parts.size() - 1) {
				// last element → just: "and X"
				sb.append("and ").append(parts.get(i));
			}
			else if (i == parts.size() - 2) {
				// second to last → do NOT add a comma
				sb.append(parts.get(i)).append(" ");
			}
			else {
				// all earlier elements → comma + space
				sb.append(parts.get(i)).append(", ");
			}
		}

		return sb.toString();
	}

	/**
	 * Converts a positive integer into its Roman numeral representation.
	 *
	 * <p>This method supports values from {@code 1} to {@code 3999}. Numbers
	 * outside this range return {@code null}, as traditional Roman numerals
	 * do not represent zero or negative values, and the standard form does not
	 * exceed 3999 without overline notation.
	 *
	 * <p>Conversion is performed using a greedy subtraction algorithm:
	 * <ul>
	 *   <li>An ordered list of Roman literal values and symbols is iterated from
	 *       largest to smallest (e.g., 1000 = "M", 900 = "CM", ... , 1 = "I").</li>
	 *   <li>While {@code input} is greater than or equal to the current value,
	 *       that value is subtracted and its Roman literal is appended to the
	 *       output.</li>
	 *   <li>This continues until the number is reduced to zero.</li>
	 * </ul>
	 *
	 * <p><b>Examples:</b>
	 * <ul>
	 *   <li>{@code numberToRoman(1)} → {@code "I"}</li>
	 *   <li>{@code numberToRoman(9)} → {@code "IX"}</li>
	 *   <li>{@code numberToRoman(58)} → {@code "LVIII"}</li>
	 *   <li>{@code numberToRoman(1994)} → {@code "MCMXCIV"}</li>
	 * </ul>
	 *
	 * @param input an integer between 1 and 3999
	 * @return the Roman numeral representation of {@code number}, or {@code null}
	 *         if the input is outside the valid range
	 */
	public static String numberToRoman(int input) {
		if(input < 1 || input > 3999)
			return null;
		
		int[] values = { 1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1 };
		String[] romanLiterals = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};

		StringBuilder roman = new StringBuilder();

		for(int i = 0; i < values.length; i++)
			while(input >= values[i]) {
				input -= values[i];
				roman.append(romanLiterals[i]);
			}

		return roman.toString();
	}

	/**
	 * Convenience overload that removes exactly one character from the end of the
	 * provided string.
	 *
	 * <p>This is equivalent to calling:
	 * <pre>
	 *   removeLastChars(1, input)
	 * </pre>
	 *
	 * <p>If {@code input} is {@code null} or shorter than 1 character, the original
	 * value is returned unchanged.</p>
	 *
	 * @param input the string to trim by one character
	 * @return the string without its final character, or the original string if it
	 *         is too short or {@code null}
	 */
	public static String removeLastChar(String input) {
		return removeLastChars(1, input);
	}
	
	/**
	 * Removes the specified number of characters from the end of a string.
	 *
	 * <p>If {@code input} is {@code null} or its length is less than or equal to
	 * {@code amount}, the original string is returned unchanged. No exceptions are
	 * thrown for negative or oversized removal requests.</p>
	 *
	 * <p><b>Examples:</b>
	 * <ul>
	 *   <li>{@code removeLastChar(1, "Test")} → {@code "Tes"}</li>
	 *   <li>{@code removeLastChar(3, "Hello")} → {@code "He"}</li>
	 *   <li>{@code removeLastChar(10, "Hi")} → {@code "Hi"} (unchanged, too short)</li>
	 *   <li>{@code removeLastChar(2, null)} → {@code null}</li>
	 * </ul>
	 *
	 * @param amount how many characters to remove from the end
	 * @param input the input string
	 * @return a new string with the last {@code amount} characters removed, or the
	 *         original string if it is too short or {@code null}
	 */
	public static String removeLastChars(Integer amount, String input) {
		if(input != null && input.length() > amount)
			input = input.substring(0, input.length() - amount);
		return input;
	}

	/**
	 * Wraps a single string into a one-element string array.
	 *
	 * <p>This is primarily a convenience utility: instead of manually creating
	 * {@code new String[]{value}}, this helper returns an array containing the
	 * provided {@code value} as its only element.</p>
	 *
	 * <p><b>Behavior notes:</b>
	 * <ul>
	 *   <li>{@code value} may be {@code null}; in that case the resulting array
	 *       has one element, {@code null}.</li>
	 *   <li>No trimming, splitting, or validation is performed.</li>
	 * </ul>
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   stringToArray("Hello")  → ["Hello"]
	 *   stringToArray(null)     → [null]
	 * </pre>
	 *
	 * @param input the string to wrap into an array
	 * @return a new {@code String[]} of length 1 containing {@code value}
	 */
	public static String[] stringToArray(String input) {
		return new String[] { input };
	}

	/**
	 * Inserts a break marker into {@code text} whenever adding the next word would
	 * exceed a specified character limit. This is a simple word-wrapping helper
	 * that attempts to avoid breaking individual words.
	 *
	 * <p><b>How it works:</b>
	 * <ul>
	 *   <li>The input string is split on spaces into individual words.</li>
	 *   <li>Words are appended to the output until the next one would cause the
	 *       current line length to exceed {@code max}.</li>
	 *   <li>When that happens, the provided {@code character} (e.g., newline
	 *       {@code "\n"} or any custom delimiter) is inserted, and a new line
	 *       begins.</li>
	 *   <li>Spaces are preserved between words on the same line.</li>
	 * </ul>
	 *
	 * <p><b>Behavior notes:</b>
	 * <ul>
	 *   <li>No hyphenation or word splitting is performed; long words are placed
	 *       as-is on the next line, even if they exceed {@code max}.</li>
	 *   <li>{@code max} is interpreted as the maximum number of characters in a
	 *       line before a break is inserted.</li>
	 *   <li>{@code character} can be any string; common values include {@code "\n"}
	 *       or {@code "<br>"}.</li>
	 * </ul>
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   breakText("Hello world from space", 10, "\n") =>
	 *   "Hello world
	 *    from space"
	 * </pre>
	 *
	 * @param input       the original text to wrap
	 * @param max        the maximum character count allowed before inserting the
	 *                   break marker
	 * @param character  the string to insert when a break occurs
	 * @return the wrapped text, with break markers inserted as needed
	 */
	public static String breakText(String input, Integer max, String character) {
		StringBuilder result = new StringBuilder();
		String[] words = input.split(" ");
		int count = 0;

		for (String word : words) {
			if (count + word.length() > max) {
				result.append(character);
				count = 0;
			}

			if (count > 0) {
				result.append(" ");
				count++;
			}

			result.append(word);
			count += word.length();
		}

		return result.toString();
	}
	
	/**
	 * Translates legacy Minecraft color codes by replacing all {@code '&'} characters
	 * with the section-sign control code {@code '\u00A7'} (also written as {@code '§'}).
	 *
	 * <p>This allows strings such as {@code "&aHello"} to be interpreted by
	 * the Minecraft client as colored text when rendered in chat, GUIs, or item
	 * metadata.
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   color("&6Gold &aText")  → "§6Gold §aText"
	 * </pre>
	 *
	 * <p><b>Behavior notes:</b>
	 * <ul>
	 *   <li>{@code value} may be {@code null}; a {@code NullPointerException} will be
	 *       thrown if so. Callers should check or sanitize input if needed.</li>
	 *   <li>No validation of formatting codes is performed; every {@code '&'}
	 *       character is replaced unconditionally.</li>
	 *   <li>The replacement is performed using a regular expression via
	 *       {@link String#replaceAll(String, String)}.</li>
	 * </ul>
	 *
	 * @param input the raw string containing {@code '&'}-based color codes
	 * @return a new string where all {@code '&'} are replaced with {@code '§'}
	 */
	public static String color(String input) {
		return input.replaceAll("&", "\247");
	}

	/**
	 * Returns the current system date and time formatted as a timestamp string in
	 * the style {@code "YYYY-MM-DD HH:MM:SS"}.
	 *
	 * <p>This method uses {@link java.util.Calendar} to obtain the current year,
	 * month, day, hour (24-hour clock), minute, and second. Each numeric component
	 * is zero-padded to two digits where appropriate (e.g., {@code "09"} instead
	 * of {@code "9"}).</p>
	 *
	 * <p><b>Example output:</b>
	 * <pre>
	 *   "2025-01-07 14:05:03"
	 * </pre>
	 *
	 * <p><b>Implementation notes:</b>
	 * <ul>
	 *   <li>Months are zero-based in {@code Calendar}, so {@code MONTH + 1} is used.</li>
	 *   <li>All date and time components are string-padded manually instead of using
	 *       {@link java.text.SimpleDateFormat} or other formatting utilities.</li>
	 *   <li>Uses the default system time zone.</li>
	 * </ul>
	 *
	 * @return a timestamp string representing the current date and time
	 */
	public static String getDate() {
		Calendar now = Calendar.getInstance();

		String yer = now.get(Calendar.YEAR) + "";
		String mon = (now.get(Calendar.MONTH)+1)  + "";
		String day = now.get(Calendar.DAY_OF_MONTH) + "";
		String hor = now.get(Calendar.HOUR_OF_DAY) + "";
		String min = now.get(Calendar.MINUTE) + "";
		String sec = now.get(Calendar.SECOND) + "";

		if(Integer.parseInt(mon) < 10) mon = "0" + mon;
		if(Integer.parseInt(day) < 10) day = "0" + day;
		if(Integer.parseInt(hor) < 10) hor = "0" + hor;
		if(Integer.parseInt(min) < 10) min = "0" + min;
		if(Integer.parseInt(sec) < 10) sec = "0" + sec;

		return yer + "-" + mon + "-" + day + " " + hor + ":" + min + ":" + sec;
	}

	/**
	 * Returns the current system date formatted as {@code "YYYY-MM-DD"}.
	 *
	 * <p>This method queries the current date using {@link java.util.Calendar} and
	 * manually constructs a zero-padded year-month-day string. For example, if the
	 * current date is March 9, 2025, it will return:
	 * <pre>
	 *   "2025-03-09"
	 * </pre>
	 *
	 * <p><b>Implementation notes:</b>
	 * <ul>
	 *   <li>{@code Calendar.MONTH} is zero-based, so {@code MONTH + 1} is used.</li>
	 *   <li>Month and day fields are padded to two digits; the year is not padded.</li>
	 *   <li>The system default time zone is used.</li>
	 * </ul>
	 *
	 * @return the current date as a {@code "YYYY-MM-DD"} string
	 */
	public static String getJustDate() {
		Calendar now = Calendar.getInstance();

		String yer = now.get(Calendar.YEAR) + "";
		String mon = (now.get(Calendar.MONTH)+1)  + "";
		String day = now.get(Calendar.DAY_OF_MONTH) + "";

		if(Integer.parseInt(mon) < 10) mon = "0" + mon;
		if(Integer.parseInt(day) < 10) day = "0" + day;

		return yer + "-" + mon + "-" + day;
	}

	/**
	 * Retrieves the current minute of the hour using the system's default time zone.
	 *
	 * <p>The value comes from {@link Calendar#MINUTE}, which ranges from {@code 0}
	 * to {@code 59}. No zero-padding or formatting is applied; the raw numeric
	 * minute is returned.</p>
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   // If the current time is 14:07:32
	 *   getJustMinute() → 7
	 * </pre>
	 *
	 * @return the current minute of the hour (0–59)
	 */
	public static int getJustMinute() {
		return Calendar.getInstance().get(Calendar.MINUTE);
	}

	/**
	 * Concatenates all elements of a string array starting from a given index into
	 * a single space-separated string.
	 *
	 * <p>This method is typically used to rebuild multi-word arguments passed to
	 * command handlers, where the first few parameters represent fixed options and
	 * the remaining text should be merged back into one readable string.</p>
	 *
	 * <p><b>How it works:</b>
	 * <ul>
	 *   <li>Iterates from {@code args[start]} to the end of the array.</li>
	 *   <li>Appends each element followed by a space.</li>
	 *   <li>Calls {@code removeLastChar(...)} to remove the final trailing space.</li>
	 * </ul>
	 *
	 * <p><b>Behavior notes:</b>
	 * <ul>
	 *   <li>No validation is performed on {@code start}; values outside the array
	 *       range will cause an {@code ArrayIndexOutOfBoundsException}.</li>
	 *   <li>If {@code start} equals {@code args.length}, the returned string will
	 *       be empty or {@code null} depending on {@code removeLastChar(...)} behavior.</li>
	 *   <li>Does not trim internal spaces or filter empty tokens.</li>
	 * </ul>
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   args = ["give", "player", "Hello", "world!"]
	 *   mergeStrings(args, 2) → "Hello world!"
	 * </pre>
	 *
	 * @param args   the string array to merge from
	 * @param start  the index at which to begin merging
	 * @return a space-joined string built from all elements starting at {@code start}
	 */
	public static String mergeStrings(String args[], int start)
	{
		String value = new String("");
		for(int i = start; i < args.length; i++)
			value = value + args[i] + " ";
		return removeLastChar(value);
	}

	/**
	 * Converts an identifier-style string into a more human-readable name.
	 *
	 * <p>This method performs two formatting steps:
	 * <ul>
	 *   <li>Converts the entire string to lowercase.</li>
	 *   <li>Replaces all underscores ({@code _}) with spaces.</li>
	 * </ul>
	 * Then it calls {@code capitalizeFirst(...)} to capitalize only the first
	 * character of the resulting text, leaving the rest in lowercase.</p>
	 *
	 * <p><b>Example transformations:</b>
	 * <pre>
	 *   "HELLO_WORLD"   → "Hello world"
	 *   "user_NAME"     → "User name"
	 *   "already nice"  → "Already nice"
	 * </pre>
	 *
	 * <p><b>Behavior notes:</b>
	 * <ul>
	 *   <li>Returns the result of {@code capitalizeFirst(...)} directly.</li>
	 *   <li>If {@code input} is {@code null}, the method will throw a
	 *       {@code NullPointerException} because {@code toLowerCase()} is called
	 *       unconditionally.</li>
	 *   <li>Does not trim leading/trailing spaces.</li>
	 * </ul>
	 *
	 * @param input the original string (e.g., a config key, enum name, or identifier)
	 * @return a more readable version where underscores become spaces and the first
	 *         letter is capitalized
	 */
	public static String niceName(String input) {
		return capitalizeFirst(input.toLowerCase().replace("_", " "));
	}

	/**
	 * Capitalizes the very first character of the given string.
	 *
	 * <p><b>Behavior:</b>
	 * <ul>
	 *   <li>If {@code input} is {@code null} or empty, it returns {@code str} unchanged.</li>
	 *   <li>If {@code input} has at least one character, the first character is
	 *       converted to uppercase using default locale rules, and the remainder of
	 *       the string is appended unchanged.</li>
	 *   <li>No trimming or special case handling for whitespace or multibyte
	 *       characters is performed.</li>
	 * </ul>
	 *
	 * <p><b>Examples:</b>
	 * <pre>
	 *   capitalizeFirst("hello")     → "Hello"
	 *   capitalizeFirst("h")         → "H"
	 *   capitalizeFirst("Already")   → "Already"
	 *   capitalizeFirst("")          → ""
	 *   capitalizeFirst(null)        → null
	 * </pre>
	 *
	 * @param input the input string to capitalize
	 * @return the resulting string with its first character uppercased, or the
	 *         original value if {@code null} or empty
	 */
	public static String capitalizeFirst(String input) {
		if(input == null || input.isEmpty())
			return input;
		return input.substring(0, 1).toUpperCase() + input.substring(1);
	}

	/**
	 * Converts the first letter of every space-separated word to uppercase.
	 *
	 * <p>This method is a multi-word equivalent of {@code capitalizeFirst(...)}:
	 * <ul>
	 *   <li>If the input is {@code null} or empty, it is returned unchanged.</li>
	 *   <li>If the input contains no spaces, it falls back to {@code capitalizeFirst(input)}.</li>
	 *   <li>Otherwise, the string is split on spaces, each word has its first character
	 *       converted to uppercase, and they are re-joined with single spaces.</li>
	 * </ul>
	 *
	 * <p><b>Behavior notes:</b>
	 * <ul>
	 *   <li>Words with length 1 are still safe: substring(0,1) is valid and substring(1)
	 *       produces an empty string.</li>
	 *   <li>Multiple consecutive spaces will create empty words when splitting, which
	 *       will cause {@code substring(0,1)} to throw an exception. Input should therefore
	 *       already be normalized or validated.</li>
	 *   <li>Trailing space is removed by calling {@code removeLastChar(...)}.</li>
	 *   <li>No locale-aware title casing is applied—only the first character is uppercased.</li>
	 * </ul>
	 *
	 * <p><b>Examples:</b>
	 * <pre>
	 *   capitalizeFirstLetters("hello world")      → "Hello World"
	 *   capitalizeFirstLetters("java plugin dev")  → "Java Plugin Dev"
	 *   capitalizeFirstLetters("hello")            → "Hello"
	 *   capitalizeFirstLetters("")                 → ""
	 *   capitalizeFirstLetters(null)               → null
	 * </pre>
	 *
	 * @param input the original text to format
	 * @return the input with every word's first character uppercased, or the original
	 *         value if {@code null} or empty
	 */
	public static String capitalizeFirstLetters(String input) {
		String[] words = null;
		StringBuilder value = new StringBuilder();

		if(input == null || input.isEmpty())
			return input;

		if(!input.contains(" "))
			return capitalizeFirst(input);

		words = input.split(" ");

		for(String word : words)
			value.append(word.substring(0, 1).toUpperCase() + word.substring(1) + " ");

		return removeLastChar(value.toString());
	}

	/**
	 * Wraps an input string into multiple lines whose lengths respect a maximum width,
	 * returning the wrapped lines as a list. The input may contain existing line
	 * breaks; each logical line is wrapped independently.
	 *
	 * <p><b>Processing pipeline:</b>
	 * <ol>
	 *   <li>Validates the input via {@link Validator#checkString(String)}. If invalid,
	 *       returns an empty list.</li>
	 *   <li>Splits the input into logical lines using {@code splitIntoLines(input)}.</li>
	 *   <li>For each logical line, delegates to {@link #wrapLineInto(String, ArrayList, int, boolean)}
	 *       which performs the actual width-constrained wrapping and appends the resulting
	 *       physical lines into the output list.</li>
	 * </ol>
	 *
	 * <p><b>Width policy:</b>
	 * Behavior of line breaking is determined by {@code strict} inside
	 * {@code wrapLineInto(...)}:
	 * <ul>
	 *   <li><b>strict = true:</b> favor hard splits near {@code maxWidth} with optional
	 *       hyphen insertion for long words (see {@code wrapLineInto} hyphenation rule).</li>
	 *   <li><b>strict = false:</b> allow a small forward lookahead to break on a cleaner
	 *       boundary (e.g., whitespace or hyphen) just past {@code maxWidth}.</li>
	 * </ul>
	 *
	 * <p><b>Inputs:</b>
	 * <ul>
	 *   <li>{@code input} — raw text to wrap; may contain embedded newlines.</li>
	 *   <li>{@code maxWidth} — maximum characters per physical line (must be &gt; 0 for
	 *       useful results).</li>
	 *   <li>{@code strict} — toggles splitting strategy as described above.</li>
	 * </ul>
	 *
	 * <p><b>Outputs:</b>
	 * <ul>
	 *   <li>A list of wrapped lines in display order.</li>
	 *   <li>Returns an empty list if {@code input} fails validation or splits to no lines.</li>
	 * </ul>
	 *
	 * <p><b>Notes & Assumptions:</b>
	 * <ul>
	 *   <li>Actual breaking logic (natural breaks, forced splits, hyphenation) is defined
	 *       by {@code wrapLineInto(...)} and {@code findBreakBefore(...)}.</li>
	 *   <li>Existing line breaks are preserved as wrap boundaries—each logical line is wrapped separately.</li>
	 * </ul>
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   wrap("Hello brave new world", 10, false)
	 *   → ["Hello brave", "new world"]
	 * </pre>
	 *
	 * @param input     the text to wrap
	 * @param maxWidth  maximum number of characters allowed per output line
	 * @param strict    whether to enforce strict width (hard splits) or allow slight lookahead for nicer breaks
	 * @return a list of wrapped lines; empty list if input is invalid or empty
	 */
	public static List<String> wrap(String input, int maxWidth, boolean strict) {
		if(!Validator.checkString(input))
			return new ArrayList<>();

		List<String> lines = splitIntoLines(input);
		if (lines.isEmpty()) {
			return lines;
		}

		ArrayList<String> strings = new ArrayList<>();
		for(Iterator<String> iter = lines.iterator(); iter.hasNext();) {
			wrapLineInto(iter.next(), strings, maxWidth, strict);
		}
		return strings;
	}

	/**
	 * Splits a text block into individual lines, honoring multiple newline styles and a
	 * custom in-band break marker.
	 *
	 * <p><b>Line break recognition:</b>
	 * <ul>
	 *   <li>Windows: {@code "\r\n"} (carriage return + line feed) — treated as a single break.</li>
	 *   <li>Unix: {@code "\n"} (line feed).</li>
	 *   <li>Classic Mac: {@code "\r"} (carriage return).</li>
	 *   <li>Custom marker: every {@code '@'} encountered is converted to {@code '\n'} on the fly,
	 *       then processed like a normal newline.</li>
	 * </ul>
	 *
	 * <p><b>Algorithm (high level):</b>
	 * <ol>
	 *   <li>If {@code input == null}, returns an empty list.</li>
	 *   <li>If {@code input.length() == 0}, returns a singleton list containing {@code ""}.</li>
	 *   <li>Iterates characters from left to right, maintaining a {@code lineStart} index.</li>
	 *   <li>On {@code '\r'}:
	 *     <ul>
	 *       <li>If followed by {@code '\n'}, treats the pair as one break ({@code "\r\n"}).</li>
	 *       <li>Emits the substring {@code [lineStart, i)} as a line and advances {@code lineStart}
	 *           past the break sequence.</li>
	 *     </ul>
	 *   </li>
	 *   <li>On {@code '\n'}: emits {@code [lineStart, i)} and advances {@code lineStart}.</li>
	 *   <li>On {@code '@'}: immediately replaces the first {@code '@'} in {@code input} with
	 *       {@code '\n'} via {@code replaceFirst}, so that the same iteration pass will treat it as a
	 *       newline on subsequent checks.</li>
	 *   <li>After the loop, if there is a trailing segment (i.e., {@code lineStart < input.length()}),
	 *       emits {@code input.substring(lineStart)} as the final line.</li>
	 * </ol>
	 *
	 * <p><b>Outputs:</b>
	 * <ul>
	 *   <li>A list of lines with all line-terminator characters removed.</li>
	 *   <li>Empty input yields {@code [""]} (one empty line), not an empty list.</li>
	 *   <li>{@code null} input yields an empty list.</li>
	 * </ul>
	 *
	 * <p><b>Notes & caveats:</b>
	 * <ul>
	 *   <li>The method mutates the local {@code input} reference (string is reassigned) when
	 *       encountering {@code '@'} by calling {@code input.replaceFirst("@", "\n")}. Since
	 *       Java strings are immutable, the original caller’s reference is unaffected.</li>
	 *   <li>The replacement of {@code '@'} uses {@code replaceFirst}, which always replaces the
	 *       leftmost remaining {@code '@'} at the time of the call. Because scanning proceeds
	 *       left-to-right and {@code '@'} is a single character replaced by another single
	 *       character, indices remain consistent during iteration.</li>
	 *   <li>CRLF {@code "\r\n"} pairs are treated as a single break; the loop skips the {@code '\n'}
	 *       after handling the {@code '\r'}.</li>
	 * </ul>
	 *
	 * @param input the text to split; may contain {@code \r}, {@code \n}, {@code \r\n}, and/or {@code '@'} markers
	 * @return a list of lines in encounter order; never {@code null}
	 */
	public static List<String> splitIntoLines(String input) {
		ArrayList<String> strings = new ArrayList<>();
		if (input != null) {
			int len = input.length();
			if (len == 0) {
				strings.add("");
				return strings;
			}

			int lineStart = 0;

			for (int i = 0; i < len; ++i) {
				if(input.charAt(i) == '@')
					input = input.replaceFirst("@", "\n");
				
				char c = input.charAt(i);
				
				if (c == '\r') {
					int newlineLength = 1;
					if ((i + 1) < len && input.charAt(i + 1) == '\n') {
						newlineLength = 2;
					}
					strings.add(input.substring(lineStart, i));
					lineStart = i + newlineLength;
					if (newlineLength == 2) // skip \n next time through loop
					{
						++i;
					}
				} else if (c == '\n') {
					strings.add(input.substring(lineStart, i));
					lineStart = i + 1;
				}
			}
			if (lineStart < len) {
				strings.add(input.substring(lineStart));
			}
		}
		return strings;
	}

	/**
	 * Wraps a single logical line of text into multiple physical lines and appends the
	 * wrapped segments to {@code list}, honoring a maximum width and an optional
	 * "strict" width policy.
	 *
	 * <p><b>Behavior overview:</b>
	 * <ul>
	 *   <li>Attempts to break lines at natural boundaries (whitespace or hyphen) at or
	 *       before {@code maxWidth} using {@link #findBreakBefore(String, int)}.</li>
	 *   <li>If no natural break exists before {@code maxWidth}:
	 *     <ul>
	 *       <li><b>strict = true:</b> forces a hard split at exactly {@code maxWidth},
	 *           using a hyphen when the left chunk length would be &gt; 3 characters;
	 *           otherwise splits without a hyphen.</li>
	 *       <li><b>strict = false:</b> scans forward up to 2 characters past
	 *           {@code maxWidth} for a cleaner break (whitespace or hyphen). If none is
	 *           found, falls back to the same forced split logic described above.</li>
	 *     </ul>
	 *   </li>
	 *   <li>Each emitted segment is {@code trim()}-ed to avoid leading spaces on the next line.</li>
	 *   <li>The final remainder (length ≤ {@code maxWidth}) is appended as the last element
	 *       without additional trimming logic beyond what the loop applies.</li>
	 * </ul>
	 *
	 * <p><b>Hyphenation rule:</b>
	 * When a forced split is required (no natural break near {@code maxWidth}),
	 * a hyphen ({@code '-'}) is appended to the left segment <em>only if</em> the split
	 * position implies the left chunk would be longer than 3 characters (i.e.,
	 * split at {@code maxWidth - 1} then add a hyphen). For chunks of length ≤ 3,
	 * the split occurs at {@code maxWidth} with no hyphen.</p>
	 *
	 * <p><b>Whitespace handling:</b>
	 * Emitted segments are trimmed before being added to {@code list}. The new
	 * {@code input} for the next iteration is also trimmed, preventing lines from
	 * starting with a space after a break.</p>
	 *
	 * <p><b>Edge cases and guarantees:</b>
	 * <ul>
	 *   <li>If {@code input.length() ≤ maxWidth}, the method emits {@code input} as a single segment.</li>
	 *   <li>If a natural break exists at or before {@code maxWidth}, the line breaks there.</li>
	 *   <li>If no natural break exists:
	 *     <ul>
	 *       <li><b>strict = true:</b> line length is enforced to {@code maxWidth} per segment
	 *           (except the final segment), subject to the hyphenation rule above.</li>
	 *       <li><b>strict = false:</b> a small forward lookahead (+1..+2) is allowed to improve readability.</li>
	 *     </ul>
	 *   </li>
	 *   <li>Very long unbroken words will be split per the forced-split behavior described above.</li>
	 * </ul>
	 *
	 * <p><b>Examples (maxWidth = 10):</b>
	 * <pre>
	 * input: "Hello brave world"
	 * strict = true  → ["Hello", "brave", "world"]
	 * strict = false → ["Hello", "brave", "world"]   (natural breaks suffice)
	 *
	 * input: "supercalifragilistic"
	 * strict = true  → ["supercali-", "fragilist-", "ic"]
	 * strict = false → same as strict (no spaces to prefer)
	 *
	 * input: "word---dashy"
	 * strict = false, maxWidth = 6
	 *   findBreakBefore breaks at '-' → ["word---", "dashy"] (after trim logic)
	 * </pre>
	 *
	 * @param input     the single logical line to wrap (must be non-{@code null})
	 * @param list      the destination list that receives each wrapped segment in order
	 * @param maxWidth  the maximum number of characters permitted per physical line
	 * @param strict    when {@code true}, enforce exact wrapping near {@code maxWidth}
	 *                  (forced splits with hyphenation rule); when {@code false}, allow
	 *                  a small forward lookahead (up to +2 chars) to prefer natural breaks
	 */
	public static void wrapLineInto(String input, ArrayList<String> list, int maxWidth, boolean strict) {
		int len = input.length();

		while (len > maxWidth) {
			int pos = findBreakBefore(input, maxWidth);

			if (pos == -1) {
				// No whitespace found before the limit.
				if (strict) {
					// Force exact hard split with hyphen if word is long
					if (maxWidth > 3) {
						list.add(input.substring(0, maxWidth - 1) + "-");
						input = input.substring(maxWidth - 1).trim();
					} else {
						// If the chunk would be <=3, just split without hyphen
						list.add(input.substring(0, maxWidth));
						input = input.substring(maxWidth).trim();
					}
				} else {
					// NON–strict mode: try breaking up to 2 chars after maxWidth
					boolean foundForward = false;
					for (int j = maxWidth + 1; j <= maxWidth + 2 && j < input.length(); j++) {
						char c = input.charAt(j);
						if (Character.isWhitespace(c) || c == '-') {
							list.add(input.substring(0, j).trim());
							input = input.substring(j).trim();
							foundForward = true;
							break;
						}
					}

					if (!foundForward) {
						// Still nothing, fallback to forced hyphen split
						if (maxWidth > 3) {
							list.add(input.substring(0, maxWidth - 1) + "-");
							input = input.substring(maxWidth - 1).trim();
						} else {
							list.add(input.substring(0, maxWidth));
							input = input.substring(maxWidth).trim();
						}
					}
				}
			} else {
				// Normal case: break at whitespace/hyphen before limit
				list.add(input.substring(0, pos).trim());
				input = input.substring(pos).trim();
			}

			len = input.length();
		}

		if (len > 0) {
			list.add(input);
		}

	}

	/**
	 * Searches backward from a given index for a natural line-break position.
	 *
	 * <p>This helper is typically used in word-wrapping or text-folding functions.
	 * Starting at {@code start} and moving left, it looks for a character that marks
	 * a safe place to split a line—either any whitespace character or a literal
	 * hyphen {@code '-'}.</p>
	 *
	 * <p><b>How it works:</b>
	 * <ul>
	 *   <li>Begins at index {@code start} and decrements down to {@code 0}.</li>
	 *   <li>Returns the index of the first encountered whitespace or {@code '-'}.</li>
	 *   <li>If nothing suitable is found, returns {@code -1}.</li>
	 * </ul>
	 *
	 * <p><b>Intended use:</b><br>
	 * When implementing manual text wrapping, a caller typically:
	 * <ul>
	 *   <li>First tries {@code findBreakBefore(...)} at {@code maxWidth}.</li>
	 *   <li>If the return value is {@code -1}, the caller may:
	 *       <ul>
	 *         <li>force a break exactly at {@code maxWidth},</li>
	 *         <li>hyphenate a long word manually, or</li>
	 *         <li>if not in strict mode, look forward a few characters for a cleaner break.</li>
	 *       </ul>
	 *   </li>
	 * </ul>
	 *
	 * <p><b>Example:</b>
	 * <pre>
	 *   // input: "Hello world example"
	 *   // searching back from index 10 (the 'd' in "world")
	 *   findBreakBefore(input, 10) → 5   (space before "world")
	 * </pre>
	 *
	 * @param input the text being wrapped
	 * @param start the index to begin scanning backward from
	 * @return the index of a safe break character, or {@code -1} if none found
	 */
	public static int findBreakBefore(String input, int start) {
		for(int i = start; i >= 0; --i) {
			char c = input.charAt(i);
			if (Character.isWhitespace(c) || c == '-') {
				return i;
			}
		}
		
		// No legal break found to the left of 'start' → caller must decide:
		//   - force hyphenation at a safe position
		//   - split at maxWidth exactly
		//   - or scan forward (if not strict) up to +2 chars
		return -1;
	}

}