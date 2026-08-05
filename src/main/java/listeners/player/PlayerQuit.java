package listeners.player;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import main.Main;
import playerdata.ProfileManager;

/**
 * Handles plugin cleanup when a player disconnects.
 *
 * <p>The player's online {@code Profile} is removed from the
 * {@link ProfileManager} cache so that session-specific cached data does not
 * remain in memory after the player leaves.</p>
 *
 * <p>This listener does not delete persistent player data.</p>
 */
public final class PlayerQuit implements Listener {

    /**
     * Removes the player's live profile from memory.
     *
     * @param event Bukkit quit event
     */
    @EventHandler(priority = EventPriority.LOW)
    public void Main(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        Main plugin = Main.getInstance();

        if (plugin == null) {
            return;
        }

        ProfileManager profileManager =
                plugin.getProfileManager();

        if (profileManager == null) {
            return;
        }

        profileManager.deleteOnlineProfile(player);
    }
}