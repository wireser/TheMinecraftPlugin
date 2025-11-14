package cmd;

/**
 * Functional interface for command execution logic.
 */
public interface CommandExecutor {
	/**
	 * Executes a command.
	 * 
	 * @param player The player executing the command
	 * @param command The command label used
	 * @param args The command arguments
	 * @throws CommandException if an error occurs during execution
	 */
	void execute(Profile player, String command, String[] args) throws CommandException;
}