package com.wireser.minecraft.listeners.player;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.modules.ModerationModule;
import com.wireser.minecraft.playerdata.Profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import io.papermc.paper.event.player.AsyncChatEvent;

/** Applies centrally ordered chat restrictions owned by enabled modules. */
public final class PlayerAsyncChat implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        Profile profile = plugin.getProfileManager().getOnlineProfile(event.getPlayer().getUniqueId());
        ModerationModule moderation = plugin.getModuleManager().getModule(ModerationModule.class);

        if (moderation != null && moderation.isEnabled()
                && moderation.shouldCancelChat(profile)) event.setCancelled(true);
    }
}
