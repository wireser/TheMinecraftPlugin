package utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class NumberUtils {
	
	public static String formatMoney(BigDecimal amount) {
	    DecimalFormat formatter = new DecimalFormat(
	            "#,##0.00#",
	            DecimalFormatSymbols.getInstance(Locale.ROOT)
	    );

	    formatter.setRoundingMode(RoundingMode.HALF_UP);
	    return formatter.format(amount);
	}
	
}
