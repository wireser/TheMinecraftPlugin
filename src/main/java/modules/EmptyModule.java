package modules;

import command.CommandRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import playerdata.Profile;

/**
 * Example module showing how to:
 * - access config + language
 * - access profiles and database
 * - register commands
 * - use the BaseModule helpers
 */
public final class EmptyModule extends BaseModule {

    public EmptyModule() {
        // moduleName = "EmptyModule", defaultVersion = "1.0.0"
        super("EmptyModule", "1.0.0");
    }

    @Override
    protected void onLoad() {
        // Called after config is available.
        // Config: modules/emptymodule/config.yml

        // Example: read a custom setting
        boolean debug = getConfig().getBoolean("config.debug", false);
        if (debug) {
            log("Debug mode enabled from config.");
        }
    }

    @Override
    protected void registerCommands() {
        // Example command: /empty
        addCommand(new CommandRegistry.Builder("empty", this::handleEmptyCommand)
            .description("Demo command from EmptyModule")
            .syntax("/empty [name]")
            .moduleName(getModuleName())
            // Example: cost + currency
            //.cost(10.0, Currency.MONEY)
            // Example: permission
            //.permissionNode("tmp.empty.use")
            .build()
        );
    }

    /**
     * Command handler for /empty.
     *
     * Signature matches CommandHandler:
     * (Profile sender, String label, String[] args)
     */
    private void handleEmptyCommand(Profile sender, String label, String[] args) {
        // Example: use language messages
        // lang.yml under: modules/emptymodule/lang.yml
        //
        // empty.hello: "&aHello from EmptyModule!"
        // empty.hello_name: "&aHello %1, welcome to EmptyModule!"
        //

        Component msg;
        if (args.length >= 1) {
            msg = getText("empty.hello_name", args[0]);
        } else {
            msg = getText("empty.hello");
        }

        sender.sendMessage(msg);

        // Example: log to console
        getLogger().info("[EmptyModule] /empty used by " + sender.getIgn());

        // Example: use profile helpers
        // getPlayer / getOfflinePlayer / getDB etc.
    }

    @Override
    protected void onEnable() {
        // Called after:
        // - config/meta loaded
        // - dependencies checked
        // - commands registered into CommandCentral
        // - listeners auto-registered (if any in modules.emptymodule.listeners)

        log("EmptyModule is now active.");
    }

    @Override
    protected void onDisable() {
        // Called when the module is disabled.
        log("EmptyModule is now disabled.");
    }

    @Override
    protected void onReload() {
        // Called after BaseModule.reload() reloaded config + meta.
        log("EmptyModule config reloaded.");
    }

    @Override
    public boolean onCommand(Profile sender, String label, String[] args) {
        // Optional: if you map a "module meta" command to this.
        // For example: /module empty reload → call this from your ModuleManager.
    	
        if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(Component.text("Module: " + getModuleName(), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Version: " + getModuleVersion(), NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Enabled: " + isEnabled(), NamedTextColor.GOLD));
            return true;
        }

        return false;
    }

}