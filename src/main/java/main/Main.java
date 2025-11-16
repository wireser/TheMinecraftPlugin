package main;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import command.CommandCentral;
import database.Database;
import managers.LanguageManager;
import managers.ModuleManager;
import managers.ProfileManager;
import managers.SimpleLanguageManager;
import model.Profile;

/**
 * The main entry point of the plugin.
 * <p>
 * Handles plugin lifecycle, initializes core managers,
 * database connection, command routing, and a watchdog that
 * monitors database connectivity in real-time.
 */
public class Main extends JavaPlugin
{

	/** Singleton instance of this plugin. */
	private static Main instance;

	/** Tracks whether the database was alive during the last watchdog check. */
	private boolean databaseAlive = true;

	/** Task ID for the repeating database watchdog task. */
	private int watchdogTaskId = -1;

	/** Primary database handler. Initialized on plugin startup. */
	private Database database;

	/** Central command handler used to route commands to subsystems. */
	private CommandCentral commandCentral;

	/** Handles all player profile storage and lifecycle. */
	private ProfileManager profileManager;

	/** Handles enabling, disabling and monitoring of plugin modules. */
	private ModuleManager moduleManager;
	
	private SimpleLanguageManager languageManager;
	
	
	/**
	 * Called when the plugin is enabled.
	 * <p>
	 * Initializes configuration, database pool, managers, modules,
	 * and starts the database watchdog task.
	 */
	@Override
	public void onEnable() {
		
		instance = this;
		saveDefaultConfig();

		database = new Database(this);
		try {
			database.init();
		} catch (Exception ex) {
			getLogger().severe("Failed to initialize database pool:");
			ex.printStackTrace();
			
			getServer().getPluginManager().disablePlugin(this); // Kill the plugin if DB is critical
			return;
		}
		
		databaseAlive = database.isAlive();

		languageManager = new SimpleLanguageManager(); 
		profileManager = new ProfileManager();
		moduleManager = new ModuleManager(this, database);
		commandCentral = new CommandCentral(this, database, languageManager);
		
		registerModules();
		startDatabaseWatchdog();

	}
	
	/**
	 * Called when the plugin is disabled.
	 * <p>
	 * Ensures orderly shutdown of modules, profiles, database, and tasks.
	 */
	@Override
	public void onDisable() {

		if (moduleManager != null) {
			moduleManager.disableAll();
		}

		if (profileManager != null) {
			profileManager.clear();
		}

		if (database != null) {
			database.shutdown();
		}

		if (watchdogTaskId != -1) {
			getServer().getScheduler().cancelTask(watchdogTaskId);
		}

		getLogger().info("Plugin disabled.");
		
	}
	
	/**
	 * Command routing entry point.
	 * <p>
	 * Delegates command processing to {@link CommandCentral}, ensuring that
	 * commands are coming from players and that player profiles exist.
	 *
	 * @param sender the entity that issued the command
	 * @param cmd	the command being executed
	 * @param label  command alias used
	 * @param args   command arguments
	 * @return true if the command was handled
	 */
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

		if (commandCentral == null) {
			sender.sendMessage("Commands are not yet available.");
			return true;
		}

		if (!(sender instanceof Player)) {
			sender.sendMessage("This command can only be used by players.");
			return true;
		}

		Player player = (Player) sender;
		Profile profile = profileManager.get(player.getUniqueId());
		
		if (profile == null) {
			sender.sendMessage("Your profile is not loaded yet. Please try again in a moment.");
			return true;
		}

		return commandCentral.execute(profile, cmd, label, args);
	}

	/**
	 * @return the active plugin instance
	 */
	public static Main getInstance() {
		return instance;
	}

	/**
	 * Convenience wrapper for accessing the plugin configuration.
	 *
	 * @return the plugin configuration
	 */
	public static FileConfiguration config() {
		return getInstance().getConfig();
	}

	public LanguageManager getLanguageManager() {
        return languageManager;
    }
	
	/**
	 * @return the database handler
	 */
	public Database getDatabase() {
		return database;
	}

	/**
	 * @return the profile manager responsible for all player profiles
	 */
	public ProfileManager getProfileManager() {
		return profileManager;
	}

	/**
	 * @return the module manager responsible for enabling/disabling components
	 */
	public ModuleManager getModuleManager() {
		return moduleManager;
	}

	/**
	 * @return the central command handler
	 */
	public CommandCentral getCommandCentral() {
		return commandCentral;
	}

	/**
	 * Registers all plugin modules.
	 * <p>
	 * Stub method — extend this to add module initialization.
	 */
	private void registerModules() {
		// moduleManager.registerModule(new EmptyModule());
	}

	/**
	 * Starts a periodic watchdog task that monitors database connectivity.
	 * <p>
	 * If the connection state changes, all modules are notified so they
	 * can adjust behavior accordingly.
	 */
	private void startDatabaseWatchdog() {
		
		watchdogTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
			
			if(database == null) {
				return;
			}
			
			boolean currentState = database.isAlive();
			
			if (currentState != databaseAlive) {
				databaseAlive = currentState;
				
				if(moduleManager != null) {
					moduleManager.checkDatabaseState();
				}

				getLogger().info("Database state changed to: " + (currentState ? "ALIVE" : "DOWN"));
			}
			
		}, 100L, 100L).getTaskId(); // Check every 5 seconds (100 ticks)
		
	}
	
}
