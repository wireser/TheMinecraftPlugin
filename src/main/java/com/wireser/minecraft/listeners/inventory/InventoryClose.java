package com.wireser.minecraft.listeners.inventory;

import com.wireser.minecraft.menu.MenuSession;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

/** Ends a menu session when its viewer closes or replaces the inventory. */
public final class InventoryClose implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuSession menu) {
            menu.closed();
        }
    }
}
