package main;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import cmd.CommandCentral;
import lib.Profile;
import net.kyori.adventure.text.format.NamedTextColor;

public class Main extends JavaPlugin
{

	public static Main plugin;
	public static FileConfiguration config;
	public static Database database;
	
	public static final NamedTextColor RED         = NamedTextColor.RED;
	public static final NamedTextColor GREEN       = NamedTextColor.GREEN;
	public static final NamedTextColor YELLOW      = NamedTextColor.YELLOW;
	public static final NamedTextColor WHITE       = NamedTextColor.WHITE;
	public static final NamedTextColor GOLD        = NamedTextColor.GOLD;
	public static final NamedTextColor GRAY        = NamedTextColor.GRAY;
	public static final NamedTextColor AQUA        = NamedTextColor.AQUA;
	public static final NamedTextColor BLUE        = NamedTextColor.BLUE;
	public static final NamedTextColor BLACK       = NamedTextColor.BLACK;
	public static final NamedTextColor PURPLE      = NamedTextColor.LIGHT_PURPLE;
	
	public static final NamedTextColor DARK_PURPLE = NamedTextColor.DARK_PURPLE;
	public static final NamedTextColor DARK_RED    = NamedTextColor.DARK_RED;
	public static final NamedTextColor DARK_AQUA   = NamedTextColor.DARK_AQUA;
	public static final NamedTextColor DARK_BLUE   = NamedTextColor.DARK_BLUE;
	public static final NamedTextColor DARK_GRAY   = NamedTextColor.DARK_GRAY;
	public static final NamedTextColor DARK_GREEN  = NamedTextColor.DARK_GREEN;
	
	// Player lists
	public static HashMap<Player, Profile> players = new HashMap<Player, Profile>();

	// Utils
	public static BukkitScheduler scheduler = Bukkit.getServer().getScheduler();
	public static HashMap<World, List<String>> bannedCmds = new HashMap<World, List<String>>();
	public static Random random = new Random();
		
	private static Main instance;
	private static CommandCentral commandCentral;
	private static ModuleManager moduleManager;
	private boolean databaseAlive = true;
	private int watchdogTaskId = -1;
	
	@Override
	public void onEnable() {
		
		plugin = this;

		plugin.saveDefaultConfig();
		
		config = plugin.getConfig();
		
		database = new Database(this);
        try {
            database.init();
        } catch (Exception ex) {
            getLogger().severe("Failed to initialize database pool:");
            ex.printStackTrace();
            // Kill the plugin if DB is critical
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        databaseAlive = database.isAlive();
		
		instance = this;
        moduleManager = new ModuleManager();
        commandCentral = new CommandCentral();
        
        // Register modules
        registerModules();
        
        // Start database watchdog
        startDatabaseWatchdog();

	}
	
	@Override
    public void onDisable() {
		
        // Disable all modules
        moduleManager.disableAll();
        
        if(database != null)
            database.shutdown();
        
        // Stop watchdog
        if(watchdogTaskId != -1)
            getServer().getScheduler().cancelTask(watchdogTaskId);

        getLogger().info("Plugin disabled.");
        
    }
	
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		
		
		
		return true;
		
	}

	public static ModuleManager getModuleManager() {
        return moduleManager;
    }
    
    public static CommandCentral getCommandCentral() {
        return commandCentral;
    }
    
    public static Main getInstance() {
    	return instance;
    }

    private void registerModules() {
    	//moduleManager.registerModule(new EmptyModule());
    }
    
    private void startDatabaseWatchdog() {
        watchdogTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            boolean currentState = database.isAlive();
            if (currentState != databaseAlive) {
                databaseAlive = currentState;
                moduleManager.checkDatabaseState();
                getLogger().info("Database state changed to: " + currentState);
            }
        }, 100L, 100L).getTaskId(); // Check every 5 seconds (100 ticks)
    }
	
}
