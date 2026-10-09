package com.wireser.minecraft.utils;

import java.util.Arrays;
import java.util.Locale;

/**
 * Shared helpers for normalizing command labels and reconstructing command
 * argument tails.
 *
 * <p>These operations are deliberately kept independent of Bukkit command
 * objects so they can be reused by the plugin's command registry, core
 * commands and feature modules.</p>
 */
public final class CommandUtils {

    private CommandUtils() {
        throw new UnsupportedOperationException(
                "CommandUtils cannot be instantiated."
        );
    }

    /**
     * Joins every command argument beginning at the supplied index.
     *
     * <p>Arguments are separated by a single space. Invalid input returns an
     * empty string.</p>
     *
     * @param arguments command arguments
     * @param firstIndex index of the first argument to include
     * @return joined argument tail, or an empty string when no valid tail exists
     */
    public static String joinArguments(String[] arguments, int firstIndex) {
        if (arguments == null
                || firstIndex < 0
                || firstIndex >= arguments.length) {
            return "";
        }

        return String.join(
                " ",
                Arrays.copyOfRange(arguments, firstIndex, arguments.length)
        ).trim();
    }

    /**
     * Normalizes a command label for case-insensitive lookup.
     *
     * <p>Surrounding whitespace and an optional leading slash are removed, and
     * the remaining label is converted to lowercase using
     * {@link Locale#ROOT}.</p>
     *
     * @param label command label
     * @return normalized label, or an empty string for {@code null}
     */
    public static String normalizeCommandLabel(String label) {
        if (label == null) {
            return "";
        }

        String normalized = label.trim().toLowerCase(Locale.ROOT);

        return normalized.startsWith("/")
                ? normalized.substring(1)
                : normalized;
    }

}
