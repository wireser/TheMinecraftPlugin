package main;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import command.CommandCentral;
import database.Database;
import database.DatabaseAccess;
import managers.ConfigManager;
import managers.LanguageManager;
import managers.ModuleManager;
import managers.YamlLanguageManager;
import playerdata.Profile;
import playerdata.ProfileManager;
import playerdata.ProfileStorage;

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

	/** Primary database handler. Initialized on plugin startup. */
	private Database database;

	/** Central command handler used to route commands to subsystems. */
	private CommandCentral commandCentral;

	/** Handles all player profile storage and lifecycle. */
	private ProfileManager profileManager;

	/** Handles enabling, disabling and monitoring of plugin modules. */
	private ModuleManager moduleManager;
	
	private LanguageManager languageManager;
	
	private ConfigManager mainConfig;
	
	private DatabaseAccess databaseAccess;

	/** Storage helper for all profile-related persistence. */
    private ProfileStorage profileStorage;
    
    @Override
    public void onLoad() {
        muteHikariLoggers();
    }

	/**
	 * Called when the plugin is enabled.
	 * <p>
	 * Initializes configuration, database pool, managers, modules,
	 * and starts the database watchdog task.
	 */
	@Override
	public void onEnable() {
		
		instance = this;

		// Config
        mainConfig = new ConfigManager(this, "config");
        mainConfig.setup();

        database = new Database(this, mainConfig);
        if (!database.initializeAndStartWatchdog()) {
            return; // database already logged and disabled the plugin
        }
        
        databaseAccess = new DatabaseAccess(database);

        ProfileStorage storage = new ProfileStorage(databaseAccess, this.getLogger());
        profileManager = new ProfileManager(storage);
        
		languageManager = new YamlLanguageManager(getLogger());
		moduleManager = new ModuleManager(database);
		commandCentral = new CommandCentral(this, database, languageManager);
		
		registerModules();
		
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

	    if (!(sender instanceof Player player)) {
	        sender.sendMessage("This command can only be used by players.");
	        return true;
	    }

	    Profile profile = profileManager.resolveOnline(player);

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

	public ConfigManager getMainConfig() {
        return mainConfig;
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
	
	public DatabaseAccess db() {
	    return databaseAccess;
	}

	/**
	 * @return the shared {@link ProfileStorage} instance used for all
	 *         profile-related database operations.
	 */
	public ProfileStorage getProfileStorage() {
	    return profileStorage;
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

    private void muteHikariLoggers() {
        try {
            // Base package logger
            Configurator.setLevel("com.wireser.minecraft.shaded.hikari", Level.OFF);

            // The two specific noisy ones shown in console
            Configurator.setLevel("com.wireser.minecraft.shaded.hikari.HikariDataSource", Level.OFF);
            Configurator.setLevel("com.wireser.minecraft.shaded.hikari.pool.HikariPool", Level.OFF);
        } catch (Throwable t) {
            getLogger().warning("Could not adjust Hikari logger levels via Log4j2. This only affects cosmetic startup logs.");
        }
    }

	public DatabaseAccess getDatabaseAccess() {
		return databaseAccess;
	}
	
}