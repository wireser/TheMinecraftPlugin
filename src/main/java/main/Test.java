package main;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.material.*;
import org.bukkit.plugin.java.JavaPlugin;

public class Test extends JavaPlugin implements Listener {

    private Map<Location, ItemStack> bottleHeads = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        // Set up the 3x3 wall with heads
        setupWall();
    }

    private void setupWall() {
        // Assume the wall is at y=64, with x=100 and z=100
        for (int x = 100; x < 103; x++) {
            for (int z = 100; z < 103; z++) {
                Location loc = new Location(getServer().getWorld("world"), x, 64, z);
                // Place a head on each block (you can choose a specific "bottle" head)
                SkullMeta meta = (SkullMeta) Bukkit.getItemFactory().getItemMeta(Material.PLAYER_HEAD);
                meta.setOwningPlayer(Bukkit.getOfflinePlayer("bottle-head-username"));
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                head.setItemMeta(meta);
                loc.getBlock().setType(Material.PLAYER_HEAD);
                loc.getBlock().setMetadata("bottleHead", new FixedMetadataValue(this, head));
                bottleHeads.put(loc, head);
            }
        }
    }

    @EventHandler
    public void onSnowballHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Snowball) {
            Location hitLocation = event.getEntity().getLocation();
            for (Map.Entry<Location, ItemStack> entry : bottleHeads.entrySet()) {
                if (hitLocation.getBlock().getLocation().equals(entry.getKey())) {
                    // A snowball hit a bottle!
                    Bukkit.getServer().broadcastMessage("You hit a bottle!");
                    // You could remove or replace the head here
                    entry.getKey().getBlock().setType(Material.AIR); // Remove head
                }
            }
        }
    }
}