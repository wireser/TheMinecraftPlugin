package modules;

import java.util.Locale;

import enums.GroupType;
import playerdata.Profile;

/**
 * Copyable starting point for a new one-file module.
 *
 * <p>{@link BaseModule} owns lifecycle state, command registration, language,
 * database access, profile resolution and logging. A concrete module normally
 * needs only its command declarations and behaviour.</p>
 */
public final class EmptyModule extends BaseModule {

    public EmptyModule() {
        super("Empty", "1.0.0");
    }

    /** Declares commands; BaseModule registers them with CommandCentral. */
    @Override
    protected void registerCommands() {
        addCommand("empty", command -> command.description("Example module command.")
                .syntax("/empty [name]").minimumGroup(GroupType.PUNISHED));
    }

    /** Routes every command owned by this module. */
    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null) return false;

        return switch (label.toLowerCase(Locale.ROOT)) {
            case "empty" -> handleEmpty(sender, arguments);
            default -> false;
        };
    }

    /** Demonstrates language output without reading lang.yml at command time. */
    private boolean handleEmpty(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            sender.sendMessage(getText("empty.greeting"));
            return true;
        }

        sender.sendMessage(getText("empty.greeting_named", arguments[0]));
        return true;
    }

    /** Optional hook called after the module has enabled successfully. */
    @Override
    protected void onEnable() {
        log("Module enabled.");
    }

    /** Optional hook called before the module is fully disabled. */
    @Override
    protected void onDisable() {
        log("Module disabled.");
    }
}
