package managers;

import java.util.*;
import java.util.stream.Collectors;

import database.Database;
import modules.BaseModule;
import main.Main;

/**
 * Manages the lifecycle and state of all plugin modules.
 * Provides functionality to register, enable, disable, and reload modules,
 * as well as handle database connectivity changes and configuration repair.
 */
public class ModuleManager {
	
    private final Map<String, BaseModule> modules = new HashMap<>();
    
    
    @SuppressWarnings("unused")
	private final Database database;
    
    public ModuleManager(Database database) {
        this.database = database;
    }
    
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