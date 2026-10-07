package com.wireser.minecraft;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import com.wireser.minecraft.command.CommandCentral;
import com.wireser.minecraft.command.CoreModerationCommands;
import com.wireser.minecraft.database.Database;
import com.wireser.minecraft.database.DatabaseAccess;
import com.wireser.minecraft.listeners.block.BlockBreak;
import com.wireser.minecraft.listeners.block.BlockPlace;
import com.wireser.minecraft.listeners.entity.EntityDamageByEntity;
import com.wireser.minecraft.listeners.entity.EntityDeath;
import com.wireser.minecraft.listeners.entity.EntityPickupItem;
import com.wireser.minecraft.listeners.inventory.InventoryClick;
import com.wireser.minecraft.listeners.inventory.InventoryClose;
import com.wireser.minecraft.listeners.inventory.InventoryDrag;
import com.wireser.minecraft.listeners.player.PlayerAsyncChat;
import com.wireser.minecraft.listeners.player.PlayerAsyncPreLogin;
import com.wireser.minecraft.listeners.player.PlayerCommandSend;
import com.wireser.minecraft.listeners.player.PlayerDropItem;
import com.wireser.minecraft.listeners.player.PlayerInteract;
import com.wireser.minecraft.listeners.player.PlayerInteractAtEntity;
import com.wireser.minecraft.listeners.player.PlayerJoin;
import com.wireser.minecraft.listeners.player.PlayerQuit;
import com.wireser.minecraft.listeners.player.PlayerRespawn;

import com.wireser.minecraft.managers.ConfigManager;
import com.wireser.minecraft.managers.LanguageManager;
import com.wireser.minecraft.managers.ModuleManager;
import com.wireser.minecraft.managers.YamlLanguageManager;
import com.wireser.minecraft.menu.MenuManager;
import com.wireser.minecraft.modules.EconomyModule;
import com.wireser.minecraft.modules.LocationsModule;
import com.wireser.minecraft.modules.MenusModule;
import com.wireser.minecraft.modules.ModerationModule;
import com.wireser.minecraft.modules.PlayersModule;
import com.wireser.minecraft.modules.TimersModule;
import com.wireser.minecraft.playerdata.Profile;
import com.wireser.minecraft.playerdata.ProfileManager;
import com.wireser.minecraft.playerdata.ProfileStorage;

/**
 * The main entry point of the plugin.
 * <p>
 * Handles plugin lifecycle, initializes core managers,
 * database connection, command routing, and a watchdog that
 * monitors database connectivity in real-time.
 */
public final class TheMinecraftPlugin extends JavaPlugin
{

	/** Singleton instance of this plugin. */
	private static TheMinecraftPlugin instance;

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
    
    private PluginManager pluginManager;

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

		pluginManager = getServer().getPluginManager();
		
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

		initializeModules();
		registerEventListeners();
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

		instance = null;
		
		getLogger().info("Plugin disabled.");

	}

	private void initializeModules() {
		moduleManager = new ModuleManager(database);

		moduleManager.registerModule(new TimersModule());
		moduleManager.registerModule(new ModerationModule());
		moduleManager.registerModule(new PlayersModule());
		moduleManager.registerModule(new LocationsModule());
		moduleManager.registerModule(new EconomyModule());
		moduleManager.registerModule(new MenusModule());

        // Load + enable in dependency order
		moduleManager.bootstrapModules();
		moduleManager.startScheduler();
	}
	
	private void registerEventListeners() {
		pluginManager.registerEvents(new PlayerAsyncPreLogin(), this);
		pluginManager.registerEvents(new PlayerJoin(), this);
		pluginManager.registerEvents(new PlayerQuit(), this);
		pluginManager.registerEvents(new PlayerRespawn(), this);
		pluginManager.registerEvents(new PlayerAsyncChat(), this);
		pluginManager.registerEvents(new PlayerCommandSend(), this);
		pluginManager.registerEvents(new PlayerInteract(), this);
		pluginManager.registerEvents(new PlayerInteractAtEntity(), this);
		pluginManager.registerEvents(new PlayerDropItem(), this);

		pluginManager.registerEvents(new InventoryClick(), this);
		pluginManager.registerEvents(new InventoryDrag(), this);
		pluginManager.registerEvents(new InventoryClose(), this);

		pluginManager.registerEvents(new BlockBreak(), this);
		pluginManager.registerEvents(new BlockPlace(), this);

		pluginManager.registerEvents(new EntityDeath(), this);
		pluginManager.registerEvents(new EntityPickupItem(), this);
		pluginManager.registerEvents(new EntityDamageByEntity(), this);
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

		if (commandCentral == null || profileManager == null) {
	        sender.sendMessage("Commands are not yet available.");
	        return true;
	    }

		/** TODO - Re-house check into Command Central with new attribute and nullable player profiles **/
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
	public static TheMinecraftPlugin getInstance() {
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

}
