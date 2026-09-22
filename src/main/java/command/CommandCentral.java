package command;

import enums.Currency;
import main.Main;
import managers.LanguageManager;
import model.Group;
import playerdata.Profile;

import org.bukkit.command.Command;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Level;

/**
 * Central command registry for managing all server commands.
 * Handles registration, execution, permissions, costs, and cooldowns.
 */
public final class CommandCentral {
	
	private final Map<String, CommandRegistry> commands = new HashMap<>();
	private final Map<String, String> aliases = new HashMap<>();
	private final CooldownManager cooldownManager = new CooldownManager();
	private final LanguageManager language;
	private final Main instance;
	
	// Will be used later for DB logging / analytics
	@SuppressWarnings("unused")
	private final database.Database database;

	public CommandCentral(Main plugin, database.Database database, LanguageManager language) {
	    this.instance = plugin;
	    this.database = database;
	    this.language = language;
	}

	/**
	 * Registers a new command with the registry.
	 *
	 * @param command The command to register
	 */
	public void register(CommandRegistry command) {
		String labelKey = command.getLabel().toLowerCase(Locale.ROOT);

		if (commands.containsKey(labelKey)) {
			instance.getLogger().warning(String.format(
					"Overwriting existing command registration for label '%s' with %s",
					labelKey,
					command.getClass().getName()
			));
		}

		commands.put(labelKey, command);

		for (String alias : command.getAliases()) {
			if (alias == null || alias.isEmpty()) continue;
			String aliasKey = alias.toLowerCase(Locale.ROOT);

			if (aliases.containsKey(aliasKey)) {
				instance.getLogger().warning(String.format(
						"Alias '%s' for command '%s' is already used by '%s'. Overwriting.",
						aliasKey,
						command.getLabel(),
						aliases.get(aliasKey)
				));
			}

			aliases.put(aliasKey, labelKey);
		}

		/*
		 * Register the command and its aliases directly with the lowest group
		 * allowed to use it. Higher groups inherit access through the group hierarchy.
		 */
		Group minimumGroup = command
		    .getMinimumGroup()
		    .getGroup();

		minimumGroup.registerCommand(
		    command.getLabel(),
		    command.getAliases()
		);
	}

	/**
	 * Unregisters a command from the registry.
	 *
	 * @param label The label of the command to unregister
	 */
	public void unregister(String label) {
		if (label == null) return;

		CommandRegistry command = commands.remove(label.toLowerCase(Locale.ROOT));
		if (command != null) {
			// Remove all aliases pointing to it
			command.getAliases().forEach(alias -> {
				if (alias == null || alias.isEmpty()) return;
				aliases.remove(alias.toLowerCase(Locale.ROOT));
			});

			/*
			 * Remove the command and every alias pointing to it from the group where
			 * it was originally registered.
			 */
			Group minimumGroup = command
			    .getMinimumGroup()
			    .getGroup();

			minimumGroup.unregisterCommand(
			    command.getLabel()
			);
		}
	}

	/**
	 * Retrieves a command by its input (either main label or alias).
	 *
	 * @param input The command input to look up
	 * @return Optional containing the command if found
	 */
	public Optional<CommandRegistry> getCommand(String input) {
		if (input == null) return Optional.empty();

		String lookup = input.toLowerCase(Locale.ROOT);
		CommandRegistry direct = commands.get(lookup);
		if (direct != null) {
			return Optional.of(direct);
		}

		String aliasTarget = aliases.get(lookup);
		if (aliasTarget != null) {
			return Optional.ofNullable(commands.get(aliasTarget));
		}
		return Optional.empty();
	}

