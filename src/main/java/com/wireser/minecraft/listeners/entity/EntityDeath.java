package com.wireser.minecraft.listeners.entity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.modules.LocationsModule;
import com.wireser.minecraft.playerdata.Profile;

/**
 * Central entry point for entity-death behaviour.
 *
 * <p>A single listener keeps future player, mob, karma and quest reactions in
 * an explicit order instead of scattering competing death listeners through
 * unrelated modules.</p>
 */
public final class EntityDeath implements Listener {

    /** Saves the death point of a player while the Locations module is active. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        TheMinecraftPlugin plugin = TheMinecraftPlugin.getInstance();
        LocationsModule locations = plugin.getModuleManager().getModule(LocationsModule.class);

        if (locations == null || !locations.isEnabled()) return;

        Profile profile = plugin.getProfileManager().resolveOnline(player);
        if (profile != null) profile.setStoredLocation("death", player.getLocation());
    }
}
