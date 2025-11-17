package database;

/**
 * Thrown when the database fails to initialize due to configuration
 * or connectivity issues. Carries a short error code to help locate
 * the cause quickly in logs or bug reports.
 */
public final class DatabaseInitException extends RuntimeException {

    /**
	 * 
	 */
	private static final long serialVersionUID = -6271653490928042462L;
	private final String code;

    /**
     * @param code    stable identifier for the error (e.g. "DB-ENCODING-001")
     * @param message human-readable description of the failure
     */
    public DatabaseInitException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @param code    stable identifier for the error (e.g. "DB-ENCODING-001")
     * @param message human-readable description of the failure
     * @param cause   underlying cause
     */
    public DatabaseInitException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Returns the stable error code describing this initialization failure.
     */
    public String getCode() {
        return code;
    }
    
}