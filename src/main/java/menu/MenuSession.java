package menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import enums.GroupType;
import main.Main;
import net.kyori.adventure.text.Component;
import playerdata.Profile;

/**
 * One hardcoded inventory layout opened for one specific player.
 *
 * <p>The session itself is the custom {@link InventoryHolder}, so listeners
 * can recognize plugin menus without inspecting their visible titles. Each
 * clickable slot stores its action beside its item, preventing the layout and
 * a separate slot switch from drifting apart.</p>
 */
public final class MenuSession implements InventoryHolder {

    private final Main plugin;
    private final Profile viewer;
    private final Inventory inventory;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    private final Set<Integer> occupiedSlots = new HashSet<>();
    private final List<AccessRequirement> accessRequirements = new ArrayList<>();
    private boolean open;

    MenuSession(Main plugin, Profile viewer, int size, Component title) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    /**
     * Places a clickable item into the menu.
     *
     * @param slot raw slot inside this menu
     * @param material displayed vanilla material
     * @param name Adventure display name
     * @param lore Adventure lore lines
     * @param action action executed on the next server tick after a click
     */
    public void button(int slot, Material material, Component name, List<Component> lore,
            MenuAction action) {
        button(slot, MenuItemBuilder.of(material).name(name).lore(lore).build(), action);
    }

    /** Places a clickable item with one lore line. */
    public void button(int slot, Material material, Component name, Component lore,
            MenuAction action) {
        button(slot, material, name, List.of(lore), action);
    }

    /** Places a cloned custom stack as a clickable icon. */
    public void button(int slot, ItemStack item, MenuAction action) {
        setSlot(slot, item, Objects.requireNonNull(action, "action"), false);
    }

    /**
     * Deliberately replaces a previously assigned slot with a new button.
     * Normal {@link #button(int, ItemStack, MenuAction)} calls reject duplicates.
     */
    public void replaceButton(int slot, ItemStack item, MenuAction action) {
        setSlot(slot, item, Objects.requireNonNull(action, "action"), true);
    }

    /** Places a cloned non-clickable stack for decoration or information. */
    public void item(int slot, ItemStack item) {
        setSlot(slot, item, null, false);
    }

    /** Deliberately replaces a previously assigned slot with a non-clickable item. */
    public void replaceItem(int slot, ItemStack item) {
        setSlot(slot, item, null, true);
    }

    /** Removes an assigned icon and its action, allowing the slot to be reused. */
    public void clearSlot(int slot) {
        validateSlot(slot);
        inventory.clear(slot);
        occupiedSlots.remove(slot);
        actions.remove(slot);
    }

