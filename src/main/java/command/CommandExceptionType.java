package command;

/**
 * Describes a structured command failure.
 *
 * <p>Each type defines its logging severity, whether it should be logged and
 * the language key used for its player-facing message. Message text belongs
 * exclusively to {@code lang.yml}; this enum does not duplicate language
 * fallbacks.</p>
 */
public enum CommandExceptionType {

    /** Invalid or incomplete command syntax. */
    SYNTAX_ERROR(
            Severity.INFO,
            false,
            "command.error.prefix"
    ),

    /** The player lacks permission for the command. */
    PERMISSION_ERROR(
            Severity.WARN,
            false,
            "command.no_permission"
    ),

    /** The player lacks permission for a subcommand. */
    SUBCOMMAND_PERMISSION(
            Severity.WARN,
            false,
            "command.no_sub_permission"
    ),

    /** The command or its owning module is temporarily unavailable. */
    UNAVAILABLE_ERROR(
            Severity.WARN,
            true,
            "command.unavailable"
    ),

    /** An unexpected internal error occurred during command execution. */
    GENERAL_ERROR(
            Severity.ERROR,
            true,
            "command.error.general"
    );

    private final Severity severity;
    private final boolean shouldLog;
    private final String languageKey;

    CommandExceptionType(
            Severity severity,
            boolean shouldLog,
            String languageKey
    ) {
        this.severity = severity;
        this.shouldLog = shouldLog;
        this.languageKey = languageKey;
    }

    /**
     * @return severity used when this failure is written to the log
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @return {@code true} when this failure should be written to the log
     */
    public boolean shouldLog() {
        return shouldLog;
    }

    /**
     * @return language key for the player-facing message
     */
    public String getLanguageKey() {
        return languageKey;
    }

    /** Logging severity for a structured command failure. */
    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

}