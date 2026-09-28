package menu;

import java.util.Objects;

import main.Main;
import net.kyori.adventure.text.Component;
import playerdata.Profile;

/**
 * Creates the per-viewer menu sessions used by the plugin.
 *
 * <p>Menu layouts and gameplay decisions remain inside their owning modules.
 * This manager only supplies the shared inventory infrastructure.</p>
 */
public final class MenuManager {

    private final Main plugin;

    public MenuManager(Main plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Creates an empty, fully locked menu for one online profile.
     *
     * @param viewer player who will receive the menu
     * @param size inventory size; a multiple of nine between 9 and 54
     * @param title visible Adventure title; never used as the menu identity
     * @return new menu session ready for its layout to be populated
     */
    public MenuSession create(Profile viewer, int size, Component title) {
        if (viewer == null || !viewer.isOnline()) {
            throw new IllegalArgumentException("A menu requires an online profile.");
        }
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Menu size must be a multiple of nine from 9 to 54.");
        }

        return new MenuSession(plugin, viewer, size, Objects.requireNonNull(title, "title"));
    }
}
