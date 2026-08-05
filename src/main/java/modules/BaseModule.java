package modules;

import command.CommandCentral;
import command.CommandRegistry;
import main.Main;
import managers.ConfigManager;
import managers.LanguageManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import playerdata.Profile;
import playerdata.ProfileManager;

import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Common base for all modules.
 *
 * Handles:
 * - module config + language loading
 * - auto registration/unregistration of commands
 * - dynamic listener registration from modules.<name>.listeners
 * - convenience accessors for DB, profiles, server, logging, etc.
 */
public abstract class BaseModule implements Listener {

    // Core wiring
    protected final Main plugin;
    protected final String moduleName;

    // Config + language
    protected ConfigManager configManager;
    protected FileConfiguration config;
    protected final LanguageManager lang;

    // Metadata
    protected String moduleVersion;
    protected String description = "No description provided";
    protected boolean enabled = false;

    // Dependencies (configured via YAML, not hard-coded)
    protected List<String> requiredPlugins = new ArrayList<>();
    protected List<String> requiredModules = new ArrayList<>();
    
    /**
     * Modules that should be loaded/enabled AFTER this one.
     * Used by ModuleManager for dependency-aware ordering.
     */
    private java.util.List<String> preloadBefore = new java.util.ArrayList<>();

    // Commands owned by this module
    private final List<CommandRegistry> registeredCommands = new ArrayList<>();

    protected BaseModule(String moduleName, String defaultVersion) {
        this.plugin = Main.getInstance();
        this.moduleName = moduleName;
        this.moduleVersion = defaultVersion;
        this.lang = plugin.getLanguageManager(); // global LanguageManager (per-module files are handled by it)
    }

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    /**
     * Called once when the module is constructed and the plugin is starting.
     * Sets up config and reads metadata.
     */
    public final void load() {
        // Config is always: <dataFolder>/modules/<modulename>/config.yml
        this.configManager = new ConfigManager(plugin, "modules/" + moduleName.toLowerCase(Locale.ROOT) + "/config");
        this.configManager.setup();
        this.config = configManager.getConfig();

        loadMetaFromConfig();
        onLoad();
    }

    /**
     * Reads module meta from YAML.
     *
     * Expected structure:
     *
     * meta:
     *   name: "EmptyModule"
     *   description: "..."
     *   version: "1.0.0"
     *   enabled: true
     *   required_plugins: []
     *   required_modules: []
     */
    protected void loadMetaFromConfig() {
        // Ensure meta section exists
        if (!config.isConfigurationSection("meta")) {
            config.createSection("meta");
        }

        // Read values (with sane defaults)
        String cfgVersion   = config.getString("meta.version", this.moduleVersion);
        String cfgDesc      = config.getString("meta.description", this.description);
        boolean cfgEnabled  = config.getBoolean("meta.enabled", true);

        List<String> plugins = config.getStringList("meta.required_plugins");
        List<String> modules = config.getStringList("meta.required_modules");
        List<String> preload = config.getStringList("meta.preload_before");

        // Apply to fields
        this.moduleVersion = cfgVersion;
        this.description   = cfgDesc;
        this.enabled       = cfgEnabled;

        requiredPlugins.clear();
        requiredPlugins.addAll(plugins);

        requiredModules.clear();
        requiredModules.addAll(modules);

        preloadBefore.clear();
        preloadBefore.addAll(preload);

        // Persist back (so defaults get written if missing)
        config.set("meta.version",         this.moduleVersion);
        config.set("meta.description",     this.description);
        config.set("meta.enabled",         this.enabled);
        config.set("meta.required_plugins", new ArrayList<>(requiredPlugins));
        config.set("meta.required_modules", new ArrayList<>(requiredModules));
        config.set("meta.preload_before",   new ArrayList<>(preloadBefore));

        saveConfig();
    }

    /**
     * Plugin is enabling this module.
     */
    public final void enable() {
        if (!enabled) {
            log("Not enabled in config, skipping.");
            return;
        }

        if (!checkDependencies()) {
            log("Disabled due to missing dependencies.");
            enabled = false;
            return;
        }

        // Let module define commands (BaseModule will register them)
        registerCommands();

        // Register all commands with CommandCentral
        CommandCentral central = plugin.getCommandCentral();
        for (CommandRegistry cmd : registeredCommands) {
            central.register(cmd);
        }

        // Register listeners from modules.<modulename>.listeners.*
        registerListenersDynamically();

        onEnable();
        log("Enabled v" + moduleVersion);
    }

    /**
     * Plugin is disabling this module.
     */
    public final void disable() {
        if (!enabled) {
            return;
        }

        // Unregister commands
        CommandCentral central = plugin.getCommandCentral();
        for (CommandRegistry cmd : registeredCommands) {
            central.unregister(cmd.getLabel());
        }
        registeredCommands.clear();

        onDisable();
        enabled = false;
        log("Disabled.");
    }

