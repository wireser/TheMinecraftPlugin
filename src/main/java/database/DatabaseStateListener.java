package database;

/**
 * Listener interface for receiving notifications when the database status changes.
 */
@FunctionalInterface
public interface DatabaseStateListener {

    /**
     * Called when the database status changes.
     *
     * @param oldStatus the previous status
     * @param newStatus the new status
     */
    void onDatabaseStatusChange(DatabaseStatus oldStatus, DatabaseStatus newStatus);
    
}