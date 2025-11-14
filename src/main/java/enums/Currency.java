package enums;

public enum Currency {
	
    MONEY("money", Double.class),
    BALANCE("balance", Double.class),
    GEMS("gems", Integer.class),
    RUBIES("rubies", Integer.class),
    LEVELS("levels", Integer.class),
    POINTS("points", Integer.class),
    KARMA("karma", Double.class);

    private final String columnName;
    private final Class<? extends Number> type;

    Currency(String columnName, Class<? extends Number> type) {
        this.columnName = columnName;
        this.type = type;
    }

    public String getColumnName() {
        return columnName;
    }

    public Class<? extends Number> getType() {
        return type;
    }

    public boolean isIntegerType() {
        return type == Integer.class;
    }
    
}