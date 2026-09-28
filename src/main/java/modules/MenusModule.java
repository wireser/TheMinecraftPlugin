package modules;

import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import enums.GroupType;
import menu.MenuItemBuilder;
import menu.MenuSession;
import playerdata.Profile;

/**
 * Owns the server's root menu and its command entry point.
 *
 * <p>The layout is deliberately hardcoded. Language files control only the
 * player-facing title, item text and feedback; they do not control slots,
 * materials, Java actions or navigation.</p>
 */
public final class MenusModule extends BaseModule {

    public MenusModule() {
        super("Menus", "1.0.0");
    }

    @Override
    protected void registerCommands() {
        addCommand("menu", command -> command.description("Open the main server menu.")
                .minimumGroup(GroupType.VISITOR));
    }

    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null) return false;

        return switch (label.toLowerCase(Locale.ROOT)) {
            case "menu" -> openMainMenu(sender);
            default -> false;
        };
    }

    /**
     * Builds and opens a fresh per-viewer copy of the nine-slot main menu.
     * Other modules may call this as the destination of their back button.
     */
    public boolean openMainMenu(Profile viewer) {
        MenuSession menu = menus().create(viewer, 9, getText("menus.main.title"))
                .requireMinimumGroup(GroupType.VISITOR, getText("command.no_permission"));

        addUnavailableButton(menu, 0, Material.COMPASS, "warps");
        addUnavailableButton(menu, 1, Material.BOOKSHELF, "tutorials");
        addUnavailableButton(menu, 2, Material.HAY_BLOCK, "shop");
        addUnavailableButton(menu, 3, Material.BARREL, "auctions");
        addUnavailableButton(menu, 4, Material.COMMAND_BLOCK, "settings");
        addUnavailableButton(menu, 5, Material.DIAMOND, "achievements");
        addUnavailableButton(menu, 6, Material.PLAYER_HEAD, "friends");
        addUnavailableButton(menu, 7, Material.PAPER, "tickets");
        menu.button(8, createIcon(Material.END_CRYSTAL, "voting", true),
                menu.runCommand("vote", true, getText("menus.main.unavailable",
                        lang.plain("menus.main.voting.name"))));

        return menu.open();
    }

    /** Adds one placeholder destination while its owning module is unfinished. */
    private void addUnavailableButton(MenuSession menu, int slot, Material material, String key) {
        menu.button(slot, createIcon(material, key, false),
                click -> click.viewer().sendMessage(getText("menus.main.unavailable",
                        lang.plain("menus.main." + key + ".name"))));
    }

    /** Builds one localized icon without putting layout data into lang.yml. */
    private ItemStack createIcon(Material material, String key, boolean glowing) {
        MenuItemBuilder icon = MenuItemBuilder.of(material)
                .name(getText("menus.main." + key + ".name"))
                .lore(getTextList("menus.main." + key + ".lore", 8, null));

        if (glowing) icon.glow();
        return icon.build();
    }
}
