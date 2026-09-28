package menu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

/**
 * Builds menu icons without discarding data already present on an ItemStack.
 *
 * <p>{@link #from(ItemStack)} clones a complete custom stack, including its
 * item data, enchantments and metadata. The builder then changes only the
 * properties explicitly requested by the caller.</p>
 */
public final class MenuItemBuilder {

    private final ItemStack item;

    private MenuItemBuilder(ItemStack item) {
        this.item = requireUsableItem(item).clone();
    }

    /** Starts a menu icon from one vanilla material. */
    public static MenuItemBuilder of(Material material) {
        Objects.requireNonNull(material, "material");
        if (!material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Menu material must represent a usable item: "
                    + material);
        }

        return new MenuItemBuilder(new ItemStack(material));
    }

    /** Starts from a cloned custom stack while preserving all existing data. */
    public static MenuItemBuilder from(ItemStack item) {
        return new MenuItemBuilder(item);
    }

    /** Sets the stack amount shown on the icon. */
    public MenuItemBuilder amount(int amount) {
        if (amount < 1 || amount > item.getMaxStackSize()) {
            throw new IllegalArgumentException("Menu item amount must be between 1 and "
                    + item.getMaxStackSize() + ".");
        }

        item.setAmount(amount);
        return this;
    }

    /** Sets the Adventure item name displayed by the client. */
    public MenuItemBuilder name(Component name) {
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Objects.requireNonNull(name, "name"));
        item.setItemMeta(meta);
        return this;
    }

    /** Replaces every lore line. An empty list removes the lore. */
    public MenuItemBuilder lore(List<? extends Component> lore) {
        Objects.requireNonNull(lore, "lore");
        ItemMeta meta = item.getItemMeta();
        meta.lore(lore.isEmpty() ? null : List.copyOf(lore));
        item.setItemMeta(meta);
        return this;
    }

    /** Convenience overload for a fixed number of lore lines. */
    public MenuItemBuilder lore(Component... lore) {
        return lore(Arrays.asList(Objects.requireNonNull(lore, "lore")));
    }

    /** Appends lines while retaining lore already stored on a custom stack. */
    public MenuItemBuilder addLore(Component... additionalLore) {
        Objects.requireNonNull(additionalLore, "additionalLore");
        ItemMeta meta = item.getItemMeta();
        List<Component> combinedLore = new ArrayList<>();

        if (meta.hasLore() && meta.lore() != null) combinedLore.addAll(meta.lore());
        for (Component line : additionalLore) {
            combinedLore.add(Objects.requireNonNull(line, "lore line"));
        }

        meta.lore(combinedLore.isEmpty() ? null : List.copyOf(combinedLore));
        item.setItemMeta(meta);
        return this;
    }

    /** Removes all lore from the icon. */
    public MenuItemBuilder clearLore() {
        ItemMeta meta = item.getItemMeta();
        meta.lore(null);
        item.setItemMeta(meta);
        return this;
    }

    /** Adds a real enchantment to the icon. */
    public MenuItemBuilder enchant(Enchantment enchantment, int level,
            boolean ignoreLevelRestriction) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Objects.requireNonNull(enchantment, "enchantment"), level,
                ignoreLevelRestriction);
        item.setItemMeta(meta);
        return this;
    }

    /** Hides real enchantment text while retaining the enchantments themselves. */
    public MenuItemBuilder hideEnchantments() {
        ItemMeta meta = item.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS);
        item.setItemMeta(meta);
        return this;
    }

    /**
     * Forces the enchanted glow without adding a fake enchantment.
     * Modern Paper exposes the client glint as its own item property.
     */
    public MenuItemBuilder glow() {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return this;
    }

    /** Removes a previously forced glow without suppressing real enchantments. */
    public MenuItemBuilder clearForcedGlow() {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(null);
        item.setItemMeta(meta);
        return this;
    }

    /** Returns a defensive clone ready to place inside an inventory. */
    public ItemStack build() {
        return item.clone();
    }

    static ItemStack requireUsableItem(ItemStack item) {
        if (item == null) throw new IllegalArgumentException("Menu item cannot be null.");
        if (item.getType().isAir()) {
            throw new IllegalArgumentException("Menu item cannot be an empty air stack.");
        }
        return item;
    }
}