	/**
	 * Executes a command with proper validation and error handling.
	 *
	 * @param player	   The player executing the command (wrapped profile)
	 * @param bukkitCmd   The Bukkit Command instance associated with this execution
	 * @param commandInput The command input (main label or alias as typed)
	 * @param args		 The command arguments
	 * @return true if the input was recognized as a registered command and was
	 * handled (executed or rejected with a message), false if no such
	 * command exists.
	 */
	public boolean execute(Profile player, Command bukkitCmd, String commandInput, String[] args) {

		// Resolve which label was actually used
		String usedLabel = (commandInput != null && !commandInput.isEmpty())
				? commandInput
				: (bukkitCmd != null ? bukkitCmd.getName() : null);

		if (usedLabel == null || usedLabel.isEmpty()) {
		    player.sendMessage(language.line("command.unknown", ""));
		    return false;
		}

		CommandRegistry cmd = getCommand(usedLabel).orElse(null);
		if (cmd == null) {
		    player.sendMessage(language.line("command.unknown", usedLabel));
		    return false;
		}

		if (!isCommandVisible(player, cmd)) {
		    // Intentionally pretend the command does not exist for this group
		    player.sendMessage(language.line("command.unknown", usedLabel));
		    return true;
		}

		// Module check
		if (!isModuleEnabled(cmd.getModuleName())) {
			player.sendMessage(language.line(
					"command.module_disabled",
					usedLabel.toLowerCase(Locale.ROOT)
			));
			return true;
		}

		// Command enabled check
		if (!cmd.isEnabled()) {
			player.sendMessage(language.line(
					"command.disabled",
					usedLabel.toLowerCase(Locale.ROOT)
			));
			return true;
		}

		// Permission check
		if (cmd.hasPermissionNode() && !player.hasPermission(cmd.getPermissionNode())) {
			player.sendMessage(language.line("command.no_permission"));
			return true;
		}

		// Costs
		if (!checkCosts(player, cmd)) {
		    Currency currency = cmd.getCurrency();       // must be non-null here, or we skipped earlier
		    double required  = getEffectiveCost(cmd);
		    String formatted = formatCurrency(required, currency);

		    player.sendMessage(language.line(
		        "command.insufficient_funds",
		        formatted,
		        currency.name()
		    ));
		    return true;
		}

		// Cooldown
		if (cmd.getCooldownSeconds() > 0 && !cooldownManager.checkCooldown(player.getUuid(), cmd)) {
			double remaining = cooldownManager.getRemaining(player.getUuid(), cmd);
			player.sendMessage(language.line(
				"command.cooldown",
				String.format(Locale.ROOT, "%.1f", remaining)
			));
			return true;
		}

		try {
			// Execute
			cmd.getExecutor().execute(player, usedLabel, args);

			// Post: costs + cooldown
			applyCosts(player, cmd);
			if (cmd.getCooldownSeconds() > 0) {
				cooldownManager.applyCooldown(player.getUuid(), cmd);
			}

			// TODO hook DB logging here
			// logCommandExecution(player, cmd, bukkitCmd, usedLabel, args);

			return true;
		} catch (CommandException e) {
			handleCommandException(player, cmd, e);
			return true;
		} catch (Exception e) {
		    // Treat as GENERAL_ERROR for logging purposes
		    instance.getLogger().log(
		        Level.SEVERE,
		        String.format(
		            "Unhandled exception while executing command '%s' for player %s",
		            cmd.getLabel(),
		            player.getUuid()
		        ),
		        e
		    );

		    player.sendMessage(language.line("command.unavailable"));
		    return true;
		}
		
	}

	/**
	 * Determines whether the player's group grants access to a command.
	 *
	 * <p>The {@link Group} object resolves inherited command access through
	 * its parent hierarchy.</p>
	 *
	 * @param player player attempting to discover or execute the command
	 * @param command command being checked
	 * @return {@code true} when the player's group grants command access
	 */
	private boolean isCommandVisible(
	    Profile player,
	    CommandRegistry command
	) {
	    Group playerGroup = player.getGroup();

	    if (playerGroup == null) {
	        return false;
	    }

	    return playerGroup.canUseCommand(
	        command.getLabel()
	    );
	}

	/**
	 * Checks if a module is enabled.
	 *
	 * @param moduleName The name of the module to check
	 * @return true if the module is enabled, false otherwise
	 */
	private boolean isModuleEnabled(String moduleName) {
		if (moduleName == null || moduleName.isEmpty()) {
			// No module -> treat as always enabled
			return true;
		}
		return instance.getModuleManager().isModuleEnabled(moduleName);
	}

