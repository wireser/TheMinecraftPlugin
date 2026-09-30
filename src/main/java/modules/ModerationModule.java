package modules;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import enums.GroupType;
import menu.MenuItemBuilder;
import menu.MenuSession;
import net.kyori.adventure.text.Component;
import playerdata.Profile;

/**
 * Owns staff moderation commands, timed punishments and PRIUS records.
 *
 * <p>Timed states are persisted by {@link TimersModule}; this module supplies
 * their moderation meaning. A mute blocks chat, a buildoff state is displayed
 * to players as {@code Marked} and temporarily replaces the effective group
 * with {@link GroupType#PUNISHED}, jail is available to the future jail system,
 * and tempban is checked before a profile is created.</p>
 *
 * <p>Permanent {@code /ban} and {@code /unban} deliberately do not live here.
 * They are core safety commands registered by {@code CoreModerationCommands},
 * so disabling this module cannot accidentally admit permanently banned
 * players.</p>
 */
public final class ModerationModule extends BaseModule {

    private static final String PRIUS_TABLE = "player_prius_entries";
    private static final int MAX_COMMAND_SUGGESTIONS = 20;
    private static final int PRIUS_ENTRIES_PER_PAGE = 8;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int CHAT_CLEAR_LINES = 100;
    private static final long RESTRICTION_MESSAGE_DELAY_MILLIS = 2_000L;
    private static final DateTimeFormatter STAFF_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final List<String> TIMER_ACTIONS = List.of("set", "add", "sub", "off");
    private static final List<String> COMMON_DURATIONS = List.of("30m", "1h", "1d", "1w");
    private static final Map<String, HelpEntry> HELP_ENTRIES = createHelpEntries();
    private static final List<String> HELP_TOPICS = List.of("punishments", "prius", "privacy");

    private final Set<Integer> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> jailedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> markedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> temporarilyBannedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, GameMode> hiddenStaffModes = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastRestrictionMessage = new ConcurrentHashMap<>();

    private TimersModule timers;

    public ModerationModule() {
        super("Moderation", "1.0.0");
    }

    /** Declares the timer service as a real module dependency. */
    @Override
    protected void onLoad() {
        if (!requiredModules.contains("Timers")) requiredModules.add("Timers");
    }

    /** Restores the runtime dependency after BaseModule reloads YAML metadata. */
    @Override
    protected void onReload() {
        if (!requiredModules.contains("Timers")) requiredModules.add("Timers");
    }

