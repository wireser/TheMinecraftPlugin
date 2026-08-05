package command;

import playerdata.Profile;

/**
 * Functional interface for command execution logic.
 * <p>
 * Implementations should contain only the core behavior for a command.
 * All common concerns such as visibility, module checks, permissions,
 * costs, and cooldowns are handled by {@link CommandCentral} before
 * this method is invoked.
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Executes a command for the given player.
     *
     * @param player    The player executing the command (wrapped profile)
     * @param label     The command label or alias used (as typed)
     * @param args      The command arguments (never null, but may be empty)
     *
     * @throws CommandException if a controlled error occurs during execution.
     *                          {@link CommandCentral} will translate this
     *                          into a user-friendly message.
     */
    void execute(Profile player, String label, String[] args) throws CommandException;
    
}