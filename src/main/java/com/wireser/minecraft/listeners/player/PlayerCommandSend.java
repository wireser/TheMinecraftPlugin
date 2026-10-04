package com.wireser.minecraft.listeners.player;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.playerdata.Profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

/** Removes registered plugin commands hidden by the player's effective group. */
public final class PlayerCommandSend implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        Profile profile = plugin.getProfileManager().resolveOnline(event.getPlayer());
        if (profile == null) return;

        event.getCommands().removeIf(
                command -> !plugin.getCommandCentral().canDiscover(profile, command));
    }
}
