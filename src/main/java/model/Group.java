package model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;


import enums.Perm;
import net.md_5.bungee.api.ChatColor;

public class Group {

	// Data
	public ChatColor color;
	public String name;
	public String prefix;

	// Limits
	public Integer limitClaim;
	public Integer limitWarps;
	public Integer limitShops;

	// Rewards
	public Double rewardCash;
	public String bonus;
	public String bonusCommand;
	public String newCommand;
	
	public Integer trip;

	// Lists
	public List<String> commands = new ArrayList<>();
	public List<Perm> flags = new ArrayList<>();

	// Initialization
	public Group(String name, String prefix, ChatColor color, Integer trip, Integer claim, Integer warps, Integer shops, Double cash, String bonus, String newCommand, String bonusCommand) {
		this.name = name;
		this.prefix = prefix;
		this.color = color;

		this.limitClaim = claim;
		this.limitWarps = warps;
		this.limitShops = shops;

		this.rewardCash = cash;
		this.bonus = bonus;
		this.bonusCommand = bonusCommand;
		this.newCommand = newCommand;
		
		this.trip = trip;
	}

	/**
	 *
	 * @param flag
	 */
	public void addFlag(Perm flag) {
		if(!flags.contains(flag))
			flags.add(flag);
	}

	/**
	 *
	 * @param flag
	 */
	public void removeFlag(Perm flag) {
		if(flags.contains(flag))
			flags.remove(flag);
	}

	/**
	 *
	 * @param group
	 */
	public void inheritFlags(Group group) {
		for(Perm flag : group.flags)
			flags.add(flag);
	}

	/**
	 *
	 * @param cmd
	 */
	public void addCommand(String cmd) {
		commands.add(cmd);
	}

	/**
	 *
	 * @param cmd
	 */
	public void addCommand(String[] cmd) {
		for(String _cmd_ : cmd)
			commands.add(_cmd_);
	}

	/**
	 *
	 * @param cmd
	 */
	public void addCommand(List<String> cmd) {
		for(String _cmd_ : cmd)
			commands.add(_cmd_);
	}

	/**
	 *
	 * @param cmd
	 */
	public void addCommand(HashMap<String, String> cmd) {
		for(String _cmd_ : cmd.values())
			commands.add(_cmd_);
	}

	/**
	 *
	 * @param cmd
	 */
	public void addCommand(Set<String> cmd) {
		for(String _cmd_ : cmd)
			commands.add(_cmd_);
	}

	/**
	 *
	 * @param cmd
	 */
	public void addCommand(Collection<String> cmd) {
		for(String _cmd_ : cmd)
			commands.add(_cmd_);
	}

	/**
	 *
	 * @param group
	 */
	public void inheritCommands(Group group) {
		for(String cmd : group.commands)
			commands.add(cmd);
	}

}
