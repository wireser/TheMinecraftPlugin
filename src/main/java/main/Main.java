package main;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import lib.ActionBar;
import lib.PlayerHeads;
import lib.Profile;
import lib.Sidebar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import utils.Strings;

public class Main extends JavaPlugin
{

	public static Main plugin;
	public static FileConfiguration config;
	
	public static final NamedTextColor RED         = NamedTextColor.RED;
	public static final NamedTextColor GREEN       = NamedTextColor.GREEN;
	public static final NamedTextColor YELLOW      = NamedTextColor.YELLOW;
	public static final NamedTextColor WHITE       = NamedTextColor.WHITE;
	public static final NamedTextColor GOLD        = NamedTextColor.GOLD;
	public static final NamedTextColor GRAY        = NamedTextColor.GRAY;
	public static final NamedTextColor AQUA        = NamedTextColor.AQUA;
	public static final NamedTextColor BLUE        = NamedTextColor.BLUE;
	public static final NamedTextColor BLACK       = NamedTextColor.BLACK;
	public static final NamedTextColor PURPLE      = NamedTextColor.LIGHT_PURPLE;
	
	public static final NamedTextColor DARK_PURPLE = NamedTextColor.DARK_PURPLE;
	public static final NamedTextColor DARK_RED    = NamedTextColor.DARK_RED;
	public static final NamedTextColor DARK_AQUA   = NamedTextColor.DARK_AQUA;
	public static final NamedTextColor DARK_BLUE   = NamedTextColor.DARK_BLUE;
	public static final NamedTextColor DARK_GRAY   = NamedTextColor.DARK_GRAY;
	public static final NamedTextColor DARK_GREEN  = NamedTextColor.DARK_GREEN;
	
	@Override
	public void onEnable() {
		plugin = this;
	}
	
	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, String label, String @NotNull [] args) {
		
		Player player = (Player) sender;
		Profile profile = new Profile(player);
		
		if(!label.equalsIgnoreCase("test"))
			return true;
		
		if(args[0].equalsIgnoreCase("box")) {
			ItemStack head = PlayerHeads.getPlayerHead(args[1]);
			if (head != null) {
			    player.getInventory().addItem(head);
			    player.sendMessage("§aPlayer head received for: " + args[1]);
			} else {
			    player.sendMessage("§cCould not get player head for: " + args[1]);
			    player.sendMessage("§7This could be because:");
			    player.sendMessage("§7- The player doesn't exist");
			    player.sendMessage("§7- The Mojang API is down");
			    player.sendMessage("§7- There was a network error");
			}
			return true;
		}
		
		if(args[0].equalsIgnoreCase("sidebar")) {
		    Sidebar.displaySidebar(profile);
		    return true;
		}
		
		if(args[0].equalsIgnoreCase("actionbar")) {
		    ActionBar.sendMessage(player, Component.text(Strings.mergeStrings(args, 1)));
		    return true;
		}
		
		return true;
		
	}
	
}
