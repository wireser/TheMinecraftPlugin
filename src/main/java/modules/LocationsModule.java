package modules;

import playerdata.Profile;

import java.util.Locale;

import command.CommandRegistry;
import enums.GroupType;
import net.kyori.adventure.text.Component;

public final class LocationsModule extends BaseModule {

    public LocationsModule() {
        super("Locations", "1.0.0");
    }

    @Override
    protected void registerCommands() {

    	addCommand(new CommandRegistry.Builder(
    	        "spawn",
    	        this::onCommand
    	    )
    	    .description("Teleport to spawn.")
    	    .syntax("/spawn")
    	    .moduleName(getModuleName())
    	    .minimumGroup(GroupType.PUNISHED)
    	    .build()
    	);

    	addCommand(new CommandRegistry.Builder(
    	        "setspawn",
    	        this::onCommand
    	    )
    	    .description("Set the server spawn.")
    	    .syntax("/setspawn")
    	    .moduleName(getModuleName())
    	    .minimumGroup(GroupType.ADMIN)
    	    .build()
    	);
    	
    }

    @Override
    public boolean onCommand(Profile sender, String label, String[] args) {
    	
        return switch (label.toLowerCase(Locale.ROOT)) {
        
            case "spawn", "hub" -> handleSpawn(sender);
            case "setspawn" -> true;
            case "home" -> true;
            case "sethome" -> true;
            case "delhome", "deletehome" -> true;
            case "homes" -> true;
            
            default -> true;
            
        };
    }
    
    private boolean handleSpawn(Profile player) {
    	player.sendMessage(Component.text("Hello World!"));
    	return true;
    }
    
}