package command;

import enums.Currency;
import main.Main;
import managers.LanguageManager;
import model.Group;
import net.kyori.adventure.text.format.NamedTextColor;
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

	// Message keys + fallbacks

	// unknown command
	private static final String KEY_UNKNOWN_COMMAND      = "command.unknown";
	private static final String FMT_UNKNOWN_COMMAND      = "Unknown command: %s";

	// generic perms
	private static final String KEY_NO_PERMISSION        = "command.no_permission";
	private static final String MSG_NO_PERMISSION        = "You don't have permission for this command!";

	// subcommand perms
	private static final String KEY_NO_SUB_PERMISSION    = "command.no_sub_permission";
	private static final String MSG_NO_SUB_PERMISSION    = "You don't have permission for this subcommand!";

	// disabled / module
	private static final String KEY_MODULE_DISABLED      = "command.module_disabled";
	private static final String FMT_MODULE_DISABLED      = "Error: The module containing the %s command has been disabled.";

	private static final String KEY_COMMAND_DISABLED     = "command.disabled";
	private static final String FMT_COMMAND_DISABLED     = "Error: The %s command is currently disabled.";

	// costs
	private static final String KEY_INSUFFICIENT_FUNDS   = "command.insufficient_funds";
	private static final String FMT_INSUFFICIENT_FUNDS   = "You need %s %s for this!";

	// cooldown
	private static final String KEY_COOLDOWN_ACTIVE      = "command.cooldown";
	private static final String FMT_COOLDOWN_ACTIVE      = "Command is on cooldown! Remaining: %.1fs";

	// syntax & errors
	private static final String KEY_SYNTAX_USAGE         = "command.syntax.usage";
	private static final String FMT_SYNTAX_USAGE         = "Usage: %s";

	private static final String KEY_COMMAND_ERROR        = "command.error.prefix";
	private static final String FMT_COMMAND_ERROR        = "Error: %s";

	private static final String KEY_UNAVAILABLE          = "command.unavailable";
	private static final String MSG_UNAVAILABLE          = "This command is currently unavailable.";
	
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

		Group group = command.getGroup();
		if (group != null) {
			group.addCommand(command.getLabel());
			for (String alias : command.getAliases()) {
				group.addCommand(alias);
			}
		}
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

			// Remove from group
			Group group = command.getGroup();
			if (group != null) {
				group.removeCommand(command.getLabel());
				for (String alias : command.getAliases()) {
					group.removeCommand(alias);
				}
			}
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
		    String msg = language.getString(
		        KEY_UNKNOWN_COMMAND,
		        FMT_UNKNOWN_COMMAND,
		        ""
		    );
		    player.msg(NamedTextColor.RED, msg);
		    return false;
		}

		CommandRegistry cmd = getCommand(usedLabel).orElse(null);
		if (cmd == null) {
		    String msg = language.getString(
		        KEY_UNKNOWN_COMMAND,
		        FMT_UNKNOWN_COMMAND,
		        usedLabel
		    );
		    player.msg(NamedTextColor.RED, msg);
		    return false;
		}

		if (!isCommandVisible(player, cmd)) {
		    // Intentionally pretend the command does not exist for this group
		    String msg = language.getString(
		        KEY_UNKNOWN_COMMAND,
		        FMT_UNKNOWN_COMMAND,
		        usedLabel
		    );
		    player.msg(NamedTextColor.RED, msg);
		    return true;
		}

		// Module check
		if (!isModuleEnabled(cmd.getModuleName())) {
			player.msg(
				    NamedTextColor.RED,
				    language.getString(
				        KEY_MODULE_DISABLED,
				        FMT_MODULE_DISABLED,
				        usedLabel.toLowerCase(Locale.ROOT)
				    )
				);
			return true;
		}

		// Command enabled check
		if (!cmd.isEnabled()) {
			player.msg(
				    NamedTextColor.RED,
				    language.getString(
				        KEY_COMMAND_DISABLED,
				        FMT_COMMAND_DISABLED,
				        usedLabel.toLowerCase(Locale.ROOT)
				    )
				);
			return true;
		}

		// Permission check
		if (cmd.hasPermissionNode() && !player.hasPermission(cmd.getPermissionNode())) {
			String noPerm = language.getString(KEY_NO_PERMISSION, MSG_NO_PERMISSION);
			player.msg(NamedTextColor.RED, noPerm);
			return true;
		}

		// Costs
		if (!checkCosts(player, cmd)) {
		    Currency currency = cmd.getCurrency();       // must be non-null here, or we skipped earlier
		    double required  = getEffectiveCost(cmd);
		    String formatted = formatCurrency(required, currency);

		    player.msg(
		        NamedTextColor.RED,
		        language.getString(
		            KEY_INSUFFICIENT_FUNDS,
		            FMT_INSUFFICIENT_FUNDS,
		            formatted,
		            currency.name()
		        )
		    );
		    return true;
		}

		// Cooldown
		if (cmd.getCooldownSeconds() > 0 && !cooldownManager.checkCooldown(player.getUuid(), cmd)) {
			double remaining = cooldownManager.getRemaining(player.getUuid(), cmd);
			player.msg(
			    NamedTextColor.RED,
			    language.getString(
			        KEY_COOLDOWN_ACTIVE,
			        FMT_COOLDOWN_ACTIVE,
			        remaining
			    )
			);
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

		    String msg = language.getString(KEY_UNAVAILABLE, MSG_UNAVAILABLE);
		    player.msg(NamedTextColor.RED, msg);
		    return true;
		}
		
	}

	/**
	 * Checks if a command is visible to a player based on their group.
	 *
	 * @param player The player to check
	 * @param cmd	The command to check visibility for
	 * @return true if the command is visible, false otherwise
	 */
	private boolean isCommandVisible(Profile player, CommandRegistry cmd) {
		Group group = player.getGroup();
		if (group == null) {
			return false;
		}

		// Check label
		if (group.hasCommand(cmd.getLabel())) {
			return true;
		}

		// Check aliases
		return cmd.getAliases().stream().anyMatch(group::hasCommand);
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

	    switch (type) {
	        case SYNTAX_ERROR: {
	            // "Error: <message>"
	            String errorPrefix = language.getString(
	                KEY_COMMAND_ERROR,
	                FMT_COMMAND_ERROR,
	                e.getMessage()
	            );
	            player.msg(NamedTextColor.RED, errorPrefix);

	            // Optional usage line
	            if (cmd.getSyntax() != null && !cmd.getSyntax().isEmpty()) {
	                String usage = language.getString(
	                    KEY_SYNTAX_USAGE,
	                    FMT_SYNTAX_USAGE,
	                    cmd.getSyntax()
	                );
	                player.msg(NamedTextColor.RED, usage);
	            }
	            break;
	        }

	        case PERMISSION_ERROR: {
	            String noPerm = language.getString(KEY_NO_PERMISSION, MSG_NO_PERMISSION);
	            player.msg(NamedTextColor.RED, noPerm);
	            break;
	        }

	        case SUBCOMMAND_PERMISSION: {
	            String noSubPerm = language.getString(KEY_NO_SUB_PERMISSION, MSG_NO_SUB_PERMISSION);
	            player.msg(NamedTextColor.RED, noSubPerm);
	            break;
	        }

	        case UNAVAILABLE_ERROR: {
	            String unavailable = language.getString(KEY_UNAVAILABLE, MSG_UNAVAILABLE);
	            player.msg(NamedTextColor.RED, unavailable);
	            break;
	        }

	        case GENERAL_ERROR:
	        default: {
	            String msg = language.getString(
	                KEY_COMMAND_ERROR,
	                FMT_COMMAND_ERROR,
	                e.getMessage()
	            );
	            player.msg(NamedTextColor.RED, msg);
	            break;
	        }
	    }
	}
	
}