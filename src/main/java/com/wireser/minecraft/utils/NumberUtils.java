package com.wireser.minecraft.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Utility methods for formatting numeric values.
 */
public final class NumberUtils {

    private NumberUtils() {
        // Utility class.
    }

    /**
     * Formats a monetary value with grouping separators and exactly two decimal places.
     *
     * <p>Values are rounded using {@link RoundingMode#HALF_UP}. Locale-independent
     * symbols are used so formatting remains consistent across server environments.</p>
     *
     * @param amount monetary value to format
     * @return formatted value with exactly two decimal places
     */
    public static String formatMoney(BigDecimal amount) {
        DecimalFormat formatter = new DecimalFormat(
                "#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.ROOT)
        );

        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(amount);
    }

}
