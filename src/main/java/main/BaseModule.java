package main;

import cmd.CommandCentral;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import utils.ConfigManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseModule implements Listener {
    // Module metadata
    protected final String moduleName;
    protected final String moduleVersion;
    private String description;
    
    // Module state
    protected boolean enabled;
    private boolean defaultEnabled;
    
    // Dependencies
    private String[] requiredPlugins = new String[0];
    private String[] requiredModules = new String[0];
    private boolean requiresDatabase = false;
    
    // Configuration
    protected ConfigManager configManager;
    protected FileConfiguration config;
    
    // Translatable texts
    protected Map<String, String> texts = new HashMap<>();
    
    public BaseModule(String moduleName, String moduleVersion) {
        this.moduleName = moduleName;
        this.moduleVersion = moduleVersion;
        this.enabled = false;
        this.defaultEnabled = true;
        this.description = "No description provided";
    }
    
    /**
     * Called when the module is loaded (before enabling)
     */
    public void load() {
        // 1. Setup configuration
        configManager = new ConfigManager(Main.getInstance(), "modules/" + moduleName.toLowerCase());
        configManager.setup();
        config = configManager.getConfig();
        
        // 2. Set default configuration values
        config.addDefault("module.version", moduleVersion);
        config.addDefault("module.enabled", defaultEnabled);
        config.addDefault("module.description", description);
        config.addDefault("module.requiresDatabase", requiresDatabase);
        config.addDefault("module.requiredPlugins", requiredPlugins);
        config.addDefault("module.requiredModules", requiredModules);
        
        // Add default texts
        config.addDefault("texts.module-disabled", "Error: The module containing the %s command has been disabled.");
        config.addDefault("texts.command-disabled", "Error: The %s command is currently disabled.");
        // Add other default texts
        
        config.options().copyDefaults(true);
        configManager.saveConfig();
        
        // 3. Load actual values from config (with fallback to defaults)
        loadFromConfig();
        
        // 4. Register events if needed
        Main.getInstance().getServer().getPluginManager().registerEvents(this, Main.getInstance());
    }
    
    /**
     * Loads values from configuration with fallbacks
     */
    protected void loadFromConfig() {
        // Load module settings
        enabled = config.getBoolean("module.enabled", defaultEnabled);
        description = config.getString("module.description", description);
        requiresDatabase = config.getBoolean("module.requiresDatabase", requiresDatabase);
        requiredPlugins = config.getStringList("module.requiredPlugins").toArray(new String[0]);
        requiredModules = config.getStringList("module.requiredModules").toArray(new String[0]);
        
        // Load texts
        loadTexts();
    }
    
    /**
     * Loads translatable texts from configuration
     */
    protected void loadTexts() {
        texts.clear();
        if (config.contains("texts")) {
            for (String key : config.getConfigurationSection("texts").getKeys(false)) {
                texts.put(key, config.getString("texts." + key));
            }
        }
    }
    
    /**
     * Called when the module is enabled
     */
    public void enable() {
        // 1. Check dependencies
        if (!checkDependencies()) {
            log("Disabled due to missing dependencies");
            return;
        }
        
        // 2. Check database dependency
        if (requiresDatabase && !Database.isAlive()) {
            log("Database unavailable - disabling module");
            enabled = false;
            return;
        }
        
        // 3. Register commands
        registerCommands(Main.getCommandCentral());
        
        // 4. Additional enable logic
        onEnable();
        
        enabled = true;
        log("Enabled v" + moduleVersion);
    }
    
    /**
     * Called when the module is disabled
     */
    public void disable() {
        // 1. Unregister commands
        unregisterCommands(Main.getCommandCentral());
        
        // 2. Additional disable logic
        onDisable();
        
        enabled = false;
        log("Disabled");
    }
    
    /**
     * Reloads module configuration
     */
    public void reload() {
        configManager.reloadConfig();
        config = configManager.getConfig();
        loadFromConfig();
        onReload();
        log("Configuration reloaded");
    }
    
    /**
     * Performs complete shutdown of the module.
     * This should release all resources and perform final cleanup.
     */
    public void shutdown() {
        // Close any open resources
        // Cancel any remaining tasks
        // Release file locks
        log("Shut down complete");
    }
    
    /**
     * Register all module commands
     */
    protected abstract void registerCommands(CommandCentral central);
    
    /**
     * Unregister all module commands
     */
    protected abstract void unregisterCommands(CommandCentral central);
    
    /**
     * Custom enable logic (override if needed)
     */
    protected void onEnable() {}
    
    /**
     * Custom disable logic (override if needed)
     */
    protected void onDisable() {}
    
    /**
     * Custom reload logic (override if needed)
     */
    protected void onReload() {}
    
    /**
     * Check if all dependencies are available
     */
    protected boolean checkDependencies() {
        // Check required plugins
        for (String plugin : requiredPlugins) {
            if (Main.getInstance().getServer().getPluginManager().getPlugin(plugin) == null) {
                return false;
            }
        }
        
        // Check required modules
        for (String module : requiredModules) {
            if (!Main.getModuleManager().isModuleEnabled(module)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Logs a message to console
     */
    public void log(String message) {
        Main.getInstance().getLogger().info("[" + moduleName + "] " + message);
    }
    
    /**
     * Logs a message to database
     */
    public void logToDatabase(String message) {
        if (Database.isAlive()) {
            Database.insert("log_modules", "module, message", "'" + moduleName + "','" + message + "'");
        } else {
            log("(DB offline) " + message);
        }
    }
    
    /**
     * Gets a translated text with placeholders
     */
    public String getText(String key, Object... args) {
        String text = texts.getOrDefault(key, key);
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", args[i].toString());
        }
        return text;
    }
    
    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public String getModuleName() { return moduleName; }
    public String getModuleVersion() { return moduleVersion; }
    public String getDescription() { return description; }
    public FileConfiguration getConfig() { return config; }
    public boolean requiresDatabase() { return requiresDatabase; }
    
    public void setDescription(String description) { 
        this.description = description;
        config.set("module.description", description);
        saveConfig();
    }
    
    public void setDefaultEnabled(boolean defaultEnabled) { 
        this.defaultEnabled = defaultEnabled;
        config.set("module.enabled", defaultEnabled);
        saveConfig();
    }
    
    public void setRequiredPlugins(String... plugins) { 
        this.requiredPlugins = plugins; 
        config.set("module.requiredPlugins", Arrays.asList(plugins));
        saveConfig();
    }
    
    public void setRequiredModules(String... modules) { 
        this.requiredModules = modules; 
        config.set("module.requiredModules", Arrays.asList(modules));
        saveConfig();
    }
    
    public void setRequiresDatabase(boolean requires) {
        this.requiresDatabase = requires;
        config.set("module.requiresDatabase", requires);
        saveConfig();
    }
    
    public void saveConfig() { 
        configManager.saveConfig(); 
    }
}