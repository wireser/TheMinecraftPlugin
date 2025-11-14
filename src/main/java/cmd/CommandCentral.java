package cmd;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import enums.Currency;
import main.Main;

/**
 * Central command registry for managing all server commands.
 * Handles registration, execution, permissions, costs, and cooldowns.
 */
public final class CommandCentral {
    private final Map<String, CommandRegistry> commands = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final CooldownManager cooldownManager = new CooldownManager();
    
    // Configurable messages
    public static String NO_PERMISSION_MSG = "You don't have permission for this command!";
    public static String INSUFFICIENT_FUNDS_MSG = "You need %s %s for this!";
    public static String COOLDOWN_ACTIVE_MSG = "Command is on cooldown! Remaining: %.1fs";
    public static String UNKNOWN_COMMAND_MSG = "Unknown command: %s";
    public static String SYNTAX_ERROR_MSG = "Usage: %s";
    public static String COMMAND_ERROR_MSG = "Error: %s";
    public static String UNAVAILABLE_ERROR_MSG = "This command is currently unavailable";
    public static String SUBCOMMAND_PERMISSION_MSG = "You don't have permission for this subcommand!";
    public static String MODULE_DISABLED_MSG = "Error: The module containing the %s command has been disabled.";
    public static String COMMAND_DISABLED_MSG = "Error: The %s command is currently disabled.";
    
    /**
     * Registers a new command with the registry.
     * 
     * @param command The command to register
     */
    public void register(CommandRegistry command) {
        // Register main command
        commands.put(command.getLabel().toLowerCase(), command);
        
        // Register aliases
        for (String alias : command.getAliases()) {
            aliases.put(alias.toLowerCase(), command.getLabel().toLowerCase());
        }
        
        // Add command to associated group
        if (command.getGroup() != null) {
            command.getGroup().addCommand(command.getLabel());
            for (String alias : command.getAliases()) {
                command.getGroup().addCommand(alias);
            }
        }
    }

