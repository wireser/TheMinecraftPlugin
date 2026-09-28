package listeners.inventory;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;

import menu.MenuSession;

/** Prevents drag operations from inserting or removing items in plugin menus. */
public final class InventoryDrag implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuSession) {
            event.setCancelled(true);
        }
    }
}