    /** Registers staff commands; permanent bans remain registered by core. */
    @Override
    protected void registerCommands() {
        addCommand("modhelp", command -> command.description(helpPlain("modhelp", "summary"))
                .syntax(helpPlain("modhelp", "syntax"))
                .minimumGroup(GroupType.MODERATOR).tabHandler(this::suggestModHelp));
        addCommand("player", command -> command.description(helpPlain("player", "summary"))
                .syntax(helpPlain("player", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestRegisteredPlayers));
        addCommand("prius", command -> command.description(helpPlain("prius", "summary"))
                .syntax(helpPlain("prius", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestRegisteredPlayers));
        addCommand("note", command -> command.description(helpPlain("note", "summary"))
                .syntax(helpPlain("note", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestRegisteredPlayers));
        addCommand("warn", command -> command.description(helpPlain("warn", "summary"))
                .syntax(helpPlain("warn", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestRegisteredPlayers));
        addCommand("kick", command -> command.description(helpPlain("kick", "summary"))
                .syntax(helpPlain("kick", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestOnlinePlayers));
        addPunishmentCommand("mute");
        addPunishmentCommand("jail");
        addPunishmentCommand("buildoff");
        addPunishmentCommand("tempban");
        addCommand("inv", command -> command.description(helpPlain("inv", "summary"))
                .syntax(helpPlain("inv", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestInventoryArguments));
        addCommand("hide", command -> command.description(helpPlain("hide", "summary"))
                .minimumGroup(GroupType.MODERATOR));
        addCommand("cc", command -> command.aliases("clearchat")
                .description(helpPlain("cc", "summary"))
                .minimumGroup(GroupType.MODERATOR));
        addCommand("clear", command -> command.description(helpPlain("clear", "summary"))
                .syntax(helpPlain("clear", "syntax")).minimumGroup(GroupType.ADMIN)
                .tabHandler(this::suggestOnlinePlayers));
    }

    /** Registers one uniform timed-punishment command. */
    private void addPunishmentCommand(String label) {
        addCommand(label, command -> command.description(helpPlain(label, "summary"))
                .syntax(helpPlain(label, "syntax"))
                .minimumGroup(GroupType.MODERATOR).tabHandler(this::suggestPunishmentArguments));
    }

    /** Connects moderation timer keys to their runtime effects. */
    @Override
    protected void onEnable() {
        timers = getModule(TimersModule.class);
        if (timers == null) throw new IllegalStateException("Moderation requires TimersModule.");

        for (Punishment punishment : Punishment.values()) {
            timers.registerTimer(punishment.timerKey, this,
                    change -> handleTimerChange(punishment, change));
        }
    }

    /** Restores temporary visual/runtime state without deleting stored timers. */
    @Override
    protected void onDisable() {
        if (timers != null) {
            for (Punishment punishment : Punishment.values()) {
                timers.unregisterTimer(punishment.timerKey, this);
            }
        }

        for (Profile profile : profiles().getOnlineProfiles()) {
            if (markedPlayers.contains(profile.getId())) profile.clearEffectiveGroupOverride();
        }

        for (Map.Entry<UUID, GameMode> hiddenStaff : List.copyOf(hiddenStaffModes.entrySet())) {
            Profile profile = profiles().getOnlineProfile(hiddenStaff.getKey());
            if (profile == null || profile.getPlayer() == null) continue;

            revealHiddenStaff(profile);
            profile.getPlayer().setGameMode(hiddenStaff.getValue());
        }

        mutedPlayers.clear();
        jailedPlayers.clear();
        markedPlayers.clear();
        temporarilyBannedPlayers.clear();
        hiddenStaffModes.clear();
        lastRestrictionMessage.clear();
    }

    /**
     * Synchronizes timer-derived state when this module is enabled while
     * players are already online, and hides vanished staff from a new viewer.
     */
    @Override
    public void onProfileLoaded(Profile profile) {
        if (profile == null || timers == null) return;

        for (UUID hiddenUuid : hiddenStaffModes.keySet()) {
            Profile hiddenProfile = profiles().getOnlineProfile(hiddenUuid);
            if (hiddenProfile != null && hiddenProfile.getPlayer() != null
                    && profile.getPlayer() != null && profile.getId() != hiddenProfile.getId()) {
                profile.getPlayer().hidePlayer(plugin, hiddenProfile.getPlayer());
            }
        }

        for (Punishment punishment : Punishment.values()) {
            try {
                Optional<TimersModule.PlayerTimer> timer =
                        timers.findTimer(profile, punishment.timerKey);
                applyPunishmentState(punishment, profile, timer.orElse(null), false);
            } catch (SQLException exception) {
                logError("Failed to synchronize " + punishment.timerKey
                        + " for playerId=" + profile.getId() + ".", exception);
            }
        }
    }

    /** Removes only session state when the profile leaves RAM. */
    @Override
    public void onProfileUnloaded(Profile profile) {
        if (profile == null) return;

        mutedPlayers.remove(profile.getId());
        jailedPlayers.remove(profile.getId());
        markedPlayers.remove(profile.getId());
        temporarilyBannedPlayers.remove(profile.getId());
        lastRestrictionMessage.remove(profile.getId());
        hiddenStaffModes.remove(profile.getUuid());
    }

    /** Routes all registered moderation labels to compact command handlers. */
    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null) return false;

        String[] safeArguments = arguments == null ? new String[0] : arguments;
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "modhelp" -> handleModHelp(sender, safeArguments);
            case "player" -> handlePlayerSheet(sender, safeArguments);
            case "prius" -> handlePrius(sender, safeArguments);
            case "note" -> handleNote(sender, safeArguments);
            case "warn" -> handleWarn(sender, safeArguments);
            case "kick" -> handleKick(sender, safeArguments);
            case "mute" -> handlePunishment(sender, Punishment.MUTE, safeArguments);
            case "jail" -> handlePunishment(sender, Punishment.JAIL, safeArguments);
            case "buildoff" -> handlePunishment(sender, Punishment.BUILDOFF, safeArguments);
            case "tempban" -> handlePunishment(sender, Punishment.TEMPBAN, safeArguments);
            case "inv" -> handleInventory(sender, safeArguments);
            case "hide" -> handleHide(sender, safeArguments);
            case "cc", "clearchat" -> handleClearChat(sender);
            case "clear" -> handleClearInventory(sender, safeArguments);
            default -> false;
        };
    }

    // =====================================================================
    // Central-listener entry points
    // =====================================================================

    /**
     * Returns whether chat should be cancelled for this profile. The message is
     * scheduled onto the server thread because Paper's chat event is async.
     */
    public boolean shouldCancelChat(Profile profile) {
        if (!isMuted(profile)) return false;

        getServer().getScheduler().runTask(plugin,
                () -> profile.sendMessageIfOnline(getText("moderation.mute.chat_blocked")));
        return true;
    }

    /** @return whether this online profile currently has an active mute */
    public boolean isMuted(Profile profile) {
        return profile != null && mutedPlayers.contains(profile.getId());
    }

    /** @return whether this online profile currently has an active jail timer */
    public boolean isJailed(Profile profile) {
        return profile != null && jailedPlayers.contains(profile.getId());
    }

    /** @return whether this online profile currently uses Marked restrictions */
    public boolean isMarked(Profile profile) {
        return profile != null && markedPlayers.contains(profile.getId());
    }

    /** Cancels world modification and interaction while a player is Marked. */
    public boolean shouldCancelWorldInteraction(Profile profile) {
        if (!isMarked(profile)) return false;

        long now = System.currentTimeMillis();
        long previous = lastRestrictionMessage.getOrDefault(profile.getId(), 0L);
        if (now - previous >= RESTRICTION_MESSAGE_DELAY_MILLIS) {
            lastRestrictionMessage.put(profile.getId(), now);
            profile.sendMessageIfOnline(getText("moderation.buildoff.interaction_blocked"));
        }

        return true;
    }

    /**
     * Resolves an active tempban directly from SQL before an online profile
     * exists. The database timestamp is authoritative; expired rows are
     * discarded by the timer module later.
     *
     * @param uuid account attempting to log in
     * @return disconnect message, or {@code null} when login may continue
     */
    public Component getTemporaryBanLoginMessage(UUID uuid) {
        if (uuid == null) return null;

        try {
            Integer playerId = getDB().getInt("players", "id", "uuid = ?", List.of(uuid.toString()));
            if (playerId == null || playerId <= 0) return null;

            Map<String, Object> row = getDB().getRow("player_timers",
                    List.of("expires_at"), "player_id = ? AND timer_key = ?",
                    List.of(playerId, Punishment.TEMPBAN.timerKey));
            if (row.isEmpty()) return null;

            LocalDateTime expiresAt = readNullableDateTime(row.get("expires_at"));
            if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) return null;

            return expiresAt == null
                    ? getText("moderation.tempban.login_indefinite")
                    : getText("moderation.tempban.login_timed", formatDateTime(expiresAt),
                            TimersModule.formatDuration(Duration.between(LocalDateTime.now(), expiresAt)));
        } catch (SQLException | DateTimeException exception) {
            logError("Failed to check tempban before login for UUID " + uuid + ".", exception);
            return null;
        }
    }

    // =====================================================================
    // Help and player information
    // =====================================================================

    private boolean handleModHelp(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            sender.sendMessage(getText("moderation.help.header"));
            for (HelpEntry entry : HELP_ENTRIES.values()) {
                sender.sendMessage(getText("moderation.help.list_entry",
                        helpPlain(entry.label, "syntax"), helpPlain(entry.label, "summary")));
            }
            sender.sendMessage(getText("moderation.help.footer"));
            return true;
        }

        if (arguments[0].equalsIgnoreCase("gui")) return openModerationHelp(sender);

        if (arguments[0].equalsIgnoreCase("topic")) {
            if (arguments.length != 2 || !HELP_TOPICS.contains(arguments[1].toLowerCase(Locale.ROOT))) {
                sender.sendMessage(getText("moderation.help.topic_usage",
                        String.join(", ", HELP_TOPICS)));
                return true;
            }

            sender.sendMessage(getText("moderation.help.topic."
                    + arguments[1].toLowerCase(Locale.ROOT)));
            return true;
        }

        HelpEntry entry = HELP_ENTRIES.get(normalizeCommandLabel(arguments[0]));
        if (entry == null) {
            sender.sendMessage(getText("moderation.help.unknown", arguments[0]));
            return true;
        }

        sender.sendMessage(getText("moderation.help.detail", helpPlain(entry.label, "syntax"),
                helpPlain(entry.label, "summary"), helpPlain(entry.label, "detail")));
        return true;
    }

    /** Builds a small hardcoded navigation menu; command documentation stays in lang.yml. */
    private boolean openModerationHelp(Profile sender) {
        MenuSession menu = menus().create(sender, 27, getText("moderation.help.menu_title"))
                .requireMinimumGroup(GroupType.MODERATOR, getText("command.no_permission"));
        int slot = 0;

        for (HelpEntry entry : HELP_ENTRIES.values()) {
            ItemStack icon = MenuItemBuilder.of(entry.icon)
                    .name(getText("moderation.help.menu_name", "/" + entry.label))
                    .lore(getText("moderation.help.menu_lore",
                            helpPlain(entry.label, "summary")))
                    .build();
            menu.button(slot++, icon, menu.runCommand("modhelp " + entry.label));
        }

        menu.fillEmpty(Material.GRAY_STAINED_GLASS_PANE);
        return menu.open();
    }

    private boolean handlePlayerSheet(Profile sender, String[] arguments) {
        if (arguments.length != 1) return sendUsage(sender, helpPlain("player", "syntax"));

        Profile target = requireKnownPlayer(sender, arguments[0]);
        if (target == null) return true;

        try {
            Map<String, Object> playerRow = getDB().getRow("players",
                    List.of("first_login_at", "last_login_at", "flag_banned"),
                    "id = ?", List.of(target.getId()));
            int priusEntries = getDB().count(PRIUS_TABLE, "player_id = ?", List.of(target.getId()));
            List<String> activePunishments = new ArrayList<>();

            for (Punishment punishment : Punishment.values()) {
                if (timers.hasTimer(target, punishment.timerKey)) {
                    activePunishments.add(punishment.displayName);
                }
            }

            boolean permanentlyBanned = Boolean.TRUE.equals(asBoolean(playerRow.get("flag_banned")));
            String punishments = activePunishments.isEmpty()
                    ? lang.plain("moderation.player.none")
                    : String.join(", ", activePunishments);

            sender.sendMessage(getText("moderation.player.sheet", target.getIgn(), target.getId(),
                    target.getUuid(), target.getAssignedGroup().getDisplayName(),
                    target.getEffectiveGroup().getDisplayName(),
                    target.isOnline() ? lang.plain("moderation.player.online")
                            : lang.plain("moderation.player.offline"),
                    formatDatabaseDate(playerRow.get("first_login_at")),
                    formatDatabaseDate(playerRow.get("last_login_at")),
                    punishments, permanentlyBanned ? lang.plain("moderation.player.affirmative")
                            : lang.plain("moderation.player.negative"), priusEntries));
        } catch (SQLException exception) {
            logError("Failed to load staff sheet for playerId=" + target.getId() + ".", exception);
            sender.sendMessage(getText("moderation.database_error"));
        }

        return true;
    }

    // =====================================================================
    // PRIUS
    // =====================================================================

    private boolean handlePrius(Profile sender, String[] arguments) {
        if (arguments.length < 1 || arguments.length > 2) {
            return sendUsage(sender, helpPlain("prius", "syntax"));
        }

        Profile target = requireKnownPlayer(sender, arguments[0]);
        if (target == null) return true;

        int page = 1;
        if (arguments.length == 2) {
            try {
                page = Integer.parseInt(arguments[1]);
            } catch (NumberFormatException ignored) {
                page = 0;
            }
        }

        if (page < 1) {
            sender.sendMessage(getText("moderation.prius.invalid_page"));
            return true;
        }

        try {
            List<Map<String, Object>> rows = getDB().getRows(PRIUS_TABLE,
                    List.of("id", "staff_id", "entry_class", "entry_type", "recorded_at", "entry_text"),
                    "player_id = ?", List.of(target.getId()));
            rows.sort(Comparator.comparingInt(
                    (Map<String, Object> row) -> asInt(row.get("id"))).reversed());

            int totalPages = Math.max(1, (rows.size() + PRIUS_ENTRIES_PER_PAGE - 1)
                    / PRIUS_ENTRIES_PER_PAGE);
            if (page > totalPages) {
                sender.sendMessage(getText("moderation.prius.page_missing", page, totalPages));
                return true;
            }

            sender.sendMessage(getText("moderation.prius.header", target.getIgn(), page, totalPages,
                    rows.size()));
            int fromIndex = (page - 1) * PRIUS_ENTRIES_PER_PAGE;
            int toIndex = Math.min(rows.size(), fromIndex + PRIUS_ENTRIES_PER_PAGE);

            if (rows.isEmpty()) {
                sender.sendMessage(getText("moderation.prius.empty"));
            } else {
                for (Map<String, Object> row : rows.subList(fromIndex, toIndex)) {
                    sender.sendMessage(getText("moderation.prius.entry",
                            formatDatabaseDate(row.get("recorded_at")), row.get("entry_class"),
                            row.get("entry_type"), resolveStaffName(asInt(row.get("staff_id"))),
                            escapeMiniMessage(String.valueOf(row.get("entry_text")))));
                }
            }
        } catch (SQLException exception) {
            logError("Failed to load PRIUS for playerId=" + target.getId() + ".", exception);
            sender.sendMessage(getText("moderation.database_error"));
        }

        return true;
    }

    private boolean handleNote(Profile sender, String[] arguments) {
        if (arguments.length < 2) return sendUsage(sender, helpPlain("note", "syntax"));

        Profile target = requireKnownPlayer(sender, arguments[0]);
        if (target == null) return true;

        String note = joinArguments(arguments, 1);
        if (!isValidReason(note)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (recordPrius(target, sender, "note", "staff_note", note)) {
            sender.sendMessage(getText("moderation.note.saved", target.getIgn()));
        } else {
            sender.sendMessage(getText("moderation.database_error"));
        }

        return true;
    }

    /** Records every warning, including the default warning without a typed reason. */
    private boolean handleWarn(Profile sender, String[] arguments) {
        if (arguments.length < 1) return sendUsage(sender, helpPlain("warn", "syntax"));

        Profile target = requireOnlinePunishableTarget(sender, arguments[0]);
        if (target == null) return true;

        String reason = arguments.length == 1
                ? lang.plain("moderation.warn.default_reason")
                : joinArguments(arguments, 1);
        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (!recordPrius(target, sender, "warning", "warning", reason)) {
            sender.sendMessage(getText("moderation.database_error"));
            return true;
        }

        target.sendMessageIfOnline(getText("moderation.warn.received", sender.getIgn(),
                escapeMiniMessage(reason)));
        sender.sendMessage(getText("moderation.warn.sent", target.getIgn()));
        return true;
    }

    private boolean recordPrius(Profile target, Profile staff, String entryClass,
            String entryType, String entryText) {
        if (target == null || staff == null || target.getId() <= 0 || staff.getId() <= 0
                || entryText == null || entryText.isBlank()) return false;

        try {
            return getDB().insert(PRIUS_TABLE,
                    List.of("player_id", "staff_id", "entry_class", "entry_type", "entry_text"),
                    List.of(target.getId(), staff.getId(), entryClass, entryType, entryText)) == 1;
        } catch (SQLException exception) {
            logError("Failed to add PRIUS entry for playerId=" + target.getId() + ".", exception);
            return false;
        }
    }

    // =====================================================================
    // Direct staff actions
    // =====================================================================

    private boolean handleKick(Profile sender, String[] arguments) {
        if (arguments.length < 2) return sendUsage(sender, helpPlain("kick", "syntax"));

        Profile target = requireOnlinePunishableTarget(sender, arguments[0]);
        if (target == null) return true;

        String reason = joinArguments(arguments, 1);
        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (!recordPrius(target, sender, "punishment", "kick", reason)) {
            sender.sendMessage(getText("moderation.database_error"));
            return true;
        }

        target.getPlayer().kick(getText("moderation.kick.disconnect", escapeMiniMessage(reason)));
        sender.sendMessage(getText("moderation.kick.completed", target.getIgn()));
        return true;
    }

    private boolean handleInventory(Profile sender, String[] arguments) {
        if (arguments.length < 1 || arguments.length > 2) {
            return sendUsage(sender, helpPlain("inv", "syntax"));
        }

        Profile target = requireOnlinePunishableTarget(sender, arguments[0]);
        if (target == null) return true;

        String inventoryType = arguments.length == 1
                ? "inventory"
                : arguments[1].toLowerCase(Locale.ROOT);
        if (!inventoryType.equals("inventory") && !inventoryType.equals("ender")) {
            return sendUsage(sender, helpPlain("inv", "syntax"));
        }

        if (inventoryType.equals("ender")) {
            sender.getPlayer().openInventory(target.getPlayer().getEnderChest());
        } else {
            sender.getPlayer().openInventory(target.getPlayer().getInventory());
        }

        sender.sendMessage(getText("moderation.inventory.opened", target.getIgn(), inventoryType));
        return true;
    }

    private boolean handleHide(Profile sender, String[] arguments) {
        if (arguments.length != 0) return sendUsage(sender, helpPlain("hide", "syntax"));

        Player player = sender.getPlayer();
        if (player == null) return true;

        GameMode previousMode = hiddenStaffModes.remove(sender.getUuid());
        if (previousMode != null) {
            revealHiddenStaff(sender);
            player.setGameMode(previousMode);
            sender.sendMessage(getText("moderation.hide.visible"));
            return true;
        }

        hiddenStaffModes.put(sender.getUuid(), player.getGameMode());
        for (Player viewer : getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(sender.getUuid())) viewer.hidePlayer(plugin, player);
        }

        player.setGameMode(GameMode.SPECTATOR);
        sender.sendMessage(getText("moderation.hide.hidden"));
        return true;
    }

    private void revealHiddenStaff(Profile profile) {
        if (profile == null || profile.getPlayer() == null) return;

        for (Player viewer : getServer().getOnlinePlayers()) {
            viewer.showPlayer(plugin, profile.getPlayer());
        }
    }

    private boolean handleClearChat(Profile sender) {
        Component clearedSpace = Component.text("\n".repeat(CHAT_CLEAR_LINES));

        for (Player player : getServer().getOnlinePlayers()) {
            player.sendMessage(clearedSpace);
            player.sendMessage(getText("moderation.chat_cleared", sender.getIgn()));
        }

        return true;
    }

    private boolean handleClearInventory(Profile sender, String[] arguments) {
        if (arguments.length != 1) return sendUsage(sender, helpPlain("clear", "syntax"));

        Profile target = arguments[0].equalsIgnoreCase(sender.getIgn())
                ? sender
                : requireOnlinePunishableTarget(sender, arguments[0]);
        if (target == null) return true;

        target.getPlayer().getInventory().clear();
        target.getPlayer().setItemOnCursor(null);
        sender.sendMessage(getText("moderation.clear.completed", target.getIgn()));

        if (target.getId() != sender.getId()) {
            target.sendMessage(getText("moderation.clear.received", sender.getIgn()));
        }

        return true;
    }

    // =====================================================================
    // Generic timed punishments
    // =====================================================================

    private boolean handlePunishment(Profile sender, Punishment punishment, String[] arguments) {
        if (arguments.length == 0) {
            sender.sendMessage(getText("moderation.punishment.usage", punishment.commandLabel));
            return true;
        }

        Profile target = requirePunishableTarget(sender, arguments[0]);
        if (target == null) return true;

        if (arguments.length == 1) return showPunishment(sender, target, punishment);

        String operation = arguments[1].toLowerCase(Locale.ROOT);
        if (operation.equals("off") || operation.equals("0")) {
            String reason = arguments.length > 2 ? joinArguments(arguments, 2)
                    : lang.plain("moderation.punishment.no_reason");
            return removePunishment(sender, target, punishment, reason);
        }

        boolean explicitOperation = TIMER_ACTIONS.contains(operation);
        String durationInput = explicitOperation
                ? arguments.length > 2 ? arguments[2] : ""
                : arguments[1];
        int reasonStart = explicitOperation ? 3 : 2;
        String reason = arguments.length > reasonStart
                ? joinArguments(arguments, reasonStart)
                : lang.plain("moderation.punishment.no_reason");

        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (durationInput.isBlank()) {
            sender.sendMessage(getText("moderation.punishment.usage", punishment.commandLabel));
            return true;
        }

        if (durationInput.equalsIgnoreCase("infinite")) {
            if (operation.equals("add") || operation.equals("sub") || !punishment.allowsIndefinite) {
                sender.sendMessage(getText("moderation.punishment.infinite_not_allowed",
                        punishment.displayName));
                return true;
            }

            return setIndefinitePunishment(sender, target, punishment, reason);
        }

        Optional<Duration> parsedDuration = TimersModule.parseDuration(durationInput);
        if (parsedDuration.isEmpty()) {
            sender.sendMessage(getText("moderation.punishment.invalid_duration", durationInput));
            return true;
        }

        String effectiveOperation = explicitOperation ? operation : "add";
        return changeFinitePunishment(sender, target, punishment, effectiveOperation,
                parsedDuration.get(), reason);
    }

    private boolean showPunishment(Profile sender, Profile target, Punishment punishment) {
        try {
            TimersModule.PlayerTimer timer =
                    timers.findTimer(target, punishment.timerKey).orElse(null);

            if (timer == null) {
                sender.sendMessage(getText("moderation.punishment.inactive", target.getIgn(),
                        punishment.displayName));
            } else if (timer.isIndefinite()) {
                sender.sendMessage(getText("moderation.punishment.active_indefinite", target.getIgn(),
                        punishment.displayName));
            } else {
                sender.sendMessage(getText("moderation.punishment.active_timed", target.getIgn(),
                        punishment.displayName, formatDateTime(timer.expiresAt()),
                        TimersModule.formatDuration(timer.remainingAt(LocalDateTime.now()))));
            }
        } catch (SQLException exception) {
            logError("Failed to inspect " + punishment.timerKey
                    + " for playerId=" + target.getId() + ".", exception);
            sender.sendMessage(getText("moderation.database_error"));
        }

        return true;
    }

    private boolean setIndefinitePunishment(Profile sender, Profile target,
            Punishment punishment, String reason) {
        try {
            timers.setIndefiniteTimer(target, punishment.timerKey);
            String record = createPunishmentRecord("set", punishment, "indefinite", null, reason);
            if (!recordPrius(target, sender, "punishment", punishment.timerKey, record)) {
                sender.sendMessage(getText("moderation.prius.save_failed"));
            }
            sender.sendMessage(getText("moderation.punishment.set_indefinite", target.getIgn(),
                    punishment.displayName));
            notifyPunishedPlayer(target, sender, punishment, "indefinite", reason);
        } catch (SQLException exception) {
            handlePunishmentDatabaseFailure(sender, target, punishment, exception);
        }

        return true;
    }

    private boolean changeFinitePunishment(Profile sender, Profile target, Punishment punishment,
            String operation, Duration duration, String reason) {
        try {
            TimersModule.PlayerTimer previous =
                    timers.findTimer(target, punishment.timerKey).orElse(null);
            if (operation.equals("sub") && previous == null) {
                sender.sendMessage(getText("moderation.punishment.inactive", target.getIgn(),
                        punishment.displayName));
                return true;
            }
            if ((operation.equals("add") || operation.equals("sub"))
                    && previous != null && previous.isIndefinite()) {
                sender.sendMessage(getText("moderation.punishment.indefinite_change_rejected",
                        target.getIgn(), punishment.displayName));
                return true;
            }

            TimersModule.PlayerTimer current;
            switch (operation) {
                case "set" -> current = timers.setTimer(target, punishment.timerKey, duration);
                case "add" -> current = timers.addTime(target, punishment.timerKey, duration);
                case "sub" -> {
                    Optional<TimersModule.PlayerTimer> result =
                            timers.subtractTime(target, punishment.timerKey, duration);
                    current = result.orElse(null);
                }
                default -> {
                    sender.sendMessage(getText("moderation.punishment.unknown_action", operation));
                    return true;
                }
            }

            String formattedDuration = TimersModule.formatDuration(duration);
            String record = createPunishmentRecord(operation, punishment, formattedDuration,
                    current == null ? null : current.expiresAt(), reason);
            if (!recordPrius(target, sender, "punishment", punishment.timerKey, record)) {
                sender.sendMessage(getText("moderation.prius.save_failed"));
            }

            if (current == null) {
                sender.sendMessage(getText("moderation.punishment.ended", target.getIgn(),
                        punishment.displayName));
                target.sendMessageIfOnline(getText("moderation.punishment.removed_target",
                        punishment.displayName, sender.getIgn(), escapeMiniMessage(reason)));
            } else {
                sender.sendMessage(getText("moderation.punishment.changed", target.getIgn(),
                        punishment.displayName, operation, formattedDuration,
                        formatDateTime(current.expiresAt())));
                notifyPunishedPlayer(target, sender, punishment, formattedDuration, reason);
            }
        } catch (IllegalArgumentException | DateTimeException exception) {
            sender.sendMessage(getText("moderation.punishment.invalid_duration",
                    TimersModule.formatDuration(duration)));
        } catch (SQLException exception) {
            handlePunishmentDatabaseFailure(sender, target, punishment, exception);
        }

        return true;
    }

    private boolean removePunishment(Profile sender, Profile target, Punishment punishment,
            String reason) {
        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        try {
            if (!timers.resetTimer(target, punishment.timerKey)) {
                sender.sendMessage(getText("moderation.punishment.inactive", target.getIgn(),
                        punishment.displayName));
                return true;
            }

            if (!recordPrius(target, sender, "punishment", punishment.timerKey,
                    createPunishmentRecord("off", punishment, null, null, reason))) {
                sender.sendMessage(getText("moderation.prius.save_failed"));
            }
            sender.sendMessage(getText("moderation.punishment.removed", target.getIgn(),
                    punishment.displayName));
            target.sendMessageIfOnline(getText("moderation.punishment.removed_target",
                    punishment.displayName, sender.getIgn(), escapeMiniMessage(reason)));
        } catch (SQLException exception) {
            handlePunishmentDatabaseFailure(sender, target, punishment, exception);
        }

        return true;
    }

    private void notifyPunishedPlayer(Profile target, Profile sender, Punishment punishment,
            String duration, String reason) {
        if (punishment == Punishment.TEMPBAN) return;

        target.sendMessageIfOnline(getText("moderation.punishment.applied_target",
                punishment.displayName, duration, sender.getIgn(), escapeMiniMessage(reason)));
    }

    private String createPunishmentRecord(String operation, Punishment punishment,
            String duration, LocalDateTime expiresAt, String reason) {
        StringBuilder record = new StringBuilder(operation.toUpperCase(Locale.ROOT))
                .append(' ').append(punishment.displayName);
        if (duration != null) record.append(" | duration: ").append(duration);
        if (expiresAt != null) record.append(" | expires: ").append(formatDateTime(expiresAt));
        return record.append(" | reason: ").append(reason).toString();
    }

    private void handlePunishmentDatabaseFailure(Profile sender, Profile target,
            Punishment punishment, SQLException exception) {
        logError("Failed to change " + punishment.timerKey
                + " for playerId=" + target.getId() + ".", exception);
        sender.sendMessage(getText("moderation.database_error"));
    }

    /** Applies cache/group/kick effects whenever the generic timer changes. */
    private void handleTimerChange(Punishment punishment, TimersModule.TimerChange change) {
        applyPunishmentState(punishment, change.profile(), change.currentTimer(),
                change.reason() == TimersModule.TimerChangeReason.EXPIRED);
    }

    private void applyPunishmentState(Punishment punishment, Profile profile,
            TimersModule.PlayerTimer activeTimer, boolean naturallyExpired) {
        /* Offline command targets are temporary profiles and must not enter session caches. */
        if (!profile.isCachedOnlineProfile()) return;

        Set<Integer> activePlayers = activeSet(punishment);
        boolean newlyActive = activeTimer != null && activePlayers.add(profile.getId());

        if (activeTimer == null) activePlayers.remove(profile.getId());

        if (punishment == Punishment.BUILDOFF) {
            if (activeTimer == null) {
                profile.clearEffectiveGroupOverride();
                lastRestrictionMessage.remove(profile.getId());
            } else {
                profile.setEffectiveGroupOverride(GroupType.PUNISHED);
            }

            if (profile.getPlayer() != null) profile.getPlayer().updateCommands();
        }

        if (punishment == Punishment.TEMPBAN && newlyActive && profile.isOnline()) {
            profile.getPlayer().kick(activeTimer.isIndefinite()
                    ? getText("moderation.tempban.login_indefinite")
                    : getText("moderation.tempban.login_timed",
                            formatDateTime(activeTimer.expiresAt()),
                            TimersModule.formatDuration(activeTimer.remainingAt(LocalDateTime.now()))));
        }

        if (naturallyExpired) {
            profile.sendMessageIfOnline(getText("moderation.punishment.expired",
                    punishment.displayName));
        }
    }

    private Set<Integer> activeSet(Punishment punishment) {
        return switch (punishment) {
            case MUTE -> mutedPlayers;
            case JAIL -> jailedPlayers;
            case BUILDOFF -> markedPlayers;
            case TEMPBAN -> temporarilyBannedPlayers;
        };
    }

    // =====================================================================
    // Target resolution and completion
    // =====================================================================

    private Profile requireKnownPlayer(Profile sender, String username) {
        Profile target = getOfflinePlayer(username);
        if (target == null || target.getId() == 0) {
            sender.sendMessage(getText("moderation.player_not_found", username));
            return null;
        }

        return target;
    }

    private Profile requirePunishableTarget(Profile sender, String username) {
        Profile target = requireKnownPlayer(sender, username);
        if (target == null) return null;

        if (target.getId() == sender.getId()) {
            sender.sendMessage(getText("moderation.cannot_target_self"));
            return null;
        }

        if (!isStrictlyHigherGroup(sender, target)) {
            sender.sendMessage(getText("moderation.cannot_target_group", target.getIgn()));
            return null;
        }

        return target;
    }

    private Profile requireOnlinePunishableTarget(Profile sender, String username) {
        Profile target = requirePunishableTarget(sender, username);
        if (target == null) return null;

        if (!target.isOnline()) {
            sender.sendMessage(getText("moderation.player_offline", target.getIgn()));
            return null;
        }

        return target;
    }

    /** Staff may alter only groups strictly below their permanent group. */
    private boolean isStrictlyHigherGroup(Profile staff, Profile target) {
        return staff.getAssignedGroup() != target.getAssignedGroup()
                && staff.getAssignedGroup().inheritsFrom(target.getAssignedGroup());
    }

    private List<String> suggestRegisteredPlayers(Profile sender, String label, String[] arguments) {
        if (arguments.length != 1) return List.of();
        return profiles().suggestRegisteredUsernames(arguments[0], 0, MAX_COMMAND_SUGGESTIONS);
    }

    private List<String> suggestOnlinePlayers(Profile sender, String label, String[] arguments) {
        if (arguments.length != 1) return List.of();
        return profiles().suggestOnlineUsernames(arguments[0], 0, MAX_COMMAND_SUGGESTIONS);
    }

    private List<String> suggestPunishmentArguments(Profile sender, String label, String[] arguments) {
        if (arguments.length == 1) {
            return profiles().suggestRegisteredUsernames(arguments[0], sender.getId(),
                    MAX_COMMAND_SUGGESTIONS);
        }

        if (arguments.length == 2) {
            List<String> suggestions = new ArrayList<>(TIMER_ACTIONS);
            suggestions.add("0");
            suggestions.addAll(COMMON_DURATIONS);
            return suggestions;
        }

        if (arguments.length == 3) {
            String operation = arguments[1].toLowerCase(Locale.ROOT);
            if (operation.equals("set")) {
                List<String> suggestions = new ArrayList<>(COMMON_DURATIONS);
                if (!label.equalsIgnoreCase("tempban")) suggestions.add("infinite");
                return suggestions;
            }
            if (operation.equals("add") || operation.equals("sub")) return COMMON_DURATIONS;
        }

        return List.of();
    }

    private List<String> suggestInventoryArguments(Profile sender, String label, String[] arguments) {
        if (arguments.length == 1) {
            return profiles().suggestOnlineUsernames(arguments[0], 0, MAX_COMMAND_SUGGESTIONS);
        }
        if (arguments.length == 2) return List.of("inventory", "ender");
        return List.of();
    }

    private List<String> suggestModHelp(Profile sender, String label, String[] arguments) {
        if (arguments.length == 1) {
            List<String> suggestions = new ArrayList<>(HELP_ENTRIES.keySet());
            suggestions.add("topic");
            suggestions.add("gui");
            return suggestions;
        }
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("topic")) return HELP_TOPICS;
        return List.of();
    }

