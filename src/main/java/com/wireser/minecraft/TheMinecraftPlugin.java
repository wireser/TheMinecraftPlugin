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
import com.wireser.minecraft.utils.PluginLogger;

/**
 * Main Paper entry point for TheMinecraftPlugin.
 *
 * <p>Owns the plugin lifecycle and initializes shared infrastructure,
 * feature modules, command routing, profile services, menus, and event listeners.</p>
 *
 * <p>Startup order is dependency-sensitive, and shutdown is performed in
 * reverse order where practical so dependent services are released safely.</p>
 */
public final class TheMinecraftPlugin extends JavaPlugin
{

    /**
     * Active plugin instance.
     *
     * <p>The singleton exists primarily for legacy/static access from parts of
     * the codebase that are not yet constructor-injected. It is assigned during
     * {@link #onEnable()} and cleared during {@link #onDisable()} so callers do
     * not retain a stale plugin instance after shutdown.</p>
     */
    private static TheMinecraftPlugin instance;

    /** Owns the HikariCP connection pool and database health monitoring. */
    private Database database;

    /** Routes registered plugin commands and tab-completion requests. */
    private CommandCentral commandCentral;

    /**
     * Owns permanent-ban commands that must remain available independently of
     * the optional moderation module.
     */
    private CoreModerationCommands coreModerationCommands;

    /** Owns online profile lifecycle, caching, and player-profile resolution. */
    private ProfileManager profileManager;

    /** Owns registration, dependency ordering, and lifecycle of feature modules. */
    private ModuleManager moduleManager;

    /** Creates and tracks plugin-owned inventory menu sessions. */
    private MenuManager menuManager;

    /** Provides localized/formatted messages to commands and modules. */
    private LanguageManager languageManager;

    /**
     * Manager for the plugin's primary {@code config.yml}.
     *
     * <p>The {@code configMain} naming is deliberate: additional shared
     * configuration files may use the same {@code configXxx} convention.</p>
     */
    private ConfigManager configMain;

    /** Shared low-level database access facade used by persistence services. */
    private DatabaseAccess databaseAccess;

    /**
     * Core persistence service for profile-related data.
     *
     * <p>Runtime online {@link Profile} objects are owned by
     * {@link ProfileManager}; this object is responsible for loading and
     * persisting their backing data.</p>
     */
    private ProfileStorage profileStorage;

    /** Cached Bukkit plugin manager used during listener registration. */
    private PluginManager pluginManager;

    /**
     * Performs pre-enable setup that must happen before normal plugin startup.
     *
     * <p>The Hikari logger adjustment intentionally runs during the load phase
     * so Hikari's startup logging is configured before the database pool is
     * created.</p>
     */
    @Override
    public void onLoad() {
        instance = this;
        PluginLogger.configure(this);
        muteHikariLoggers();
    }

    /**
     * Initializes the plugin and all shared runtime services.
     *
     * <p>Initialization is intentionally fail-fast around the database because
     * persistent player data is a core dependency of the plugin. If database
     * initialization fails, {@link Database#initializeAndStartWatchdog()}
     * disables the plugin and this method stops immediately.</p>
     */
    @Override
    public void onEnable() {

        pluginManager = getServer().getPluginManager();

        // Primary plugin configuration. Feature modules manage their own
        // module-specific configuration separately.
        configMain = new ConfigManager(this, "config");
        configMain.setup();

        database = new Database(this, configMain);
        if (!database.initializeAndStartWatchdog()) {
            return; // Failure has already been logged and plugin disable requested.
        }

        // Persistence infrastructure is layered deliberately:
        // Database -> DatabaseAccess -> ProfileStorage -> ProfileManager.
        databaseAccess = new DatabaseAccess(database);

        profileStorage = new ProfileStorage(databaseAccess, getLogger());
        profileManager = new ProfileManager(profileStorage);

        languageManager = new YamlLanguageManager(this);

        commandCentral = new CommandCentral(this, database, languageManager);
        menuManager = new MenuManager(this);

        /*
         * Permanent bans are core safety functionality rather than an optional
         * moderation-module feature, so their commands are registered before
         * feature modules are initialized.
         */
        coreModerationCommands = new CoreModerationCommands(this);
        coreModerationCommands.registerCommands();
        coreModerationCommands.synchronizePermanentBans();

        initializeModules();
        registerEventListeners();
    }

    /**
     * Shuts the plugin down in dependency-safe order.
     *
     * <p>Modules are stopped first so they can release runtime state while
     * profiles and database access are still available. Profiles are then
     * cleared, followed by the database pool. Every step is null-safe because
     * Paper may invoke this method after a partial startup failure.</p>
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

    /**
     * Creates, registers, and boots all feature modules.
     *
     * <p>Registration order acts as the stable fallback order when the module
     * dependency graph cannot produce a valid ordering. Normal startup is
     * dependency-aware and is handled by {@link ModuleManager#bootstrapModules()}.
     * The shared module scheduler starts only after bootstrap completes.</p>
     */
    private void initializeModules() {
        moduleManager = new ModuleManager(database);

        moduleManager.registerModule(new TimersModule());
        moduleManager.registerModule(new ModerationModule());
        moduleManager.registerModule(new PlayersModule());
        moduleManager.registerModule(new LocationsModule());
        moduleManager.registerModule(new EconomyModule());
        moduleManager.registerModule(new MenusModule());

        moduleManager.bootstrapModules();
        moduleManager.startScheduler();
    }

