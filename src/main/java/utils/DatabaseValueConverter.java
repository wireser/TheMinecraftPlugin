package utils;

/** Converts loosely typed JDBC values without repeating casts in modules. */
public final class DatabaseValueConverter {

    private DatabaseValueConverter() {}

    /** Returns the numeric value as an int, or zero when the value is absent. */
    public static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** Returns a nullable boolean from the common JDBC boolean representations. */
    public static Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof Number number) return number.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }
}