    // =====================================================================
    // Small formatting helpers
    // =====================================================================

    private boolean sendUsage(Profile sender, String syntax) {
        sender.sendMessage(getText("command.syntax.usage", syntax));
        return true;
    }

    /** Returns one unformatted command-help field from the cached language data. */
    private String helpPlain(String command, String field) {
        return lang.plain("moderation.help.commands." + command + "." + field);
    }

    private static String joinArguments(String[] arguments, int firstIndex) {
        if (arguments == null || firstIndex < 0 || firstIndex >= arguments.length) return "";
        return String.join(" ", List.of(arguments).subList(firstIndex, arguments.length)).trim();
    }

    private static boolean isValidReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= MAX_REASON_LENGTH
                && reason.indexOf('\n') < 0 && reason.indexOf('\r') < 0;
    }

    private static String normalizeCommandLabel(String label) {
        if (label == null) return "";
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }

    /** Prevents staff-entered text from becoming MiniMessage markup. */
    private static String escapeMiniMessage(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("<", "\\<");
    }

    private String resolveStaffName(int staffId) {
        if (staffId == 0) return "server";
        Profile staff = getOfflinePlayer(staffId);
        return staff == null || staff.getIgn() == null ? "#" + staffId : staff.getIgn();
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value instanceof Number number) return number.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    private static LocalDateTime readNullableDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime.withNano(0);
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().withNano(0);
        return LocalDateTime.parse(value.toString().replace(' ', 'T')).withNano(0);
    }

    private static String formatDatabaseDate(Object value) {
        if (value == null) return "-";

        try {
            LocalDateTime dateTime = readNullableDateTime(value);
            return dateTime == null ? "-" : formatDateTime(dateTime);
        } catch (DateTimeException exception) {
            return String.valueOf(value);
        }
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "-" : dateTime.format(STAFF_DATE_TIME);
    }

    private static Map<String, HelpEntry> createHelpEntries() {
        Map<String, HelpEntry> entries = new LinkedHashMap<>();
        entries.put("modhelp", new HelpEntry("modhelp", Material.KNOWLEDGE_BOOK));
        entries.put("player", new HelpEntry("player", Material.PLAYER_HEAD));
        entries.put("prius", new HelpEntry("prius", Material.WRITABLE_BOOK));
        entries.put("note", new HelpEntry("note", Material.PAPER));
        entries.put("warn", new HelpEntry("warn", Material.BELL));
        entries.put("kick", new HelpEntry("kick", Material.IRON_BOOTS));
        entries.put("mute", new HelpEntry("mute", Material.PACKED_ICE));
        entries.put("jail", new HelpEntry("jail", Material.IRON_BARS));
        entries.put("buildoff", new HelpEntry("buildoff", Material.BARRIER));
        entries.put("tempban", new HelpEntry("tempban", Material.CLOCK));
        entries.put("ban", new HelpEntry("ban", Material.NETHERITE_SWORD));
        entries.put("unban", new HelpEntry("unban", Material.GOLDEN_APPLE));
        entries.put("inv", new HelpEntry("inv", Material.CHEST));
        entries.put("hide", new HelpEntry("hide", Material.ENDER_EYE));
        entries.put("cc", new HelpEntry("cc", Material.WATER_BUCKET));
        entries.put("clear", new HelpEntry("clear", Material.LAVA_BUCKET));
        return Collections.unmodifiableMap(entries);
    }

    private enum Punishment {
        MUTE("mute", "mute", "Mute", true),
        JAIL("jail", "jail", "Jail", true),
        BUILDOFF("buildoff", "buildoff", "Marked", true),
        TEMPBAN("tempban", "tempban", "Temporary ban", false);

        private final String commandLabel;
        private final String timerKey;
        private final String displayName;
        private final boolean allowsIndefinite;

        Punishment(String commandLabel, String timerKey, String displayName,
                boolean allowsIndefinite) {
            this.commandLabel = commandLabel;
            this.timerKey = timerKey;
            this.displayName = displayName;
            this.allowsIndefinite = allowsIndefinite;
        }
    }

    private record HelpEntry(String label, Material icon) {}
}
