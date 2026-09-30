package listeners.block;

import main.Main;
import modules.ModerationModule;
import playerdata.Profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** Prevents world modification when an enabled module forbids it. */
public final class BlockPlace implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Main plugin = Main.getInstance();
        Profile profile = plugin.getProfileManager().resolveOnline(event.getPlayer());
        ModerationModule moderation = plugin.getModuleManager().getModule(ModerationModule.class);

        if (moderation != null && moderation.isEnabled()
                && moderation.shouldCancelWorldInteraction(profile)) event.setCancelled(true);
    }
}
