package listeners.player;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import main.Main;
import modules.LocationsModule;

/** Selects the plugin respawn point for every Bukkit respawn reason. */
public final class PlayerRespawn implements Listener {

    /**
     * Uses the server spawn warp when it is valid and falls back to the
     * current world's native spawn when it is not.
     *
     * <p>This changes Bukkit's pending respawn location; it is not an
     * additional teleport and must not replace the player's {@code back}
     * location.</p>
     *
     * @param event Bukkit respawn event
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Main plugin = Main.getInstance();
        LocationsModule locations = plugin.getModuleManager().getModule(LocationsModule.class);

        if (locations == null || !locations.isEnabled()) return;

        Location serverSpawn = locations.getServerSpawnLocation();
        event.setRespawnLocation(serverSpawn != null
                ? serverSpawn
                : event.getPlayer().getWorld().getSpawnLocation());
    }
}
