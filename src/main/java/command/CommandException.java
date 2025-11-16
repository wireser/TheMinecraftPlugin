package command;

/**
 * Represents a controlled failure during command execution.
 * Carries both a human-readable message and a specific error type
 * used by {@link CommandCentral} for standardized response handling.
 */
public final class CommandException extends Exception {

    private static final long serialVersionUID = 1230241345341910226L;

    private final CommandExceptionType type;

    /**
     * Creates a generic command exception (GENERAL_ERROR).
     *
     * @param message Description of the error
     */
    public CommandException(String message) {
        super(message);
        this.type = CommandExceptionType.GENERAL_ERROR;
    }

    /**
     * Creates a typed command exception.
     *
     * @param message Description of the error
     * @param type    Specific exception category
     */
    public CommandException(String message, CommandExceptionType type) {
        super(message);
        this.type = type;
    }

    /**
     * Creates a typed command exception with a root cause.
     *
     * @param message Description of the error
     * @param type    Specific exception category
     * @param cause   Underlying cause
     */
    public CommandException(String message, CommandExceptionType type, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /**
     * @return The type/category of this command exception.
     */
    public CommandExceptionType getType() {
        return type;
    }

    // ==========================================================
    //  Static factory helpers for clean command code
    // ==========================================================

    public static CommandException syntax(String message) {
        return new CommandException(message, CommandExceptionType.SYNTAX_ERROR);
    }

    public static CommandException permission(String message) {
        return new CommandException(message, CommandExceptionType.PERMISSION_ERROR);
    }

    public static CommandException subPerm(String message) {
        return new CommandException(message, CommandExceptionType.SUBCOMMAND_PERMISSION);
    }

    public static CommandException unavailable(String message) {
        return new CommandException(message, CommandExceptionType.UNAVAILABLE_ERROR);
    }

    public static CommandException general(String message) {
        return new CommandException(message, CommandExceptionType.GENERAL_ERROR);
    }
    
}