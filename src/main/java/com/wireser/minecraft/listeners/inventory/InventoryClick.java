package com.wireser.minecraft.listeners.inventory;

import com.wireser.minecraft.TheMinecraftPlugin;
import com.wireser.minecraft.menu.MenuSession;
import com.wireser.minecraft.playerdata.Profile;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Locks plugin menus and routes top-inventory clicks to their menu session. */
public final class InventoryClick implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuSession menu)) return;

        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled || !(event.getWhoClicked() instanceof Player player)) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;

        Profile profile = TheMinecraftPlugin.getInstance().getProfileManager().resolveOnline(player);
        if (!menu.belongsTo(profile)) return;

        menu.click(rawSlot, event.getClick());
    }
}
