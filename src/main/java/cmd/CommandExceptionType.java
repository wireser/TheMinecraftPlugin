package cmd;

/**
 * Exception types for command execution errors.
 */
public enum CommandExceptionType {
	SYNTAX_ERROR,		  // Incorrect command syntax
	PERMISSION_ERROR,	  // General permission issue
	SUBCOMMAND_PERMISSION, // Specific subcommand permission issue
	UNAVAILABLE,		   // Command unavailable (e.g., maintenance)
	GENERAL_ERROR		  // Other command errors
}
