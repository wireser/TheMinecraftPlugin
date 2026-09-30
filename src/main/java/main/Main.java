package main;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import command.CommandCentral;
import command.CoreModerationCommands;
import database.Database;
import database.DatabaseAccess;
import managers.ConfigManager;
import managers.LanguageManager;
import managers.ModuleManager;
import managers.YamlLanguageManager;
import menu.MenuManager;
import modules.EconomyModule;
import modules.LocationsModule;
import modules.MenusModule;
import modules.ModerationModule;
import modules.PlayersModule;
import modules.TimersModule;
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

	/** Permanent-ban commands that must remain independent from modules. */
	private CoreModerationCommands coreModerationCommands;

	/** Handles all player profile storage and lifecycle. */
	private ProfileManager profileManager;

	/** Handles enabling, disabling and monitoring of plugin modules. */
	private ModuleManager moduleManager;

	/** Creates per-viewer inventory menu sessions for modules. */
	private MenuManager menuManager;

	private LanguageManager languageManager;

	private ConfigManager configMain;

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
        configMain = new ConfigManager(this, "config");
        configMain.setup();

        database = new Database(this, configMain);
        if (!database.initializeAndStartWatchdog()) {
            return; // database already logged and disabled the plugin
        }

        databaseAccess = new DatabaseAccess(database);

        profileStorage = new ProfileStorage(databaseAccess, getLogger());
        profileManager = new ProfileManager(profileStorage);

        languageManager = new YamlLanguageManager(this);

		commandCentral = new CommandCentral(this, database, languageManager);
		menuManager = new MenuManager(this);
		coreModerationCommands = new CoreModerationCommands(this);
		coreModerationCommands.registerCommands();
		coreModerationCommands.synchronizePermanentBans();

		moduleManager = new ModuleManager(database);

        // Register modules here
		moduleManager.registerModule(new TimersModule());
		moduleManager.registerModule(new ModerationModule());
		moduleManager.registerModule(new PlayersModule());
		moduleManager.registerModule(new LocationsModule());
		moduleManager.registerModule(new EconomyModule());
		moduleManager.registerModule(new MenusModule());

        // Load + enable in dependency order
		registerModules();
		moduleManager.bootstrapModules();
		moduleManager.startScheduler();

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerAsyncPreLogin(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerJoin(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerQuit(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.entity.EntityDeath(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerRespawn(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.inventory.InventoryClick(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.inventory.InventoryDrag(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.inventory.InventoryClose(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerAsyncChat(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerCommandSend(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.block.BlockBreak(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.block.BlockPlace(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerInteract(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerInteractAtEntity(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.player.PlayerDropItem(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.entity.EntityPickupItem(),
		        this
		);

		getServer().getPluginManager().registerEvents(
		        new listeners.entity.EntityDamageByEntity(),
		        this
		);

	}

	/**
	 * Called when the plugin is disabled.
	 * <p>
	 * Ensures orderly shutdown of modules, profiles, database, and tasks.
	 */
	@Override
	public void onDisable() {

		if (moduleManager != null) {
            moduleManager.stopScheduler();
            moduleManager.disableAll();
            moduleManager.shutdownAll();
        }

		if (coreModerationCommands != null) {
			coreModerationCommands.unregisterCommands();
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

    /** Routes Bukkit tab completion through the same central command registry. */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
            String alias, String[] arguments) {
        if (commandCentral == null || profileManager == null
                || !(sender instanceof Player player)) return List.of();

        Profile profile = profileManager.resolveOnline(player);
        if (profile == null) return List.of();

        return commandCentral.complete(profile, command, alias, arguments);
    }


	/**
	 * @return the active plugin instance
	 */
	public static Main getInstance() {
		return instance;
	}

	public ConfigManager getMainConfig() {
        return configMain;
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

	/** @return shared factory for plugin-owned inventory menus */
	public MenuManager getMenuManager() {
		return menuManager;
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

    /*private void muteHikariLoggers() {
        try {
            // Base package logger
            Configurator.setLevel("com.wireser.minecraft.shaded.hikari", Level.OFF);

            // The two specific noisy ones shown in console
            Configurator.setLevel("com.wireser.minecraft.shaded.hikari.HikariDataSource", Level.OFF);
            Configurator.setLevel("com.wireser.minecraft.shaded.hikari.pool.HikariPool", Level.OFF);
        } catch (Throwable t) {
            getLogger().warning("Could not adjust Hikari logger levels via Log4j2. This only affects cosmetic startup logs.");
        }
    }*/

    private void muteHikariLoggers() {
        try {
            Configurator.setLevel(
                    "com.wireser.minecraft.shaded.hikari",
                    Level.WARN
            );
        } catch (RuntimeException | LinkageError exception) {
            getLogger().warning(
                    "Could not adjust Hikari logging. "
                    + "This only affects cosmetic startup messages."
            );
        }
    }

	public DatabaseAccess getDatabaseAccess() {
		return databaseAccess;
	}

}
