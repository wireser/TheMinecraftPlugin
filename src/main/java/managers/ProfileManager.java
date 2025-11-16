package managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import model.Profile;

public final class ProfileManager {

    private final Map<UUID, Profile> profiles = new HashMap<>();

    public Profile get(UUID uuid) {
        return profiles.get(uuid);
    }

    public Profile getOrCreate(Player player) {
        return profiles.computeIfAbsent(player.getUniqueId(), uuid -> new Profile(player));
    }
    
    public void set(UUID uuid, Profile profile) {
        profiles.put(uuid, profile);
    }
    
    public void set(Player player) {
    	profiles.put(player.getUniqueId(), new Profile(player));
    }

    public void remove(UUID uuid) {
        profiles.remove(uuid);
    }
    
    public void remove(Player player) {
    	profiles.remove(player.getUniqueId());
    }

    public void clear() {
        profiles.clear();
    }
    
    public boolean has(UUID uuid) {
        return profiles.containsKey(uuid);
    }

    public int size() {
        return profiles.size();
    }
    
}