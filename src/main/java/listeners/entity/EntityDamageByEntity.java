package listeners.entity;

import main.Main;
import modules.ModerationModule;
import playerdata.Profile;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

/** Prevents Marked players from damaging entities directly or by projectile. */
public final class EntityDamageByEntity implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player player) attacker = player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) attacker = player;
        }
        if (attacker == null) return;

        Main plugin = Main.getInstance();
        Profile profile = plugin.getProfileManager().resolveOnline(attacker);
        ModerationModule moderation = plugin.getModuleManager().getModule(ModerationModule.class);

        if (moderation != null && moderation.isEnabled()
                && moderation.shouldCancelWorldInteraction(profile)) event.setCancelled(true);
    }
}
