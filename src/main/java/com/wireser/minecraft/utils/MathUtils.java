package com.wireser.minecraft.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared numeric helpers for operations that require explicit plugin-wide
 * rounding or probability rules.
 */
public final class MathUtils {

    private MathUtils() {}

    /**
     * Rounds a value to the requested number of decimal places using
     * {@link RoundingMode#HALF_UP}.
     *
     * @param value value to round
     * @param places number of decimal places, zero or greater
     * @return rounded value
     * @throws IllegalArgumentException if {@code places} is negative
     */
    public static double round(double value, int places) {
        if (places < 0) {
            throw new IllegalArgumentException("places cannot be negative");
        }

        return BigDecimal.valueOf(value)
                .setScale(places, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Performs an integer percentage roll.
     *
     * @param chance percentage chance from 0 through 100
     * @return {@code true} when the roll succeeds
     * @throws IllegalArgumentException if {@code chance} is outside 0-100
     */
    public static boolean roll(int chance) {
        if (chance < 0 || chance > 100) {
            throw new IllegalArgumentException(
                    "chance must be between 0 and 100"
            );
        }

        return ThreadLocalRandom.current().nextInt(100) < chance;
    }

}
