package database;

/**
 * Represents the last known state of the database connection.
 */
public enum DatabaseStatus {
	
    /**
     * Initial state before any health check has been performed.
     */
    UNKNOWN,

    /**
     * The last health check succeeded; the database is considered available.
     */
    UP,

    /**
     * The last health check failed or the pool is closed; the database
     * is considered unavailable.
     */
    DOWN
    
}