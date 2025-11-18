package modules;

import command.CommandCentral;

public class EmptyModule extends BaseModule {

    public EmptyModule() {
        super("EmptyModule", "1.0.0");
        setDescription("A template module demonstrating proper structure");
        setDefaultEnabled(true);
        setRequiredPlugins("PlaceholderAPI");
        setRequiredModules("Economy");
    }

    @Override
    protected void registerCommands(CommandCentral central) {
        // Register commands here
        // central.register(new CommandRegistry.Builder(...).build());
    }

    @Override
    protected void unregisterCommands(CommandCentral central) {
        // Unregister commands here
        // central.unregister("command1");
        // central.unregister("command2");
    }

    @Override
    protected void onEnable() {
        // Custom enable logic
        // - Start scheduled tasks
        // - Initialize database tables
        // - Register event listeners
    }

    @Override
    protected void onDisable() {
        // Custom disable logic
        // - Save data to database
        // - Cancel scheduled tasks
    }
    
}