    /**
     * Unregisters a command from the registry.
     * 
     * @param label The label of the command to unregister
     */
    public void unregister(String label) {
        CommandRegistry command = commands.remove(label.toLowerCase());
        if (command != null) {
            // Remove all aliases
            command.getAliases().forEach(alias -> aliases.remove(alias.toLowerCase()));
            
            // Remove from group
            if (command.getGroup() != null) {
                command.getGroup().commands.remove(command.getLabel().toLowerCase());
                for (String alias : command.getAliases()) {
                    command.getGroup().commands.remove(alias.toLowerCase());
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
        String lookup = input.toLowerCase();
        CommandRegistry direct = commands.get(lookup);
        if (direct != null) return Optional.of(direct);
        
        String aliasTarget = aliases.get(lookup);
        if (aliasTarget != null) {
            return Optional.ofNullable(commands.get(aliasTarget));
        }
        return Optional.empty();
    }

    /**
     * Executes a command with proper validation and error handling.
     * 
     * @param player The player executing the command
     * @param commandInput The command input (main label or alias)
     * @param args The command arguments
     * @return true if command was found and processed, false otherwise
     */
    public boolean execute(Profile player, String commandInput, String[] args) {
        Optional<CommandRegistry> cmdOpt = getCommand(commandInput);
        if (!cmdOpt.isPresent()) {
            player.msg(Main.RED, UNKNOWN_COMMAND_MSG, commandInput);
            return false;
        }

        CommandRegistry cmd = cmdOpt.get();
        
        // Check group-based visibility
        if (!isCommandVisible(player, cmd)) {
            player.msg(Main.RED, UNKNOWN_COMMAND_MSG, commandInput);
            return true;
        }
        
        // Check if module is enabled
        if (!isModuleEnabled(cmd.getModuleName())) {
            player.msg(Main.RED, MODULE_DISABLED_MSG, commandInput.toLowerCase());
            return true;
        }
        
        // Check if command is enabled
        if (!cmd.isEnabled()) {
            player.msg(Main.RED, COMMAND_DISABLED_MSG, commandInput.toLowerCase());
            return true;
        }
        
        // Check specific permission node if configured
        if (cmd.getPermissionNode() != null && !player.vanilla.hasPermission(cmd.getPermissionNode())) {
            player.msg(Main.RED, NO_PERMISSION_MSG);
            return true;
        }
        
        // Check costs
        if (!checkCosts(player, cmd)) {
            return true;
        }
        
        // Check cooldown if applicable
        if (cmd.getCooldownSeconds() > 0 && !cooldownManager.checkCooldown(player.getUuid(), cmd)) {
            player.msg(Main.RED, COOLDOWN_ACTIVE_MSG, 
                      cooldownManager.getRemaining(player.getUuid(), cmd));
            return true;
        }
        
        try {
            // Execute the command
            cmd.getExecutor().execute(player, commandInput, args);
            
            // Apply costs and cooldown after successful execution
            applyCosts(player, cmd);
            if (cmd.getCooldownSeconds() > 0) {
                cooldownManager.applyCooldown(player.getUuid(), cmd);
            }
            return true;
        } catch (CommandException e) {
            handleCommandException(player, cmd, e);
            return true;
        } catch (Exception e) {
            // Handle unexpected exceptions
            player.msg(Main.RED, UNAVAILABLE_ERROR_MSG);
            e.printStackTrace();
            return true;
        }
    }

    /**
     * Checks if a command is visible to a player based on their group.
     * 
     * @param player The player to check
     * @param cmd The command to check visibility for
     * @return true if the command is visible, false otherwise
     */
    private boolean isCommandVisible(Profile player, CommandRegistry cmd) {
        // Check if command is explicitly available in player's group
        return player.getGroup().commands.contains(cmd.getLabel().toLowerCase()) ||
               cmd.getAliases().stream()
                  .anyMatch(alias -> player.getGroup().commands.contains(alias.toLowerCase()));
    }

    /**
     * Checks if a module is enabled.
     * 
     * @param moduleName The name of the module to check
     * @return true if the module is enabled, false otherwise
     */
    private boolean isModuleEnabled(String moduleName) {
        // Implement your module enabled check here
        // This could be from a configuration or module manager
        return Main.getModuleManager().isModuleEnabled(moduleName);
    }
    
    /**
     * Checks if a player has sufficient funds for a command.
     * 
     * @param player The player to check
     * @param cmd The command with associated cost
     * @return true if player can afford the command, false otherwise
     */
    private boolean checkCosts(Profile player, CommandRegistry cmd) {
        if (cmd.getCost() > 0) {
            Currency currency = cmd.getCurrency();
            double balance = player.getBalance(currency);
            
            // Format cost based on currency type
            String formattedCost = formatCurrency(cmd.getCost(), currency);
            
            if (balance < cmd.getCost()) {
                player.msg(Main.RED, INSUFFICIENT_FUNDS_MSG, 
                          formattedCost, currency.name().toLowerCase());
                return false;
            }
        }
        return true;
    }

    /**
     * Formats a currency value appropriately for its type.
     * 
     * @param amount The amount to format
     * @param currency The currency type
     * @return Formatted currency string
     */
    private String formatCurrency(double amount, Currency currency) {
        if (currency.isIntegerType()) {
            return String.valueOf((int) Math.round(amount));
        } else {
            return String.format("%.2f", amount);
        }
    }

    /**
     * Applies command costs to a player after successful execution.
     * 
     * @param player The player to deduct costs from
     * @param cmd The command with associated cost
     */
    private void applyCosts(Profile player, CommandRegistry cmd) {
        if (cmd.getCost() > 0) {
            Currency currency = cmd.getCurrency();
            double amount = cmd.getCost();
            
            // For integer currencies, round to nearest whole number
            if (currency.isIntegerType()) {
                amount = Math.round(amount);
            }
            
            player.removeBalance(amount, currency);
        }
    }

    /**
     * Handles different types of command exceptions with appropriate messaging.
     * 
     * @param player The player who executed the command
     * @param cmd The command that caused the exception
     * @param e The exception that occurred
     */
    private void handleCommandException(Profile player, CommandRegistry cmd, CommandException e) {
        switch (e.getType()) {
            case SYNTAX_ERROR:
                player.msg(Main.RED, COMMAND_ERROR_MSG, e.getMessage());
                if (cmd.getSyntax() != null && !cmd.getSyntax().isEmpty()) {
                    player.msg(Main.RED, SYNTAX_ERROR_MSG, cmd.getSyntax());
                }
                break;
                
            case PERMISSION_ERROR:
                player.msg(Main.RED, NO_PERMISSION_MSG);
                break;
                
            case SUBCOMMAND_PERMISSION:
                player.msg(Main.RED, SUBCOMMAND_PERMISSION_MSG);
                break;
                
            case UNAVAILABLE:
                player.msg(Main.RED, UNAVAILABLE_ERROR_MSG);
                break;
                
            case GENERAL_ERROR:
            default:
                player.msg(Main.RED, COMMAND_ERROR_MSG, e.getMessage());
                break;
        }
    }
}

/**
 * Manages command cooldowns for players.
 */
class CooldownManager {
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    /**
     * Checks if a command is off cooldown for a player.
     * 
     * @param playerId The player's UUID
     * @param command The command to check
     * @return true if command is off cooldown, false otherwise
     */
    public boolean checkCooldown(UUID playerId, CommandRegistry command) {
        if (command.getCooldownSeconds() <= 0) return true;
        
        long current = System.currentTimeMillis();
        Long lastUsed = getCooldownMap(playerId).get(command.getLabel());
        return lastUsed == null || (current - lastUsed) > command.getCooldownSeconds() * 1000L;
    }

    /**
     * Gets the remaining cooldown time for a command.
     * 
     * @param playerId The player's UUID
     * @param command The command to check
     * @return Remaining cooldown time in seconds
     */
    public double getRemaining(UUID playerId, CommandRegistry command) {
        Long lastUsed = getCooldownMap(playerId).get(command.getLabel());
        if (lastUsed == null) return 0;
        
        long elapsed = System.currentTimeMillis() - lastUsed;
        double remaining = command.getCooldownSeconds() - (elapsed / 1000.0);
        return Math.max(0, remaining);
    }

    /**
     * Applies a cooldown to a command for a player.
     * 
     * @param playerId The player's UUID
     * @param command The command to apply cooldown to
     */
    public void applyCooldown(UUID playerId, CommandRegistry command) {
        if (command.getCooldownSeconds() > 0) {
            getCooldownMap(playerId).put(command.getLabel(), System.currentTimeMillis());
        }
    }

    /**
     * Gets the cooldown map for a player, creating if necessary.
     * 
     * @param playerId The player's UUID
     * @return The player's cooldown map
     */
    private Map<String, Long> getCooldownMap(UUID playerId) {
        return cooldowns.computeIfAbsent(playerId, k -> new HashMap<>());
    }
}