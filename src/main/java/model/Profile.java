package model;

import java.util.UUID;

import org.bukkit.entity.Player;

import enums.Currency;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

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

	public void msg(NamedTextColor red, String cOMMAND_ERROR_MSG, String message) {
		// TODO Auto-generated method stub
		
	}

	public void msg(NamedTextColor red, String sUBCOMMAND_PERMISSION_MSG) {
		// TODO Auto-generated method stub
		
	}

	public void msg(NamedTextColor red, String iNSUFFICIENT_FUNDS_MSG, String formattedCost, String lowerCase) {
		// TODO Auto-generated method stub
		
	}

	public UUID getUuid() {
		// TODO Auto-generated method stub
		return null;
	}

	public Group getGroup() {
		// TODO Auto-generated method stub
		return new Group(ign, ign, null, null, null, null, null, null, ign, ign, ign);
	}

	public void msg(NamedTextColor red, String cOOLDOWN_ACTIVE_MSG, double remaining) {
		// TODO Auto-generated method stub
		
	}

	public double getBalance(Currency currency) {
		// TODO Auto-generated method stub
		return 0;
	}

	public void removeBalance(double amount, Currency currency) {
		// TODO Auto-generated method stub
		
	}
	
}
