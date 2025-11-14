package cmd;

/**
 * Custom exception for command execution errors.
 */
public class CommandException extends Exception {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1230241345341910226L;
	private final CommandExceptionType type;
	
	public CommandException(String message) {
		super(message);
		this.type = CommandExceptionType.GENERAL_ERROR;
	}
	
	public CommandException(String message, CommandExceptionType type) {
		super(message);
		this.type = type;
	}
	
	public CommandExceptionType getType() {
		return type;
	}
}