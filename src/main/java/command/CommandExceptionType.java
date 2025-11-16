package command;

/**
 * Defines structured failure types for command execution.
 * Each enum constant contains metadata used for logging,
 * fallback messages, and language-key resolution.
 */
public enum CommandExceptionType {

    /**
     * The command syntax was invalid (missing args, wrong format).
     * Not considered an internal error and should not be logged.
     */
    SYNTAX_ERROR(
        Severity.INFO,
        false,
        "command.error.syntax",
        "Invalid command syntax."
    ),

    /**
     * The user lacks the required permission.
     */
    PERMISSION_ERROR(
        Severity.WARN,
        false,
        "command.error.permission",
        "You don't have permission to do that."
    ),

    /**
     * The user lacks permission for a specific subcommand.
     */
    SUBCOMMAND_PERMISSION(
        Severity.WARN,
        false,
        "command.error.subcommand_permission",
        "You don't have permission for that subcommand."
    ),

    /**
     * The command or module is temporarily unavailable
     * (disabled, maintenance, etc.).
     */
    UNAVAILABLE_ERROR(
        Severity.WARN,
        true,
        "command.error.unavailable",
        "This command is currently unavailable."
    ),

    /**
     * A general or unexpected internal command error.
     */
    GENERAL_ERROR(
        Severity.ERROR,
        true,
        "command.error.general",
        "An error occurred while executing the command."
    );

    private final Severity severity;
    private final boolean shouldLog;
    private final String languageKey;
    private final String fallbackMessage;

    CommandExceptionType(Severity severity, boolean shouldLog, String languageKey, String fallbackMessage) {
        this.severity = severity;
        this.shouldLog = shouldLog;
        this.languageKey = languageKey;
        this.fallbackMessage = fallbackMessage;
    }

    /**
     * @return Severity level of the error.
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @return Whether this error should be written to logs.
     */
    public boolean shouldLog() {
        return shouldLog;
    }

    /**
     * @return Language key for localization lookup.
     */
    public String getLanguageKey() {
        return languageKey;
    }

    /**
     * @return Fallback English message if language key missing.
     */
    public String getFallbackMessage() {
        return fallbackMessage;
    }

    public enum Severity {
        INFO,
        WARN,
        ERROR
    }
    
}