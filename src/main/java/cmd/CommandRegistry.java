package cmd;

import enums.Currency;
import java.util.*;

/**
 * Represents a command with all its configuration options.
 * This class is immutable once created.
 */
public final class CommandRegistry {
	private final String label;
	private final List<String> aliases;
	private final String syntax;
	private final String description;
	private final double cost;
	private final Currency currency;
	private final int cooldownSeconds;
	private final String permissionNode; // For Bukkit-style permissions
	private final String moduleName;
	private final Group group;
	private final CommandExecutor executor;
	private final boolean enabled;

	private CommandRegistry(Builder builder) {
		this.label = builder.label;
		this.aliases = Collections.unmodifiableList(builder.aliases);
		this.syntax = builder.syntax;
		this.description = builder.description;
		this.cost = builder.cost;
		this.currency = builder.currency;
		this.cooldownSeconds = builder.cooldownSeconds;
		this.permissionNode = builder.permissionNode;
		this.moduleName = builder.moduleName;
		this.group = builder.group;
		this.executor = builder.executor;
		this.enabled = true;
	}

	// Getters for all fields
	public String getLabel() { return label; }
	public List<String> getAliases() { return aliases; }
	public String getSyntax() { return syntax; }
	public String getDescription() { return description; }
	public double getCost() { return cost; }
	public Currency getCurrency() { return currency; }
	public int getCooldownSeconds() { return cooldownSeconds; }
	public String getPermissionNode() { return permissionNode; }
	public String getModuleName() { return moduleName; }
	public Group getGroup() { return group; }
	public CommandExecutor getExecutor() { return executor; }
	public boolean isEnabled() { return enabled; }

	/**
	 * Builder pattern for creating Command instances.
	 */
	public static class Builder {
		private final String label;
		private final CommandExecutor executor;
		private List<String> aliases = new ArrayList<>();
		private String syntax = "";
		private String description = "";
		private double cost = 0.0;
		private Currency currency = Currency.MONEY; // Default currency
		private int cooldownSeconds = 0;
		private String permissionNode = null; // Bukkit-style permission node
		private String moduleName = "Global";
		private Group group = null;
		private boolean enabled = true;

		/**
		 * Creates a new Command builder.
		 * 
		 * @param label The primary command label
		 * @param executor The command execution logic
		 */
		public Builder(String label, CommandExecutor executor) {
			this.label = label;
			this.executor = executor;
		}

		/**
		 * Sets command aliases.
		 * 
		 * @param aliases The command aliases
		 * @return This builder
		 */
		public Builder aliases(String... aliases) {
			this.aliases = Arrays.asList(aliases);
			return this;
		}

		/**
		 * Sets command syntax.
		 * 
		 * @param syntax The command syntax
		 * @return This builder
		 */
		public Builder syntax(String syntax) {
			this.syntax = syntax;
			return this;
		}

		/**
		 * Sets command description.
		 * 
		 * @param description The command description
		 * @return This builder
		 */
		public Builder description(String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets command cost.
		 * 
		 * @param cost The cost to execute the command
		 * @param currency The currency type for the cost
		 * @return This builder
		 */
		public Builder cost(double cost, Currency currency) {
			this.cost = cost;
			this.currency = currency;
			return this;
		}

		/**
		 * Sets command cooldown.
		 * 
		 * @param cooldownSeconds Cooldown time in seconds
		 * @return This builder
		 */
		public Builder cooldownSeconds(int cooldownSeconds) {
			this.cooldownSeconds = cooldownSeconds;
			return this;
		}

		/**
		 * Sets a Bukkit-style permission node.
		 * 
		 * @param permissionNode The permission node string
		 * @return This builder
		 */
		public Builder permissionNode(String permissionNode) {
			this.permissionNode = permissionNode;
			return this;
		}

		/**
		 * Sets the module name.
		 * 
		 * @param moduleName The name of the module
		 * @return This builder
		 */
		public Builder moduleName(String moduleName) {
			this.moduleName = moduleName;
			return this;
		}
		
		/**
		 * Sets the associated group.
		 * 
		 * @param group The group that can access this command
		 * @return This builder
		 */
		public Builder group(Group group) {
			this.group = group;
			return this;
		}
		
		public Builder enabled(boolean enabled) {
		    this.enabled = enabled;
		    return this;
		}

		/**
		 * Builds the Command instance.
		 * 
		 * @return The created Command
		 */
		public CommandRegistry build() {
			return new CommandRegistry(this);
		}
	}
}