    /**
     * Registers the core Bukkit/Paper event listeners owned by the plugin.
     *
     * <p>Listeners are listed explicitly instead of discovered reflectively so
     * registration remains compiler-visible, easy to audit, and predictable.
     * Module-specific behavior may still be delegated from these listeners to
     * enabled modules.</p>
     */
    private void registerEventListeners() {
        // Player lifecycle, communication, and interaction.
        pluginManager.registerEvents(new PlayerAsyncPreLogin(), this);
        pluginManager.registerEvents(new PlayerJoin(), this);
        pluginManager.registerEvents(new PlayerQuit(), this);
        pluginManager.registerEvents(new PlayerRespawn(), this);
        pluginManager.registerEvents(new PlayerAsyncChat(), this);
        pluginManager.registerEvents(new PlayerCommandSend(), this);
        pluginManager.registerEvents(new PlayerInteract(), this);
        pluginManager.registerEvents(new PlayerInteractAtEntity(), this);
        pluginManager.registerEvents(new PlayerDropItem(), this);

        // Inventory/menu interaction.
        pluginManager.registerEvents(new InventoryClick(), this);
        pluginManager.registerEvents(new InventoryDrag(), this);
        pluginManager.registerEvents(new InventoryClose(), this);

        // Block protection and interaction.
        pluginManager.registerEvents(new BlockBreak(), this);
        pluginManager.registerEvents(new BlockPlace(), this);

        // Entity lifecycle and interaction.
        pluginManager.registerEvents(new EntityDeath(), this);
        pluginManager.registerEvents(new EntityPickupItem(), this);
        pluginManager.registerEvents(new EntityDamageByEntity(), this);
    }

    /**
     * Routes Bukkit command execution into the plugin command subsystem.
     *
     * <p>Commands currently require a loaded online {@link Profile}, so console
     * senders are rejected at this boundary. Console-capable commands should
     * eventually be handled by {@link CommandCentral} using command metadata
     * rather than being special-cased in the plugin entry point.</p>
     *
     * @param sender command sender supplied by Bukkit
     * @param cmd command being executed
     * @param label label or alias used to invoke the command
     * @param args command arguments excluding the label
     * @return {@code true} when the command was handled
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (commandCentral == null || profileManager == null) {
            sender.sendMessage("Commands are not yet available.");
            return true;
        }

        // TODO: Move sender capability checks into CommandCentral. Commands
        // should declare whether console execution is permitted; player-only
        // commands will continue to require a non-null Profile.
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
     * Routes Bukkit tab-completion requests through the central command registry.
     *
     * <p>Completion is available only when the command subsystem is initialized
     * and the sender has an active online profile. Returning an empty list tells
     * Bukkit that the plugin has no completions to offer for the current state.</p>
     *
     * @param sender command sender requesting completion
     * @param command command being completed
     * @param alias label or alias used by the sender
     * @param arguments current command arguments
     * @return completion candidates, or an empty list when completion is unavailable
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
            String alias, String[] arguments) {
        if (commandCentral == null || profileManager == null
                || !(sender instanceof Player player)) {
            return List.of();
        }

        Profile profile = profileManager.resolveOnline(player);
        if (profile == null) {
            return List.of();
        }

        return commandCentral.complete(profile, command, alias, arguments);
    }

    /**
     * Returns the currently active plugin instance.
     *
     * @return active plugin instance, or {@code null} before enable or after disable
     */
    public static TheMinecraftPlugin getInstance() {
        return instance;
    }

    /**
     * Returns the manager for the plugin's primary configuration file.
     *
     * @return main configuration manager
     */
    public ConfigManager getMainConfig() {
        return configMain;
    }

    /**
     * Returns the shared language/message service.
     *
     * @return active language manager
     */
    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    /**
     * Returns the database lifecycle and connection-pool manager.
     *
     * @return database manager
     */
    public Database getDatabase() {
        return database;
    }

    /**
     * Returns the shared low-level database access facade.
     *
     * <p>This short accessor is intended for persistence-oriented code that
     * needs direct access to the common CRUD/query helpers.</p>
     *
     * @return shared database access facade
     */
    public DatabaseAccess db() {
        return databaseAccess;
    }

    /**
     * Returns the core profile persistence service.
     *
     * <p>This service persists profile-related data; callers looking for active
     * in-memory player profiles should use {@link #getProfileManager()} instead.</p>
     *
     * @return shared profile storage service
     */
    public ProfileStorage getProfileStorage() {
        return profileStorage;
    }

    /**
     * Returns the player-profile lifecycle manager.
     *
     * @return profile manager containing active online profiles and lookup helpers
     */
    public ProfileManager getProfileManager() {
        return profileManager;
    }

    /**
     * Returns the feature-module lifecycle manager.
     *
     * @return module manager
     */
    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    /**
     * Returns the shared inventory-menu manager.
     *
     * @return menu manager
     */
    public MenuManager getMenuManager() {
        return menuManager;
    }

    /**
     * Returns the central plugin command registry/router.
     *
     * @return command central
     */
    public CommandCentral getCommandCentral() {
        return commandCentral;
    }

    /**
     * Reduces HikariCP startup noise to warnings and errors.
     *
     * <p>The Hikari package is shaded into the plugin namespace at build time,
     * so the logger name must match the relocated package exactly. This setup
     * intentionally runs during {@link #onLoad()} before the pool is created.</p>
     */
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