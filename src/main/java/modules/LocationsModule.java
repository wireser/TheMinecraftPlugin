package modules;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;

import command.CommandRegistry;
import enums.GroupType;
import playerdata.Profile;
import utils.LocationSerializer;

/**
 * Handles persistent warps and their commands.
 *
 * Death, respawn, join and quit events are handled by the central listeners,
 * not inside this module.
 */
public final class LocationsModule extends BaseModule {

    public LocationsModule() {
        super("Locations", "1.0.0");
    }

    /** Registers commands owned by the Locations module. */
    @Override
    protected void registerCommands() {
        addCommand(new CommandRegistry.Builder("spawn", this::onCommand)
                .aliases("hub")
                .description("Return to the server spawn.")
                .moduleName(getModuleName())
                .minimumGroup(GroupType.PUNISHED)
                .build());

        addCommand(new CommandRegistry.Builder("setspawn", this::onCommand)
                .description("Set the server spawn.")
                .moduleName(getModuleName())
                .minimumGroup(GroupType.ADMIN)
                .build());
    }

    /** Routes registered command labels to their handlers. */
    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null) return false;

        return switch (label.toLowerCase(Locale.ROOT)) {
            case "spawn", "hub" -> handleSpawn(sender);
            case "setspawn" -> handleSetSpawn(sender);
            default -> false;
        };
    }

    /** Teleports the player to spawn and saves their previous location as back. */
    private boolean handleSpawn(Profile profile) {
        Location spawn = getServerSpawnLocation();

        if (spawn == null) {
            profile.sendMessage(getText("locations.spawn.unavailable"));
            return true;
        }

        profile.sendMessage(getText(profile.teleport(spawn)
                ? "locations.spawn.teleported"
                : "locations.spawn.teleport_failed"));

        return true;
    }

    /** Creates or updates the server-owned warp named spawn. */
    private boolean handleSetSpawn(Profile profile) {
        String locationData = LocationSerializer.serialize(profile.getLocation());

        if (locationData == null) {
            profile.sendMessage(getText("locations.spawn.save_failed"));
            return true;
        }

        try {
            getDB().insertIfAbsent(
                    "data_warps_locations",
                    List.of("owner_id", "name", "location_data"),
                    List.of(0, "spawn", locationData)
            );

            getDB().update(
                    "data_warps_locations",
                    List.of(
                            "owner_id",
                            "location_data",
                            "is_public",
                            "is_listed",
                            "is_locked",
                            "updated_at"
                    ),
                    List.of(
                            0,
                            locationData,
                            true,
                            true,
                            false,
                            LocalDateTime.now()
                    ),
                    "name = ?",
                    "spawn"
            );

            profile.sendMessage(getText("locations.spawn.saved"));
        } catch (SQLException exception) {
            logError("Failed to update server spawn.", exception);
            profile.sendMessage(getText("locations.spawn.save_failed"));
        }

        return true;
    }

    /**
     * Loads the usable server-owned spawn warp.
     *
     * Listing does not affect direct use. Private or staff-locked spawn warps
     * are rejected.
     *
     * @return decoded spawn location, or null when unavailable or invalid
     */
    public Location getServerSpawnLocation() {
        try {
            String locationData = getDB().getString(
                    "data_warps_locations",
                    "location_data",
                    "owner_id = ? AND name = ? AND is_public = ? AND is_locked = ?",
                    0,
                    "spawn",
                    true,
                    false
            );

            return LocationSerializer.deserialize(locationData);
        } catch (SQLException exception) {
            logError("Failed to load server spawn.", exception);
            return null;
        }
    }
}