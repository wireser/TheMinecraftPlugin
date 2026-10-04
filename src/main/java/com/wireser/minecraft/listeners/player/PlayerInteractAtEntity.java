package com.wireser.minecraft.listeners.player;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.modules.ModerationModule;
import com.wireser.minecraft.playerdata.Profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

/** Routes entity interaction restrictions through the central listener layer. */
public final class PlayerInteractAtEntity implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        Profile profile = plugin.getProfileManager().resolveOnline(event.getPlayer());
        ModerationModule moderation = plugin.getModuleManager().getModule(ModerationModule.class);

        if (moderation != null && moderation.isEnabled()
                && moderation.shouldCancelWorldInteraction(profile)) event.setCancelled(true);
    }
}
