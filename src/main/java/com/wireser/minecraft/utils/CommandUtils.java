package com.wireser.minecraft.utils;

import java.util.Arrays;
import java.util.Locale;

/** Small, shared helpers for command labels and argument tails. */
public final class CommandUtils {

    private CommandUtils() {}

    /** Joins every argument from {@code firstIndex}, preserving spaces between words. */
    public static String joinArguments(String[] arguments, int firstIndex) {
        if (arguments == null || firstIndex < 0 || firstIndex >= arguments.length) return "";
        return String.join(" ", Arrays.copyOfRange(arguments, firstIndex, arguments.length)).trim();
    }

    /** Normalizes a command label for lookup, accepting an optional leading slash. */
    public static String normalizeCommandLabel(String label) {
        if (label == null) return "";

        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }
}
