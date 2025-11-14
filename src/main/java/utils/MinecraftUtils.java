package utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.command.ConsoleCommandSender;

public class MinecraftUtils {

	/***
	 * Get an entity type by its name
	 * @param entityTypeName The name of the entity type
	 * @return EntityType null if input is invalid or entity can not be found
	 */
	public static EntityType getEntityByName(String entityTypeName) {
		if(!Validator.checkString(entityTypeName))
	        return null;

	    try {
	        return EntityType.valueOf(entityTypeName.toUpperCase());
	    } catch(IllegalArgumentException ex) {
	        return null;
	    }
	}
	
	/**
	 * Executes a command as the server console.
	 * <p>
	 * If the command is {@code null}, empty, or invalid, the execution is skipped
	 * and {@code false} is returned. Any thrown exception is caught to ensure the
	 * server does not crash due to malformed or failing commands.
	 *
	 * @param command the full command string to run (without leading slash)
	 * @return {@code true} if the command was executed successfully by Bukkit,
	 *         {@code false} if execution failed or the input was invalid
	 */
	public static boolean sendCommandAsConsole(String command) {
		if(!Validator.checkString(command))
	        return false;
		
		try {
			ConsoleCommandSender console = Bukkit.getConsoleSender();
	        return Bukkit.dispatchCommand(console, command);
	    } catch (Exception ignored) {
	        return false;
	    }
	}
	
}
