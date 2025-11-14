package lib;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

public class Profile {

	public Player player;
	public String ign;
	
	public Component name;
	public Component displayName;
	
	public Profile(Player player) {
		
		this.player = player;	
		this.ign = player.getName();
		
		this.name = player.name();
		displayName = player.displayName();
		
	}
	
}
