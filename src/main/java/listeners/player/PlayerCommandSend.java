package listeners.player;

import main.Main;
import playerdata.Profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

/** Removes registered plugin commands hidden by the player's effective group. */
public final class PlayerCommandSend implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        Main plugin = Main.getInstance();
        Profile profile = plugin.getProfileManager().resolveOnline(event.getPlayer());
        if (profile == null) return;

        event.getCommands().removeIf(
                command -> !plugin.getCommandCentral().canDiscover(profile, command));
    }
}
