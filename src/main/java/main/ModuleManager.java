package main;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle and state of all plugin modules.
 * Provides functionality to register, enable, disable, and reload modules,
 * as well as handle database connectivity changes and configuration repair.
 */
public class ModuleManager {
    private final Map<String, BaseModule> modules = new HashMap<>();
    private boolean lastDatabaseState = true;
    
    /**
     * Registers a module and initializes it.
     * 
     * @param module The module to register
     */
    public void registerModule(BaseModule module) {
        // Load module configuration and resources
        module.load();
        modules.put(module.getModuleName(), module);
        
        // Enable if configured to be enabled
        if (module.isEnabled()) {
            module.enable();
        }
    }
    
    /**
     * Enables a specific module.
     * 
     * @param moduleName The name of the module to enable
     */
    public void enableModule(String moduleName) {
        BaseModule module = modules.get(moduleName);
        if (module != null && !module.isEnabled()) {
            module.enable();
        }
    }
    
    /**
     * Disables a specific module.
     * 
     * @param moduleName The name of the module to disable
     */
    public void disableModule(String moduleName) {
        BaseModule module = modules.get(moduleName);
        if (module != null && module.isEnabled()) {
            module.disable();
        }
    }
    
    /**
     * Disables all currently enabled modules.
     * This should be called during plugin shutdown.
     */
    public void disableAll() {
        for (BaseModule module : modules.values()) {
            if (module.isEnabled()) {
                module.disable();
            }
        }
    }
    
    /**
     * Reloads a specific module's configuration and state.
     * 
     * @param moduleName The name of the module to reload
     */
    public void reloadModule(String moduleName) {
        BaseModule module = modules.get(moduleName);
        if (module != null) {
            // Reload configuration
            module.reload();
            
            // Restart if currently enabled
            if (module.isEnabled()) {
                disableModule(moduleName);
                enableModule(moduleName);
            }
        }
    }
    
    /**
     * Reloads all registered modules.
     */
    public void reloadAllModules() {
        for (BaseModule module : modules.values()) {
            reloadModule(module.getModuleName());
        }
    }
    
    /**
     * Checks if a specific module is enabled.
     * 
     * @param moduleName The name of the module to check
     * @return true if the module is enabled, false otherwise
     */
    public boolean isModuleEnabled(String moduleName) {
        BaseModule module = modules.get(moduleName);
        return module != null && module.isEnabled();
    }
    
    /**
     * Retrieves a module by name.
     * 
     * @param moduleName The name of the module to retrieve
     * @return The module instance, or null if not found
     */
    public BaseModule getModule(String moduleName) {
        return modules.get(moduleName);
    }
    
    /**
     * Enables all registered modules that are not already enabled.
     */
    public void enableAll() {
        for (BaseModule module : modules.values()) {
            if (!module.isEnabled()) {
                module.enable();
            }
        }
    }
    
    /**
     * Gets all registered modules.
     * 
     * @return A list of all registered modules
     */
    public List<BaseModule> getAllModules() {
        return new ArrayList<>(modules.values());
    }
    
    /**
     * Gets all loaded modules (same as getAllModules()).
     * 
     * @return A list of all loaded modules
     */
    public List<BaseModule> getLoadedModules() {
        return new ArrayList<>(modules.values());
    }
    
    /**
     * Gets all currently enabled modules.
     * 
     * @return A list of enabled modules
     */
    public List<BaseModule> getEnabledModules() {
        return modules.values().stream()
            .filter(BaseModule::isEnabled)
            .collect(Collectors.toList());
    }
    
    /**
     * Gets all disabled modules.
     * 
     * @return A list of disabled modules
     */
    public List<BaseModule> getDisabledModules() {
        return modules.values().stream()
            .filter(module -> !module.isEnabled())
            .collect(Collectors.toList());
    }
    
    /**
     * Gets all modules that depend on database connectivity.
     * 
     * @return A list of database-dependent modules
     */
    public List<BaseModule> getDatabaseDependentModules() {
        return modules.values().stream()
            .filter(BaseModule::requiresDatabase)
            .collect(Collectors.toList());
    }
    
    /**
     * Checks the database state and enables/disables modules as needed.
     * This should be called periodically to handle database connectivity changes.
     */
    public void checkDatabaseState() {
        boolean currentState = Database.isAlive();
        if (currentState != lastDatabaseState) {
            if (currentState) {
                onDatabaseOnline();
            } else {
                onDatabaseOffline();
            }
            lastDatabaseState = currentState;
        }
    }
    
    /**
     * Handles actions when the database comes online.
     * Enables database-dependent modules that were disabled due to database unavailability.
     */
    private void onDatabaseOnline() {
        for (BaseModule module : modules.values()) {
            if (module.requiresDatabase() && !module.isEnabled()) {
                // Only enable if dependencies are met
                if (module.checkDependencies()) {
                    module.log("Database online - enabling module");
                    module.enable();
                }
            }
        }
    }
    
    /**
     * Handles actions when the database goes offline.
     * Disables database-dependent modules to prevent errors.
     */
    private void onDatabaseOffline() {
        for (BaseModule module : modules.values()) {
            if (module.requiresDatabase() && module.isEnabled()) {
                module.log("Database offline - disabling module");
                module.disable();
            }
        }
    }
    
    /**
     * Repairs module configurations by reloading defaults.
     * This can be used to recover from corrupted or missing configuration files.
     */
    public void repairModuleConfigs() {
        for (BaseModule module : modules.values()) {
            try {
                // Reload default values
                module.getConfig().options().copyDefaults(true);
                module.saveConfig();
                module.reload();
                module.log("Configuration repaired");
            } catch (Exception e) {
                Main.getInstance().getLogger().warning("[" + module.getModuleName() + "] Config repair failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Shuts down all modules, performing any necessary cleanup.
     * This should be called during plugin shutdown after disableAll().
     */
    public void shutdownAll() {
        for (BaseModule module : modules.values()) {
            try {
                module.shutdown();
            } catch (Exception e) {
                Main.getInstance().getLogger().warning("Error shutting down " + module.getModuleName() + ": " + e.getMessage());
            }
        }
    }
}