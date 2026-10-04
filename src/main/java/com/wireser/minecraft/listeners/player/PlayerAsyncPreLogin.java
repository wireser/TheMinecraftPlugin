package com.wireser.minecraft.listeners.player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.modules.ModerationModule;
import net.kyori.adventure.text.Component;

/** Rejects active temporary bans before Bukkit creates an online player. */
public final class PlayerAsyncPreLogin implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        if (plugin == null || plugin.getModuleManager() == null) return;

        ModerationModule moderation =
                plugin.getModuleManager().getModule(ModerationModule.class);
        if (moderation == null || !moderation.isEnabled()) return;

        Component disconnectMessage =
                moderation.getTemporaryBanLoginMessage(event.getUniqueId());
        if (disconnectMessage != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
        }
    }
}
