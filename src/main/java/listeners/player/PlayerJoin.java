package listeners.player;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import main.Main;
import playerdata.Profile;
import playerdata.ProfileManager;

/**
 * Handles creation of the live player profile when a player joins.
 *
 * <p>Joining establishes or resolves the player's permanent
 * {@code players.id}, hydrates the runtime profile and places that
 * profile into the online cache.</p>
 */
public final class PlayerJoin implements Listener {

    /**
     * Creates and caches the player's online profile.
     *
     * @param event Bukkit player join event
     */
    @EventHandler(priority = EventPriority.LOW)
    public void Main(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        ProfileManager profileManager =
                Main.getInstance().getProfileManager();

        if (profileManager == null) {
            Main.getInstance().getLogger().severe(
                    "Player joined before ProfileManager was initialized: "
                    + player.getName()
            );
            return;
        }

        Profile profile =
                profileManager.createOnlineProfile(player);

        if (profile == null) {
            Main.getInstance().getLogger().severe(
                    "Failed to create online profile for "
                    + player.getName()
                    + " ("
                    + player.getUniqueId()
                    + ")."
            );
            return;
        }

        /*
         * Keep this INFO message while testing the new profile lifecycle.
         * Once we're confident everything works, we can remove/downgrade it.
         */
        Main.getInstance().getLogger().info(
                "Loaded profile for "
                + player.getName()
                + ": playerId="
                + profile.getId()
        );
    }
}