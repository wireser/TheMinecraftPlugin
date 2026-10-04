package com.wireser.minecraft.listeners.block;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.modules.ModerationModule;
import com.wireser.minecraft.playerdata.Profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/** Prevents world modification when an enabled module forbids it. */
public final class BlockBreak implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        Profile profile = plugin.getProfileManager().resolveOnline(event.getPlayer());
        ModerationModule moderation = plugin.getModuleManager().getModule(ModerationModule.class);

        if (moderation != null && moderation.isEnabled()
                && moderation.shouldCancelWorldInteraction(profile)) event.setCancelled(true);
    }
}
