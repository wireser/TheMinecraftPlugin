package utils;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Converts Bukkit locations to and from the compact format stored by the
 * plugin's location tables.
 *
 * <p>The stored form is:</p>
 *
 * <pre>world-uuid;x;y;z;yaw;pitch</pre>
 *
 * <p>A world UUID is used instead of its display name so renaming a world does
 * not invalidate every saved location. Java's numeric conversion methods are
 * locale independent, which prevents decimal commas from corrupting the
 * semicolon-delimited value.</p>
 */
public final class LocationSerializer {

    private LocationSerializer() {
        // Utility class; instances carry no state.
    }

    /**
     * Serializes a complete, finite location.
     *
     * @param location location to serialize
     * @return database-safe location string, or {@code null} when invalid
     */
    public static String serialize(Location location) {
        if (location == null || !location.isWorldLoaded() || !location.isFinite()) return null;

        World world = location.getWorld();
        if (world == null) return null;

        return String.join(";",
                world.getUID().toString(),
                Double.toString(location.getX()),
                Double.toString(location.getY()),
                Double.toString(location.getZ()),
                Float.toString(location.getYaw()),
                Float.toString(location.getPitch()));
    }

    /**
     * Restores a serialized location only when its world is currently loaded
     * and every coordinate is finite.
     *
     * @param serializedLocation value created by {@link #serialize(Location)}
     * @return usable Bukkit location, or {@code null} when invalid
     */
    public static Location deserialize(String serializedLocation) {
        if (serializedLocation == null || serializedLocation.isBlank()) return null;

        String[] parts = serializedLocation.split(";", -1);
        if (parts.length != 6) return null;

        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world == null) return null;

            Location location = new Location(world,
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5]));

            return location.isFinite() ? location : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