    /**
     * Reload config + language and call module hook.
     */
    public final void reload() {
        configManager.reloadConfig();
        this.config = configManager.getConfig();
        loadMetaFromConfig(); // re-read meta
        onReload();
        log("Configuration reloaded.");
    }

    /**
     * Called on plugin shutdown for final cleanup.
     */
    public final void shutdown() {
        onShutdown();
        log("Shut down complete.");
    }

    // =====================================================================
    // HOOKS FOR MODULES
    // =====================================================================

    /**
     * Called once after config has been loaded in {@link #load()}.
     */
    protected void onLoad() {
        // Optional override
    }

    /**
     * Called when the module is enabled and dependencies are satisfied.
     */
    protected void onEnable() {
        // Optional override
    }

    /**
     * Called when the module is disabled.
     */
    protected void onDisable() {
        // Optional override
    }

    /**
     * Called after config reload.
     */
    protected void onReload() {
        // Optional override
    }

    /**
     * Called on plugin shutdown.
     */
    protected void onShutdown() {
        // Optional override
    }

    /**
     * Module-level /meta command entry point (if you bind a command to call this).
     * Return true if you handled the command.
     */
    public boolean onCommand(Profile sender, String label, String[] args) {
        // Optional override by modules
        return false;
    }

    /**
     * Module must create its commands here using {@link #addCommand(CommandRegistry)}.
     * BaseModule handles registration with CommandCentral.
     */
    protected abstract void registerCommands();

    /**
     * Add a command definition owned by this module.
     */
    protected final void addCommand(CommandRegistry command) {
        if (command == null) return;
        registeredCommands.add(command);
    }

    // =====================================================================
    // DEPENDENCY CHECKING
    // =====================================================================

    /**
     * Checks Bukkit plugin + module dependencies.
     */
    public boolean checkDependencies() {
        // External plugins
    	for (String pluginName : requiredPlugins) {
    	    org.bukkit.plugin.Plugin dep = plugin.getServer()
    	            .getPluginManager()
    	            .getPlugin(pluginName);

    	    if (dep == null || !dep.isEnabled()) {
    	        log("Missing or disabled required plugin: " + pluginName);
    	        return false;
    	    }
    	}

        // Other modules
        for (String moduleId : requiredModules) {
            if (moduleId == null || moduleId.isEmpty()) continue;

            BaseModule m = plugin.getModuleManager().getModule(moduleId);
            if (m == null || !m.isEnabled()) {
                log("Missing or disabled required module: " + moduleId);
                return false;
            }
        }

        return true;
    }

    // =====================================================================
    // LISTENER REGISTRATION
    // =====================================================================

