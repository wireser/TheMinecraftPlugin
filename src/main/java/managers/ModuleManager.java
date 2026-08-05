package managers;

import database.Database;
import main.Main;
import modules.BaseModule;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle and state of all plugin modules.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Registration of modules</li>
 *     <li>Loading configs / resources</li>
 *     <li>Dependency-aware enable order</li>
 *     <li>Enable / disable / reload individual modules</li>
 *     <li>Bulk operations (reload all, disable all, shutdown all)</li>
 *     <li>Config repair utilities</li>
 * </ul>
 */
public class ModuleManager {

    /** All modules by logical name (e.g. "Economy", "Warps"). */
    private final Map<String, BaseModule> modules = new LinkedHashMap<>();

    @SuppressWarnings("unused")
    private final Database database;

    public ModuleManager(Database database) {
        this.database = database;
    }

    // ---------------------------------------------------------------------
    // REGISTRATION + BOOTSTRAP
    // ---------------------------------------------------------------------

    /**
     * Registers a module instance.
     * <p>
     * This ONLY stores the module – it does NOT load or enable it.
     * Call {@link #bootstrapModules()} once after all modules are registered.
     *
     * @param module module instance
     */
    public void registerModule(BaseModule module) {
        if (module == null) return;

        String name = module.getModuleName();
        BaseModule prev = modules.put(name, module);

        if (prev != null) {
            Main.getInstance().getLogger().warning(
                    "[ModuleManager] Overwriting already registered module '" + name + "'"
            );
        }
    }

    /**
     * Full lifecycle bootstrap, called once from Main.onEnable():
     * <ol>
     *     <li>Call {@code load()} on every registered module
     *         (config, lang, listeners, etc. are handled by BaseModule).</li>
     *     <li>Sort modules based on {@code requiredModules} and {@code preloadBefore}.</li>
     *     <li>Enable modules in that order, but only if their config says enabled.</li>
     * </ol>
     */
    public void bootstrapModules() {
        // 1) Load all modules (config + lang + listeners)
        for (BaseModule module : modules.values()) {
            try {
                module.load();
            } catch (Exception e) {
                Main.getInstance().getLogger().severe(
                        "[ModuleManager] Failed to load module " + module.getModuleName()
                );
                e.printStackTrace();
            }
        }

        // 2) Sort by dependency graph (requiredModules + preload_before)
        List<BaseModule> ordered = sortModulesByDependencies(new ArrayList<>(modules.values()));

        // 3) Enable in order, but only if config says module.enabled: true
        for (BaseModule module : ordered) {
            try {
                if (module.isEnabled()) {      // after load(), this reflects config meta.enabled
                    module.enable();
                } else {
                    module.log("Disabled by configuration (meta.enabled = false)");
                }
            } catch (Exception e) {
                Main.getInstance().getLogger().severe(
                        "[ModuleManager] Failed to enable module " + module.getModuleName()
                );
                e.printStackTrace();
            }
        }
    }

    // ---------------------------------------------------------------------
    // INDIVIDUAL CONTROL
    // ---------------------------------------------------------------------

    /**
     * Enables a specific module (no re-load).
     *
     * @param moduleName logical module name
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
     * @param moduleName logical module name
     */
    public void disableModule(String moduleName) {
        BaseModule module = modules.get(moduleName);
        if (module != null && module.isEnabled()) {
            module.disable();
        }
    }

    /**
     * Disables all currently enabled modules.
     * Should be called during plugin shutdown.
     */
    public void disableAll() {
        for (BaseModule module : modules.values()) {
            if (module.isEnabled()) {
                try {
                    module.disable();
                } catch (Exception e) {
                    Main.getInstance().getLogger().warning(
                            "Error disabling " + module.getModuleName() + ": " + e.getMessage()
                    );
                }
            }
        }
    }