	/**
	 * Returns the effective cost for this command, taking currency type into account.
	 * <p>
	 * For currencies that are configured as integral, the cost is rounded to the
	 * nearest whole number. For decimal currencies, the raw cost is used as-is.
	 *
	 * @param cmd the command definition
	 * @return effective cost to charge
	 */
	private double getEffectiveCost(CommandRegistry cmd) {
	    double cost = cmd.getCost();
	    Currency currency = cmd.getCurrency();

	    if (currency != null && currency.isIntegral()) {
	        return Math.round(cost);
	    }
	    return cost;
	}

	/**
	 * Checks if a player has sufficient funds for a command.
	 *
	 * @param player the profile to check
	 * @param cmd    the command with associated cost
	 * @return {@code true} if the player can afford the command (or no cost is defined),
	 *         {@code false} otherwise
	 */
	private boolean checkCosts(Profile player, CommandRegistry cmd) {
	    double rawCost = cmd.getCost();
	    if (rawCost <= 0.0d) {
	        // No cost configured -> always allowed
	        return true;
	    }

	    Currency currency = cmd.getCurrency();
	    if (currency == null) {
	        instance.getLogger().warning(String.format(
	                "Command '%s' has a non-zero cost (%.3f) but no currency defined. Skipping cost.",
	                cmd.getLabel(),
	                rawCost
	        ));
	        // Treat as free from the player's perspective
	        return true;
	    }

	    double effective = getEffectiveCost(cmd);

	    // Profile should return BigDecimal here
	    BigDecimal balance = player.getBalance(currency);
	    BigDecimal required = BigDecimal.valueOf(effective);

	    return balance.compareTo(required) >= 0;
	}

	/**
	 * Formats a currency value appropriately for its type.
	 *
	 * @param amount   the amount to format
	 * @param currency the currency type (may be {@code null})
	 * @return formatted currency string
	 */
	private String formatCurrency(double amount, Currency currency) {
	    if (currency != null && currency.isIntegral()) {
	        return String.valueOf((int) Math.round(amount));
	    } else {
	        // Use a fixed 2-decimal representation for decimal currencies
	        return String.format(Locale.US, "%.2f", amount);
	    }
	}

	/**
	 * Applies command costs to a player after successful execution.
	 *
	 * @param player the player to deduct costs from
	 * @param cmd    the command with associated cost
	 */
	private void applyCosts(Profile player, CommandRegistry cmd) {
	    double rawCost = cmd.getCost();
	    if (rawCost <= 0.0d) {
	        return;
	    }

	    Currency currency = cmd.getCurrency();
	    if (currency == null) {
	        return;
	    }

	    double effective = getEffectiveCost(cmd);
	    BigDecimal delta = BigDecimal.valueOf(effective).negate();

	    // Subtract by adding a negative delta
	    player.addBalance(currency, delta);
	}

	private void logCommandException(CommandRegistry cmd, CommandException e) {
	    CommandExceptionType type = e.getType();
	    Level level = switch (type.getSeverity()) {
	        case INFO -> Level.INFO;
	        case WARN -> Level.WARNING;
	        default -> Level.SEVERE;
	    };

	    instance.getLogger().log(
	        level,
	        String.format(
	            "CommandException while executing '%s': %s",
	            cmd.getLabel(),
	            e.getMessage()
	        ),
	        e
	    );
	}
	
	/**
	 * Handles different types of command exceptions with appropriate messaging.
	 *
	 * @param player The player who executed the command
	 * @param cmd	The command that caused the exception
	 * @param e	  The exception that occurred
	 */
	private void handleCommandException(Profile player, CommandRegistry cmd, CommandException e) {
	    CommandExceptionType type = e.getType();

	    // Logging based on metadata
	    if (type.shouldLog()) {
	        logCommandException(cmd, e);
	    }

	    if (type == CommandExceptionType.SYNTAX_ERROR) {
	        player.sendMessage(language.line(
	            type.getLanguageKey(),
	            e.getMessage()
	        ));

	        if (cmd.getSyntax() != null && !cmd.getSyntax().isEmpty()) {
	            player.sendMessage(language.line(
	                "command.syntax.usage",
	                cmd.getSyntax()
	            ));
	        }

	        return;
	    }

	    player.sendMessage(language.line(type.getLanguageKey()));
	}
	
}