    /**
     * Scans the JAR for classes under modules.<moduleName>.listeners and registers all
     * that implement {@link Listener}.
     *
     * Supported constructors in listener classes:
     * - (BaseModule)
     * - (Main)
     * - no-arg
     */
    private void registerListenersDynamically() {
        String pkg = "modules." + moduleName.toLowerCase(Locale.ROOT) + ".listeners";
        String path = pkg.replace('.', '/');

        try {
            ClassLoader cl = plugin.getClass().getClassLoader();
            Enumeration<URL> resources = cl.getResources(path);
            if (!resources.hasMoreElements()) {
                return; // no listeners package
            }

            PluginManager pm = plugin.getServer().getPluginManager();
            Set<String> classNames = new HashSet<>();

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String urlStr = url.toString();

                if (urlStr.startsWith("jar:file:")) {
                    String jarPath = urlStr.substring("jar:file:".length(), urlStr.indexOf("!"));
                    try (JarFile jar = new JarFile(jarPath)) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith(path) && name.endsWith(".class") && !entry.isDirectory()) {
                                String className = name.replace('/', '.').substring(0, name.length() - 6);
                                classNames.add(className);
                            }
                        }
                    }
                }
            }

            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className, true, plugin.getClass().getClassLoader());
                    if (!Listener.class.isAssignableFrom(clazz)) {
                        continue;
                    }

                    Listener listener = instantiateListener(clazz);
                    if (listener != null) {
                        pm.registerEvents(listener, plugin);
                        log("Registered listener: " + className);
                    }
                } catch (ClassNotFoundException ignored) {
                    // Skip invalid classes
                }
            }

        } catch (Exception e) {
            log("Failed to scan/register listeners for package: " + path + " (" + e.getMessage() + ")");
        }
    }

    private Listener instantiateListener(Class<?> clazz) {
        try {
            // Try (BaseModule)
            try {
                return (Listener) clazz.getConstructor(BaseModule.class).newInstance(this);
            } catch (NoSuchMethodException ignored) {}

            // Try (Main)
            try {
                return (Listener) clazz.getConstructor(Main.class).newInstance(plugin);
            } catch (NoSuchMethodException ignored) {}

            // Try no-arg
            return (Listener) clazz.getConstructor().newInstance();

        } catch (Exception e) {
            log("Failed to instantiate listener " + clazz.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // CONVENIENCE GETTERS (what you asked for)
    // =====================================================================

    // --- Database ---

    /**
     * Database access helper (wrapper, not raw Hikari / Connection).
     */
    public database.DatabaseAccess getDB() {
        return plugin.getDatabaseAccess();
    }

    // --- Profile / player access ---

    private ProfileManager profiles() {
        return plugin.getProfileManager();
    }

    public Profile getPlayer(Player bukkit) {
        if (bukkit == null) return null;
        Profile p = profiles().resolveByUuid(bukkit.getUniqueId());
        if (p != null && p.getPlayer() != null) {
            return p;
        }
        return null;
    }

    public Profile getPlayer(UUID uuid) {
        if (uuid == null) return null;
        Profile p = profiles().resolveByUuid(uuid);
        if (p != null && p.getPlayer() != null) {
            return p;
        }
        return null;
    }

    public Profile getPlayer(String ign) {
        if (ign == null || ign.isEmpty()) return null;
        Player online = plugin.getServer().getPlayerExact(ign);
        if (online == null) return null;
        return getPlayer(online);
    }

    public Profile getPlayer(int id) {
        if (id <= 0) return null;
        Profile p = profiles().resolveById(id);
        if (p != null && p.getPlayer() != null) {
            return p;
        }
        return null;
    }

    /**
     * Offline lookup: returns a profile even if the player is not online,
     * or {@code null} if they do not exist in the database.
     */
    public Profile getOfflinePlayer(UUID uuid) {
        if (uuid == null) return null;
        return profiles().resolveByUuid(uuid);
    }

    public Profile getOfflinePlayer(int id) {
        if (id <= 0) return null;
        return profiles().resolveById(id);
    }

    /**
     * Name-based offline lookup. Uses Bukkit offline player cache + ProfileManager.
     */
    public Profile getOfflinePlayer(String ign) {
        if (ign == null || ign.isEmpty()) return null;
        // Prefer online
        Profile online = getPlayer(ign);
        if (online != null) return online;

        // Fallback: offline by name via Bukkit -> UUID -> ProfileManager
        java.util.UUID uuid = null;
        try {
            // 1.20+ has getOfflinePlayerIfCached; fall back to legacy if needed
            org.bukkit.OfflinePlayer off = plugin.getServer().getOfflinePlayer(ign);
            if (off != null && off.hasPlayedBefore()) {
                uuid = off.getUniqueId();
            }
        } catch (Throwable ignored) {}

        if (uuid == null) return null;
        return profiles().resolveByUuid(uuid);
    }

    // --- Config / language ---

    public FileConfiguration getConfig() {
        return config;
    }

    /**
     * Text from language manager (component).
     */
    public Component getText(String key) {
        return lang.get(key);
    }

    /**
     * Text with positional placeholders (%1, %2, ...).
     */
    public Component getText(String key, Object... args) {
        return lang.get(key, args);
    }

    // --- Console / server / world / logger ---

    public ConsoleCommandSender getConsole() {
        return plugin.getServer().getConsoleSender();
    }

    public Server getServer() {
        return plugin.getServer();
    }

    public World getWorld(String worldName) {
        if (worldName == null || worldName.isEmpty()) return null;
        return plugin.getServer().getWorld(worldName);
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    // --- Other modules ---

    /**
     * Get another module by name. Version check is up to the caller.
     */
    public BaseModule getModule(String name) {
        if (name == null || name.isEmpty()) return null;
        return plugin.getModuleManager().getModule(name);
    }

    /**
     * Get another module, returning null if version does not match exactly.
     */
    public BaseModule getModule(String name, String version) {
        BaseModule other = getModule(name);
        if (other == null) return null;
        if (version == null || version.isEmpty()) return other;
        if (version.equalsIgnoreCase(other.getModuleVersion())) {
            return other;
        }
        return null;
    }

    // =====================================================================
    // MISC
    // =====================================================================

    public boolean isEnabled() {
        return enabled;
    }

    public String getModuleName() {
        return moduleName;
    }

    public String getModuleVersion() {
        return moduleVersion;
    }

    public String getDescription() {
        return description;
    }

    public void saveConfig() {
        configManager.saveConfig();
    }

    public void log(String message) {
        getLogger().info("[" + moduleName + "] " + message);
    }
    

	/**
	 * Modules this module depends on (must exist and be enabled).
	 */
	public List<String> getRequiredModules() {
	    return Collections.unmodifiableList(requiredModules);
	}
	
	/**
	 * Modules that should be loaded/enabled BEFORE this module.
	 * Comes from meta.preload_before in the module config.
	 */
	public List<String> getPreloadBefore() {
	    return Collections.unmodifiableList(preloadBefore);
	}
	
	/**
	 * Plugins that must be present/enabled for this module to work.
	 * (Optional, but nice to expose.)
	 */
	public List<String> getRequiredPlugins() {
	    return Collections.unmodifiableList(requiredPlugins);
	}

}