    /**
     * Reloads a specific module's configuration and state.
     *
     * @param moduleName logical module name
     */
    public void reloadModule(String moduleName) {
        BaseModule module = modules.get(moduleName);
        if (module == null) return;

        boolean wasEnabled = module.isEnabled();
        try {
            module.reload();
            // Optional "restart" behaviour (if you want a full cycle):
            if (wasEnabled) {
                module.disable();
                module.enable();
            }
        } catch (Exception e) {
            Main.getInstance().getLogger().warning(
                    "Error reloading module " + moduleName + ": " + e.getMessage()
            );
            e.printStackTrace();
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
     * @param moduleName logical module name
     * @return true if module exists and is currently enabled
     */
    public boolean isModuleEnabled(String moduleName) {
        BaseModule module = modules.get(moduleName);
        return module != null && module.isEnabled();
    }

    /**
     * @param moduleName logical module name
     * @return module instance or null
     */
    public BaseModule getModule(String moduleName) {
        return modules.get(moduleName);
    }

    /**
     * Enables all registered modules that are not already enabled.
     * (Uses current map order, NOT dependency order – for manual use.)
     */
    public void enableAll() {
        for (BaseModule module : modules.values()) {
            if (!module.isEnabled()) {
                module.enable();
            }
        }
    }

    /**
     * @return list of all registered modules.
     */
    public List<BaseModule> getAllModules() {
        return new ArrayList<>(modules.values());
    }

    /**
     * Alias for {@link #getAllModules()} for backwards compatibility.
     */
    public List<BaseModule> getLoadedModules() {
        return getAllModules();
    }

    /**
     * @return list of enabled modules.
     */
    public List<BaseModule> getEnabledModules() {
        return modules.values().stream()
                .filter(BaseModule::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * @return list of disabled modules.
     */
    public List<BaseModule> getDisabledModules() {
        return modules.values().stream()
                .filter(m -> !m.isEnabled())
                .collect(Collectors.toList());
    }

    /**
     * Repairs module configurations by reloading defaults.
     * Useful if configs got corrupted or were edited badly.
     */
    public void repairModuleConfigs() {
        for (BaseModule module : modules.values()) {
            try {
                module.getConfig().options().copyDefaults(true);
                module.saveConfig();
                module.reload();
                module.log("Configuration repaired");
            } catch (Exception e) {
                Main.getInstance().getLogger().warning(
                        "[" + module.getModuleName() + "] Config repair failed: " + e.getMessage()
                );
            }
        }
    }

    /**
     * Shuts down all modules, performing any necessary cleanup.
     * Should be called during plugin shutdown after {@link #disableAll()}.
     */
    public void shutdownAll() {
        for (BaseModule module : modules.values()) {
            try {
                module.shutdown();
            } catch (Exception e) {
                Main.getInstance().getLogger().warning(
                        "Error shutting down " + module.getModuleName() + ": " + e.getMessage()
                );
            }
        }
    }

    // ---------------------------------------------------------------------
    // DEPENDENCY SORT
    // ---------------------------------------------------------------------

    /**
     * Sorts modules based on:
     * <ul>
     *     <li>{@code requiredModules} → A must load/enable before B</li>
     *     <li>{@code preloadBefore}  → A must load/enable before listed modules</li>
     * </ul>
     * If a cycle is detected, falls back to registration order.
     */
    private List<BaseModule> sortModulesByDependencies(List<BaseModule> input) {
        Map<String, Node> nodes = new HashMap<>();

        // Create nodes
        for (BaseModule module : input) {
            nodes.put(module.getModuleName(), new Node(module));
        }

        // Build edges: X -> Y means X must be before Y
        for (BaseModule module : input) {
            Node me = nodes.get(module.getModuleName());

            // requiredModules: each required module must come before this module
            for (String reqName : module.getRequiredModules()) {
                Node dep = nodes.get(reqName);
                if (dep == null) {
                    Main.getInstance().getLogger().warning(
                            "[ModuleManager] Module " + module.getModuleName()
                                    + " requires missing module: " + reqName
                    );
                    continue;
                }
                dep.out.add(me);
                me.in++;
            }

            // preload_before: this module must be before the given modules
            for (String laterName : module.getPreloadBefore()) {
                Node later = nodes.get(laterName);
                if (later == null) {
                    Main.getInstance().getLogger().warning(
                            "[ModuleManager] Module " + module.getModuleName()
                                    + " declares preload_before for missing module: " + laterName
                    );
                    continue;
                }
                me.out.add(later);
                later.in++;
            }
        }

        // Kahn's algorithm (topological sort)
        Deque<Node> queue = new ArrayDeque<>();
        for (Node n : nodes.values()) {
            if (n.in == 0) {
                queue.add(n);
            }
        }

        List<BaseModule> ordered = new ArrayList<>(input.size());

        while (!queue.isEmpty()) {
            Node n = queue.removeFirst();
            ordered.add(n.module);

            for (Node out : n.out) {
                out.in--;
                if (out.in == 0) {
                    queue.add(out);
                }
            }
        }

        // Cycle detection: if something's missing, fall back to registration order
        if (ordered.size() != input.size()) {
            Main.getInstance().getLogger().warning(
                    "[ModuleManager] Detected circular module dependencies – using registration order."
            );
            return input;
        }

        return ordered;
    }

    private static final class Node {
        final BaseModule module;
        int in = 0;
        final List<Node> out = new ArrayList<>();

        Node(BaseModule module) {
            this.module = module;
        }
    }

}