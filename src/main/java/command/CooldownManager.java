package command;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player, per-command cooldowns.
 * <p>
 * Cooldowns are tracked using the command's normalized label and the player's UUID.
 * Time is stored in milliseconds since {@link System#currentTimeMillis()}.
 *
 * <p><b>Threading:</b> This class is not thread-safe. It is intended to be used
 * from the main server thread only, which is typical for Bukkit/Paper plugins.
 */
public final class CooldownManager {

    /**
     * Top-level map: player UUID -> (command key -> last used timestamp in ms).
     */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    /**
     * Generates the internal key used to store cooldown data for the given command.
     * <p>
     * Uses the command's normalized label in lowercase.
     *
     * @param command the command registry entry
     * @return a normalized string key for cooldown tracking
     */
    private String key(CommandRegistry command) {
        // label is already normalized to lowercase, but we enforce it here defensively
        return command.getLabel().toLowerCase(Locale.ROOT);
    }

    /**
     * Checks whether the given command is off cooldown for a specific player.
     *
     * @param playerId the UUID of the player
     * @param command  the command to check
     * @return {@code true} if the command can be executed (no cooldown or expired),
     *         {@code false} if it is still on cooldown
     */
    public boolean checkCooldown(UUID playerId, CommandRegistry command) {
        int cooldownSeconds = command.getCooldownSeconds();
        if (cooldownSeconds <= 0) {
            return true; // no cooldown configured
        }

        long current = System.currentTimeMillis();
        Long lastUsed = getCooldownMap(playerId).get(key(command));
        return lastUsed == null || (current - lastUsed) > cooldownSeconds * 1000L;
    }

    /**
     * Gets the remaining cooldown time in seconds for a command and player.
     *
     * @param playerId the UUID of the player
     * @param command  the command to check
     * @return remaining cooldown time in seconds (0 if no cooldown or already expired)
     */
    public double getRemaining(UUID playerId, CommandRegistry command) {
        Long lastUsed = getCooldownMap(playerId).get(key(command));
        if (lastUsed == null) {
            return 0;
        }

        int cooldownSeconds = command.getCooldownSeconds();
        long elapsed = System.currentTimeMillis() - lastUsed;
        double remaining = cooldownSeconds - (elapsed / 1000.0);
        return Math.max(0, remaining);
    }

    /**
     * Applies a cooldown for the given command and player starting from now.
     *
     * @param playerId the UUID of the player
     * @param command  the command to apply cooldown to
     */
    public void applyCooldown(UUID playerId, CommandRegistry command) {
        int cooldownSeconds = command.getCooldownSeconds();
        if (cooldownSeconds > 0) {
            getCooldownMap(playerId).put(key(command), System.currentTimeMillis());
        }
    }

    /**
     * Clears all cooldowns for a specific player.
     *
     * @param playerId the UUID of the player whose cooldowns should be cleared
     */
    public void clearPlayer(UUID playerId) {
        cooldowns.remove(playerId);
    }

    /**
     * Clears cooldowns for a specific command across all players.
     *
     * @param command the command whose cooldown entries should be removed
     */
    public void clearCommand(CommandRegistry command) {
        String commandKey = key(command);
        for (Map<String, Long> perPlayer : cooldowns.values()) {
            perPlayer.remove(commandKey);
        }
    }

    /**
     * Clears all cooldown data for all players and commands.
     * <p>
     * Useful for full reloads, debug resets, or when disabling a module.
     */
    public void clearAll() {
        cooldowns.clear();
    }

    /**
     * Returns a snapshot of the current cooldown state.
     * <p>
     * The returned map and its nested maps are unmodifiable copies and
     * cannot be used to mutate the internal cooldown state.
     *
     * @return an unmodifiable view of the current cooldown data:
     *         {@code Map&lt;UUID, Map&lt;String, Long&gt;&gt;}
     */
    public Map<UUID, Map<String, Long>> snapshot() {
        Map<UUID, Map<String, Long>> copy = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> entry : cooldowns.entrySet()) {
            // Copy inner map to avoid exposing mutable internal state
            copy.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Gets (or lazily creates) the cooldown map for a specific player.
     *
     * @param playerId the UUID of the player
     * @return a mutable map of command key -> last used timestamp for that player
     */
    private Map<String, Long> getCooldownMap(UUID playerId) {
        return cooldowns.computeIfAbsent(playerId, k -> new HashMap<>());
    }
    
}