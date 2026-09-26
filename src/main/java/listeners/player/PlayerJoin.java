package listeners.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import main.Main;
import modules.LocationsModule;
import modules.PlayersModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Main plugin = Main.getInstance();

        ProfileManager profileManager =
                plugin.getProfileManager();

        if (profileManager == null) {
            plugin.getLogger().severe(
                    "Player joined before ProfileManager was initialized: "
                    + player.getName()
            );
            return;
        }

        Profile profile =
                profileManager.createOnlineProfile(player);

        if (profile == null) {
            plugin.getLogger().severe(
                    "Failed to create online profile for "
                    + player.getName()
                    + " ("
                    + player.getUniqueId()
                    + ")."
            );
            return;
        }

        /*
         * A clean logout stores "last". Restore it only when another plugin,
         * the server or the client placed the joining player elsewhere. This
         * corrective movement deliberately preserves the existing "back".
         */
        LocationsModule locations = plugin.getModuleManager().getModule(LocationsModule.class);

        if (locations != null && locations.isEnabled()) {
            Location lastLocation = profile.getStoredLocation("last");

            if (lastLocation != null && !lastLocation.equals(player.getLocation())
                    && profile.teleportWithoutSavingBack(lastLocation)) {
                profile.sendMessage(plugin.getLanguageManager().line("locations.last.restored"));
            }
        }

        plugin.getModuleManager().notifyProfileLoaded(profile);

        PlayersModule playersModule = plugin.getModuleManager().getModule(PlayersModule.class);
        if (playersModule != null && playersModule.isEnabled()) {
            playersModule.handlePlayerJoin(profile);
        }

        /*
         * Personal welcomes are supervised prose, not formatting input. One
         * server-owned color keeps them readable and prevents stored style codes.
         */
        String welcomeMessage = profile.getWelcome();
        if (welcomeMessage != null && !welcomeMessage.isBlank()) {
            Component welcomeComponent = Component.text(welcomeMessage, NamedTextColor.LIGHT_PURPLE);
            Component joinMessage = event.joinMessage();
            event.joinMessage(joinMessage == null
                    ? welcomeComponent
                    : welcomeComponent.append(Component.newline()).append(joinMessage));
        }

        /*
         * Keep this INFO message while testing the new profile lifecycle.
         * Once we're confident everything works, we can remove/downgrade it.
         */
        plugin.getLogger().info(
                "Loaded profile for "
                + player.getName()
                + ": playerId="
                + profile.getId()
        );
    }

}
