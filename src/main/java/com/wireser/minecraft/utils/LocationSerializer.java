package com.wireser.minecraft.utils;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Converts Bukkit {@link Location} objects to and from the compact persistent
 * format used by TheMinecraftPlugin's database location fields.
 *
 * <p>The current stored format is:</p>
 *
 * <pre>
 * version;world-uuid;x;y;z;yaw;pitch
 * </pre>
 *
 * <p>For example:</p>
 *
 * <pre>
 * 1;550e8400-e29b-41d4-a716-446655440000;123.5;64.0;-22.75;90.0;0.0
 * </pre>
 *
 * <p>The format is deliberately compact and independent of world names.
 * World UUIDs are used so renaming a world does not invalidate every stored
 * location. Coordinates retain Bukkit's native precision: {@code double} for
 * X/Y/Z and {@code float} for yaw/pitch.</p>
 *
 * <p>The leading format version allows the persistent representation to evolve
 * later without ambiguously interpreting older database values.</p>
 *
 * <p>Serialization only requires a valid world reference and finite numeric
 * values. Whether that world is currently loaded is runtime state and does not
 * determine whether the location itself is valid for persistence.</p>
 *
 * <p>Deserialization can only produce a usable Bukkit {@link Location} when
 * the referenced world is currently loaded. If the stored value is malformed,
 * unsupported, non-finite, or refers to an unloaded or unknown world,
 * {@code null} is returned.</p>
 */
public final class LocationSerializer {

    private static final String FORMAT_VERSION = "1";
    private static final String DELIMITER = ";";
    private static final int FIELD_COUNT = 7;

    private LocationSerializer() {
        throw new UnsupportedOperationException(
                "LocationSerializer cannot be instantiated."
        );
    }

    /**
     * Serializes a complete Bukkit location into the plugin's persistent
     * database format.
     *
     * <p>The location must contain a world and all coordinate and orientation
     * values must be finite.</p>
     *
     * @param location location to serialize
     * @return serialized database value, or {@code null} when the location
     *         cannot be safely persisted
     */
    public static String serialize(Location location) {
        if (location == null || !location.isFinite()) {
            return null;
        }

        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        return String.join(
                DELIMITER,
                FORMAT_VERSION,
                world.getUID().toString(),
                Double.toString(location.getX()),
                Double.toString(location.getY()),
                Double.toString(location.getZ()),
                Float.toString(location.getYaw()),
                Float.toString(location.getPitch())
        );
    }

    /**
     * Restores a Bukkit location from the plugin's persistent database format.
     *
     * <p>The stored format version must be supported, the world UUID must be
     * valid and currently loaded, and all numeric values must parse to finite
     * coordinates and orientation values.</p>
     *
     * @param serializedLocation stored database value
     * @return restored Bukkit location, or {@code null} when the value is
     *         missing, malformed, unsupported, non-finite, or its world is not
     *         currently loaded
     */
    public static Location deserialize(String serializedLocation) {
        if (serializedLocation == null || serializedLocation.isBlank()) {
            return null;
        }

        String[] parts = serializedLocation.split(DELIMITER, -1);

        if (parts.length != FIELD_COUNT || !FORMAT_VERSION.equals(parts[0])) {
            return null;
        }

        try {
            UUID worldUuid = UUID.fromString(parts[1]);
            World world = Bukkit.getWorld(worldUuid);

            if (world == null) {
                return null;
            }

            Location location = new Location(
                    world,
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Double.parseDouble(parts[4]),
                    Float.parseFloat(parts[5]),
                    Float.parseFloat(parts[6])
            );

            return location.isFinite()
                    ? location
                    : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

}