    /** Fills every unassigned slot with an independent clone of one item. */
    public void fillEmpty(ItemStack filler) {
        ItemStack safeFiller = MenuItemBuilder.requireUsableItem(filler);

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!occupiedSlots.contains(slot)) setSlot(slot, safeFiller, null, false);
        }
    }

    /** Fills every unassigned slot with an unnamed item of one material. */
    public void fillEmpty(Material material) {
        fillEmpty(MenuItemBuilder.of(material).name(Component.empty()).build());
    }

    /** Restricts this menu to the supplied effective group and its descendants. */
    public MenuSession requireMinimumGroup(GroupType minimumGroup, Component denialMessage) {
        Objects.requireNonNull(minimumGroup, "minimumGroup");
        return requireAccess(profile -> profile.meetsMinimumGroup(minimumGroup), denialMessage);
    }

    /** Restricts this menu using a Bukkit permission node or plugin command key. */
    public MenuSession requirePermission(String permissionNode, Component denialMessage) {
        if (permissionNode == null || permissionNode.isBlank()) {
            throw new IllegalArgumentException("Menu permission cannot be blank.");
        }

        return requireAccess(profile -> profile.hasPermission(permissionNode), denialMessage);
    }

    /**
     * Adds an arbitrary access rule for owner-only or similarly private menus.
     * Every rule registered on the session must pass before it can open.
     */
    public MenuSession requireAccess(Predicate<Profile> accessRule, Component denialMessage) {
        accessRequirements.add(new AccessRequirement(
                Objects.requireNonNull(accessRule, "accessRule"),
                Objects.requireNonNull(denialMessage, "denialMessage")));
        return this;
    }

    /** Creates a reusable action that executes a command and closes this menu. */
    public MenuAction runCommand(String command) {
        return runCommand(command, true, null);
    }

    /** Creates a reusable command action with explicit close behaviour. */
    public MenuAction runCommand(String command, boolean closeMenu) {
        return runCommand(command, closeMenu, null);
    }

    /**
     * Creates a reusable command action with optional failure feedback.
     * A leading slash is accepted but removed before dispatch.
     */
    public MenuAction runCommand(String command, boolean closeMenu, Component failureMessage) {
        String preparedCommand = prepareCommand(command);

        return click -> {
            if (closeMenu) click.viewer().getPlayer().closeInventory();

            boolean handled = click.viewer().getPlayer().performCommand(preparedCommand);
            if (!handled && failureMessage != null) click.viewer().sendMessage(failureMessage);
        };
    }

    /** Opens this completed menu for its viewer. */
    public boolean open() {
        if (!viewer.isOnline()) return false;

        AccessRequirement deniedRequirement = findDeniedRequirement();
        if (deniedRequirement != null) {
            viewer.sendMessage(deniedRequirement.denialMessage());
            return false;
        }

        open = true;
        viewer.getPlayer().openInventory(inventory);
        return true;
    }

    /** Returns whether the supplied profile owns this per-viewer session. */
    public boolean belongsTo(Profile profile) {
        return profile != null && viewer.getUuid().equals(profile.getUuid());
    }

    /**
     * Queues one registered action outside the inventory click event.
     *
     * <p>Opening or closing inventories directly inside a click event is not
     * safe on every server version. Deferring the action by one tick keeps menu
     * navigation and command execution outside that event.</p>
     */
    public void click(int slot, ClickType clickType) {
        MenuAction action = actions.get(slot);
        if (action == null || !open) return;

        MenuClick click = new MenuClick(viewer, slot, clickType);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!open || !viewer.isOnline()) return;

            AccessRequirement deniedRequirement = findDeniedRequirement();
            if (deniedRequirement != null) {
                viewer.getPlayer().closeInventory();
                viewer.sendMessage(deniedRequirement.denialMessage());
                return;
            }

            try {
                action.execute(click);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Menu action failed for " + viewer.getIgn()
                        + " in slot " + slot + ".", exception);
            }
        });
    }

    /** Marks the session closed so delayed clicks cannot run afterward. */
    public void closed() {
        open = false;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void setSlot(int slot, ItemStack item, MenuAction action, boolean replace) {
        validateSlot(slot);
        if (!replace && occupiedSlots.contains(slot)) {
            throw new IllegalStateException("Menu slot " + slot + " is already assigned. "
                    + "Use replaceButton or replaceItem when replacement is intentional.");
        }

        inventory.setItem(slot, MenuItemBuilder.requireUsableItem(item).clone());
        occupiedSlots.add(slot);

        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
    }

    private boolean hasAccess(AccessRequirement requirement) {
        try {
            return requirement.rule().test(viewer);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Menu access check failed for " + viewer.getIgn() + ".", exception);
            return false;
        }
    }

    private AccessRequirement findDeniedRequirement() {
        for (AccessRequirement requirement : accessRequirements) {
            if (!hasAccess(requirement)) return requirement;
        }

        return null;
    }

    private void validateSlot(int slot) {
        if (slot < 0 || slot >= inventory.getSize()) {
            throw new IllegalArgumentException("Menu slot is outside the inventory: " + slot);
        }
    }

    private static String prepareCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Menu command cannot be blank.");
        }

        String prepared = command.trim();
        if (prepared.startsWith("/")) prepared = prepared.substring(1).trim();
        if (prepared.isBlank() || prepared.indexOf('\n') >= 0 || prepared.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Menu command must contain one command line.");
        }

        return prepared;
    }

    /** Immutable information supplied to a menu button action. */
    public record MenuClick(Profile viewer, int slot, ClickType clickType) {}

    /** Behaviour registered for one clickable menu slot. */
    @FunctionalInterface
    public interface MenuAction {
        void execute(MenuClick click);
    }

    /** One access rule and the player-facing message used when it fails. */
    private record AccessRequirement(Predicate<Profile> rule, Component denialMessage) {}
}
