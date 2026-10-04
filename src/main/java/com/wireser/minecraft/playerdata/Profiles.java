package com.wireser.minecraft.playerdata;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.wireser.minecraft.TheMinecraftPlugin;

public final class Profiles {

    private static ProfileManager manager() {
        return TheMinecraftPlugin.getInstance().getProfileManager();
    }

    public static Profile of(Player player) {
        return manager().resolveOnline(player);
    }

    public static Profile of(UUID uuid) {
        return manager().resolveByUuid(uuid);
    }

    public static Profile of(int id) {
        return manager().resolveById(id);
    }

    public static Optional<Profile> optional(UUID uuid) {
        return manager().optionalByUuid(uuid);
    }

    public static Optional<Profile> optional(int id) {
        return manager().optionalById(id);
    }

    public static boolean exists(int id) {
        return manager().exists(id);
    }

    public static boolean exists(UUID uuid) {
        return manager().exists(uuid);
    }
    
}