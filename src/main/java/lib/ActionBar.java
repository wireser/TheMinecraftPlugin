package lib;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import main.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Handles temporary action bar messages displayed above the inventory
 * Provides timed messages with configurable duration and formatting
 */
public class ActionBar {
	
	/**
	 * Sends a temporary action bar message to a player with custom duration and formatting
	 * Uses Adventure API to display messages above the inventory hotbar
	 * Automatically clears the message after the specified duration
	 * 
	 * @param player The target player to receive the message
	 * @param message The component message to display
	 * @param seconds Duration in seconds to display the message (1-10 recommended)
	 * @param bold Whether the text should be rendered in bold format
	 */
	public static void sendMessage(Player player, Component message, int seconds, boolean bold) {
		
		if(player == null || !player.isOnline())
			return;
		
		// Apply bold formatting if requested
		Component formattedMessage = bold ? 
			message.decorate(TextDecoration.BOLD) : 
			message;
		
		// Send the initial message
		player.sendActionBar(formattedMessage);
		
		// Schedule message clearing after specified duration
		if(seconds > 0) {
			new BukkitRunnable() {
				@Override
				public void run() {
					if (player.isOnline()) {
						// Clear the action bar by sending empty component
						player.sendActionBar(Component.empty());
					}
				}
			}.runTaskLater(Main.plugin, seconds * 20L); // Convert seconds to ticks (20 ticks = 1 second)
		}
	}
	
	/**
	 * Sends a temporary action bar message to a player with custom duration
	 * Uses normal (non-bold) text formatting by default
	 * 
	 * @param player The target player to receive the message
	 * @param message The component message to display
	 * @param seconds Duration in seconds to display the message
	 */
	public static void sendMessage(Player player, Component message, int seconds) {
		sendMessage(player, message, seconds, false);
	}
	
	/**
	 * Sends a temporary action bar message to a player with default 2-second duration
	 * Uses normal (non-bold) text formatting by default
	 * 
	 * @param player The target player to receive the message
	 * @param message The component message to display
	 */
	public static void sendMessage(Player player, Component message) {
		sendMessage(player, message, 2, false);
	}
	
	/**
	 * Sends a persistent action bar message that doesn't auto-clear
	 * Useful for continuous status updates that should remain until manually cleared
	 * 
	 * @param player The target player to receive the message
	 * @param message The component message to display
	 * @param bold Whether the text should be rendered in bold format
	 */
	public static void sendPersistentMessage(Player player, Component message, boolean bold) {
		sendMessage(player, message, 0, bold);
	}
	
	/**
	 * Clears any existing action bar message for a player
	 * 
	 * @param player The player whose action bar should be cleared
	 */
	public static void clearMessage(Player player) {
		if(player != null && player.isOnline())
			player.sendActionBar(Component.empty());
	}
	
}