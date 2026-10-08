package com.wireser.minecraft.modules;

import static com.wireser.minecraft.utils.CommandUtils.joinArguments;
import static com.wireser.minecraft.utils.CommandUtils.normalizeCommandLabel;
import static com.wireser.minecraft.utils.DatabaseValueConverter.asBoolean;
import static com.wireser.minecraft.utils.DatabaseValueConverter.asInt;

import java.sql.SQLException;
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
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.wireser.minecraft.enums.GroupType;
import com.wireser.minecraft.menu.MenuItemBuilder;
import com.wireser.minecraft.menu.MenuSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import com.wireser.minecraft.playerdata.Profile;
import com.wireser.minecraft.utils.DateTimeUtils;

/**
 * Owns staff moderation commands, timed punishments and PRIUS records.
 *
 * <p>{@link TimersModule} persists generic clocks; this module gives the mute,
 * jail, buildoff and tempban keys their moderation meaning. Buildoff is shown
 * to players as {@code Marked} and changes only the effective group, preserving
 * the player's assigned database group for automatic restoration.</p>
 *
 * <p>Permanent {@code /ban} and {@code /unban} deliberately remain in core so
 * disabling this module cannot admit permanently banned players.</p>
 */
public final class ModerationModule extends BaseModule {

    private static final String PRIUS_TABLE = "player_prius_entries";
    private static final int MAX_COMMAND_SUGGESTIONS = 20;
    private static final int PRIUS_ENTRIES_PER_PAGE = 8;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int CHAT_CLEAR_LINES = 100;
    private static final long RESTRICTION_MESSAGE_DELAY_MILLIS = 2_000L;
    private static final Duration MAX_MODERATION_SENTENCE = Duration.ofDays(365);
    private static final DateTimeFormatter STAFF_DATE_TIME =
            DateTimeFormatter.ofPattern("yy-MM-dd HH:mm");
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
    private final NamespacedKey hiddenPreviousGameModeKey;

    private TimersModule timers;

    public ModerationModule() {
        super("Moderation", "1.0.0");
        hiddenPreviousGameModeKey = new NamespacedKey(plugin, "moderation_previous_gamemode");
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
                .syntax(helpPlain("modhelp", "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestModHelp));
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
                .description(helpPlain("cc", "summary")).minimumGroup(GroupType.MODERATOR));
        addCommand("clear", command -> command.description(helpPlain("clear", "summary"))
                .syntax(helpPlain("clear", "syntax")).minimumGroup(GroupType.ADMIN)
                .tabHandler(this::suggestOnlinePlayers));
    }

    private void addPunishmentCommand(String label) {
        addCommand(label, command -> command.description(helpPlain(label, "summary"))
                .syntax(helpPlain(label, "syntax")).minimumGroup(GroupType.MODERATOR)
                .tabHandler(this::suggestPunishmentArguments));
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
            restoreHiddenStaff(profile, false);
        }

        mutedPlayers.clear();
        jailedPlayers.clear();
        markedPlayers.clear();
        temporarilyBannedPlayers.clear();
        hiddenStaffModes.clear();
        lastRestrictionMessage.clear();
    }

    /**
     * Rebuilds timer-derived state, repairs an interrupted hide session and
     * delivers only current punishments plus previously unseen warnings.
     */
    @Override
    public void onProfileLoaded(Profile profile) {
        if (profile == null || timers == null) return;

        restoreHiddenStaff(profile, false);
        hideExistingStaffFromViewer(profile);

        for (Punishment punishment : Punishment.values()) {
            try {
                TimersModule.PlayerTimer timer =
                        timers.findTimer(profile, punishment.timerKey).orElse(null);
                applyPunishmentState(punishment, profile, timer, false);
            } catch (SQLException exception) {
                logError("Failed to synchronize " + punishment.timerKey
                        + " for playerId=" + profile.getId() + ".", exception);
            }
        }

        notifyCurrentPunishments(profile);
        deliverPendingWarnings(profile);
    }

    /** Restores hide state before the player profile leaves RAM. */
    @Override
    public void onProfileUnloaded(Profile profile) {
        if (profile == null) return;

        restoreHiddenStaff(profile, false);
        mutedPlayers.remove(profile.getId());
        jailedPlayers.remove(profile.getId());
        markedPlayers.remove(profile.getId());
        temporarilyBannedPlayers.remove(profile.getId());
        lastRestrictionMessage.remove(profile.getId());
    }

    /** Routes registered labels to their compact command handlers. */
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

    /** Returns whether chat should be cancelled for this profile. */
    public boolean shouldCancelChat(Profile profile) {
        if (!isMuted(profile)) return false;

        getServer().getScheduler().runTask(plugin,
                () -> profile.sendMessageIfOnline(getText("moderation.mute.chat_blocked")));
        return true;
    }

    public boolean isMuted(Profile profile) {
        return profile != null && mutedPlayers.contains(profile.getId());
    }

    public boolean isJailed(Profile profile) {
        return profile != null && jailedPlayers.contains(profile.getId());
    }

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
     * Resolves a tempban before an online profile exists. The disconnect text
     * exposes the current reason, but never the staff member who issued it.
     */
    public Component getTemporaryBanLoginMessage(UUID uuid) {
        if (uuid == null) return null;

        try {
            Integer playerId = getDB().getInt("players", "id", "uuid = ?", List.of(uuid.toString()));
            if (playerId == null || playerId <= 0) return null;

            Map<String, Object> row = getDB().getRow("player_timers",
                    List.of("started_at", "expires_at"), "player_id = ? AND timer_key = ?",
                    List.of(playerId, Punishment.TEMPBAN.timerKey));
            if (row.isEmpty()) return null;

            LocalDateTime expiresAt = DateTimeUtils.readNullableDateTime(row.get("expires_at"));
            if (expiresAt != null && !expiresAt.isAfter(now())) return null;

            String reason = findCurrentPunishmentReason(
                    playerId, Punishment.TEMPBAN);
            if (expiresAt == null) {
                return getText("moderation.tempban.login_indefinite", reason);
            }

            return getText("moderation.tempban.login_timed", formatDateTime(expiresAt),
                    formatModerationDuration(Duration.between(now(), expiresAt)), reason);
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
                sender.sendMessage(getText("moderation.help.topic_usage", String.join(", ", HELP_TOPICS)));
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

    /** Builds a hardcoded navigation menu while keeping prose in lang.yml. */
    private boolean openModerationHelp(Profile sender) {
        MenuSession menu = menus().create(sender, 27, getText("moderation.help.menu_title"))
                .requireMinimumGroup(GroupType.MODERATOR, getText("command.no_permission"));
        int slot = 0;

        for (HelpEntry entry : HELP_ENTRIES.values()) {
            ItemStack icon = MenuItemBuilder.of(entry.icon)
                    .name(getText("moderation.help.menu_name", "/" + entry.label))
                    .lore(getText("moderation.help.menu_lore", helpPlain(entry.label, "summary")))
                    .build();
            menu.button(slot++, icon, menu.runCommand("modhelp " + entry.label));
        }

        menu.fillEmpty(Material.GRAY_STAINED_GLASS_PANE);
        return menu.open();
    }

    private boolean handlePlayerSheet(Profile sender, String[] arguments) {
        if (arguments.length != 1) return sendUsage(sender, helpPlain("player", "syntax"));

        Profile target = requireKnownPlayer(sender, arguments[0]);
        if (target == null || !mayInspectStaffRecord(sender, target)) return true;

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
                    formatDatabaseDate(playerRow.get("last_login_at")), punishments,
                    permanentlyBanned ? lang.plain("moderation.player.affirmative")
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
        if (target == null || !mayInspectStaffRecord(sender, target)) return true;

        int page = arguments.length == 1 ? 1 : parsePositivePage(arguments[1]);
        if (page < 1) {
            sender.sendMessage(getText("moderation.prius.invalid_page"));
            return true;
        }

        try {
            List<Map<String, Object>> rows = getDB().getRows(PRIUS_TABLE,
                    List.of("id", "staff_id", "entry_class", "entry_type", "entry_action",
                            "recorded_at", "entry_text"),
                    "player_id = ?", List.of(target.getId()));
            rows.sort(Comparator.comparingInt(
                    (Map<String, Object> row) -> asInt(row.get("id"))).reversed());

            int totalPages = Math.max(1,
                    (rows.size() + PRIUS_ENTRIES_PER_PAGE - 1) / PRIUS_ENTRIES_PER_PAGE);
            if (page > totalPages) {
                sender.sendMessage(getText("moderation.prius.page_missing", page, totalPages));
                return true;
            }

            sender.sendMessage(getText("moderation.prius.header", target.getIgn(), page,
                    totalPages, rows.size()));
            if (page == 1) sendPriusSummary(sender, target, rows);

            int fromIndex = (page - 1) * PRIUS_ENTRIES_PER_PAGE;
            int toIndex = Math.min(rows.size(), fromIndex + PRIUS_ENTRIES_PER_PAGE);

            if (rows.isEmpty()) {
                sender.sendMessage(getText("moderation.prius.empty"));
            } else {
                for (Map<String, Object> row : rows.subList(fromIndex, toIndex)) {
                    sendPriusEntry(sender, row);
                }
            }

            sender.sendMessage(createPriusFooter(target.getIgn(), page, totalPages));
        } catch (SQLException exception) {
            logError("Failed to load PRIUS for playerId=" + target.getId() + ".", exception);
            sender.sendMessage(getText("moderation.database_error"));
        }

        return true;
    }

    private void sendPriusSummary(Profile sender, Profile target, List<Map<String, Object>> rows)
            throws SQLException {
        PriusSummary summary = PriusSummary.from(rows);
        List<String> active = new ArrayList<>();

        for (Punishment punishment : Punishment.values()) {
            if (timers.hasTimer(target, punishment.timerKey)) active.add(punishment.displayName);
        }
        if (Boolean.TRUE.equals(getDB().getBoolean("players", "flag_banned",
                "id = ?", List.of(target.getId())))) active.add("Permanent ban");

        sender.sendMessage(getText("moderation.prius.summary_punishments", summary.warnings(),
                summary.mutes(), summary.jails(), summary.buildoffs(), summary.tempbans(),
                summary.permanentBans()));
        sender.sendMessage(getText("moderation.prius.summary_other", summary.kicks(),
                summary.notes()));
        sender.sendMessage(getText("moderation.prius.summary_active",
                active.isEmpty() ? lang.plain("moderation.prius.summary_none")
                        : String.join(", ", active)));
    }

    private void sendPriusEntry(Profile sender, Map<String, Object> row) {
        String entryClass = String.valueOf(row.get("entry_class"));
        String entryType = String.valueOf(row.get("entry_type"));
        String action = row.get("entry_action") == null ? "" : row.get("entry_action").toString();
        String typeAndAction = action.isBlank()
                ? entryType.toUpperCase(Locale.ROOT)
                : entryType.toUpperCase(Locale.ROOT) + "/" + action.toUpperCase(Locale.ROOT);
        String headerKey = switch (entryClass.toLowerCase(Locale.ROOT)) {
            case "punishment" -> "moderation.prius.entry_punishment";
            case "warning" -> "moderation.prius.entry_warning";
            case "note" -> "moderation.prius.entry_note";
            default -> "moderation.prius.entry_info";
        };

        sender.sendMessage(getText(headerKey, formatDatabaseDate(row.get("recorded_at")),
                typeAndAction, resolveStaffName(asInt(row.get("staff_id")))));
        sender.sendMessage(getText("moderation.prius.entry_text",
                String.valueOf(row.get("entry_text"))));
    }

    private Component createPriusFooter(String username, int page, int totalPages) {
        Component previous = page > 1
                ? getText("moderation.prius.previous").clickEvent(
                        ClickEvent.runCommand("/prius " + username + " " + (page - 1)))
                        .hoverEvent(HoverEvent.showText(getText("moderation.prius.previous_hover")))
                : getText("moderation.prius.previous_disabled");
        Component next = page < totalPages
                ? getText("moderation.prius.next").clickEvent(
                        ClickEvent.runCommand("/prius " + username + " " + (page + 1)))
                        .hoverEvent(HoverEvent.showText(getText("moderation.prius.next_hover")))
                : getText("moderation.prius.next_disabled");

        return previous.append(getText("moderation.prius.page", page, totalPages)).append(next);
    }

    private boolean handleNote(Profile sender, String[] arguments) {
        if (arguments.length < 2) return sendUsage(sender, helpPlain("note", "syntax"));

        Profile target = requireKnownPlayer(sender, arguments[0]);
        if (target == null || !mayInspectStaffRecord(sender, target)) return true;

        String note = joinArguments(arguments, 1);
        if (!isValidReason(note)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (recordPrius(target, sender, "note", "staff_note", "create",
                null, null, null, note)) {
            sender.sendMessage(getText("moderation.note.saved", target.getIgn()));
        } else {
            sender.sendMessage(getText("moderation.database_error"));
        }

        return true;
    }

    /** Records online and offline warnings; offline warnings are delivered once on login. */
    private boolean handleWarn(Profile sender, String[] arguments) {
        if (arguments.length < 1) return sendUsage(sender, helpPlain("warn", "syntax"));

        Profile target = requireActionTarget(sender, arguments[0], false);
        if (target == null) return true;

        String reason = arguments.length == 1
                ? lang.plain("moderation.warn.default_reason")
                : joinArguments(arguments, 1);
        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        LocalDateTime notifiedAt = target.isOnline() ? now() : null;
        if (!recordPrius(target, sender, "warning", "warning", "issue",
                null, null, notifiedAt, reason)) {
            sender.sendMessage(getText("moderation.database_error"));
            return true;
        }

        target.sendMessageIfOnline(getText("moderation.warn.received", reason));
        sender.sendMessage(getText(target.isOnline()
                ? "moderation.warn.sent_online"
                : "moderation.warn.saved_offline", target.getIgn()));
        return true;
    }

    private boolean recordPrius(Profile target, Profile staff, String entryClass, String entryType,
            String entryAction, Long durationSeconds, LocalDateTime expiresAt,
            LocalDateTime notifiedAt, String entryText) {
        if (target == null || staff == null || target.getId() <= 0 || staff.getId() < 0
                || entryText == null || entryText.isBlank()) return false;

        try {
            return getDB().insert(PRIUS_TABLE,
                    List.of("player_id", "staff_id", "entry_class", "entry_type",
                            "entry_action", "duration_seconds", "expires_at", "notified_at",
                            "entry_text"),
                    java.util.Arrays.asList(target.getId(), staff.getId(), entryClass, entryType,
                            entryAction, durationSeconds, expiresAt, notifiedAt, entryText)) == 1;
        } catch (SQLException exception) {
            logError("Failed to add PRIUS entry for playerId=" + target.getId() + ".", exception);
            return false;
        }
    }

    private void deliverPendingWarnings(Profile profile) {
        if (!profile.isOnline()) return;

        try {
            List<Map<String, Object>> warnings = getDB().getRows(PRIUS_TABLE,
                    List.of("id", "entry_text"),
                    "player_id = ? AND entry_class = ? AND notified_at IS NULL",
                    List.of(profile.getId(), "warning"));
            warnings.sort(Comparator.comparingInt(row -> asInt(row.get("id"))));

            for (Map<String, Object> warning : warnings) {
                profile.sendMessage(getText("moderation.warn.received",
                        String.valueOf(warning.get("entry_text"))));
                getDB().update(PRIUS_TABLE, List.of("notified_at"), List.of(now()),
                        "id = ?", asInt(warning.get("id")));
            }
        } catch (SQLException exception) {
            logError("Failed to deliver pending warnings for playerId=" + profile.getId() + ".",
                    exception);
        }
    }

    // =====================================================================
    // Direct staff actions
    // =====================================================================

    private boolean handleKick(Profile sender, String[] arguments) {
        if (arguments.length < 2) return sendUsage(sender, helpPlain("kick", "syntax"));

        Profile target = requireActionTarget(sender, arguments[0], true);
        if (target == null) return true;

        String reason = joinArguments(arguments, 1);
        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (!recordPrius(target, sender, "punishment", "kick", "execute",
                null, null, null, reason)) {
            sender.sendMessage(getText("moderation.database_error"));
            return true;
        }

        target.getPlayer().kick(getText("moderation.kick.disconnect", reason));
        sender.sendMessage(getText("moderation.kick.completed", target.getIgn()));
        return true;
    }

    private boolean handleInventory(Profile sender, String[] arguments) {
        if (arguments.length < 1 || arguments.length > 2) {
            return sendUsage(sender, helpPlain("inv", "syntax"));
        }

        Profile target = requireInventoryTarget(sender, arguments[0]);
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

        if (isHidden(sender)) {
            restoreHiddenStaff(sender, true);
            return true;
        }

        GameMode previousMode = player.getGameMode();
        hiddenStaffModes.put(sender.getUuid(), previousMode);
        player.getPersistentDataContainer().set(hiddenPreviousGameModeKey,
                PersistentDataType.STRING, previousMode.name());

        for (Player viewer : getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(sender.getUuid())) viewer.hidePlayer(plugin, player);
        }

        player.setGameMode(GameMode.SPECTATOR);
        sender.sendMessage(getText("moderation.hide.hidden"));
        return true;
    }

    private boolean isHidden(Profile profile) {
        return hiddenStaffModes.containsKey(profile.getUuid())
                || profile.getPlayer() != null && profile.getPlayer().getPersistentDataContainer()
                        .has(hiddenPreviousGameModeKey, PersistentDataType.STRING);
    }

    /** Restores visibility and the mode saved both in RAM and persistent player data. */
    private void restoreHiddenStaff(Profile profile, boolean notify) {
        if (profile == null || profile.getPlayer() == null || !isHidden(profile)) return;

        Player player = profile.getPlayer();
        GameMode previousMode = hiddenStaffModes.remove(profile.getUuid());
        String storedMode = player.getPersistentDataContainer().get(hiddenPreviousGameModeKey,
                PersistentDataType.STRING);
        player.getPersistentDataContainer().remove(hiddenPreviousGameModeKey);

        if (previousMode == null && storedMode != null) {
            try {
                previousMode = GameMode.valueOf(storedMode);
            } catch (IllegalArgumentException ignored) {
                previousMode = GameMode.SURVIVAL;
            }
        }

        for (Player viewer : getServer().getOnlinePlayers()) viewer.showPlayer(plugin, player);
        if (previousMode != null) player.setGameMode(previousMode);
        if (notify) profile.sendMessage(getText("moderation.hide.visible"));
    }

    private void hideExistingStaffFromViewer(Profile viewerProfile) {
        if (viewerProfile.getPlayer() == null) return;

        for (UUID hiddenUuid : hiddenStaffModes.keySet()) {
            Profile hiddenProfile = profiles().getOnlineProfile(hiddenUuid);
            if (hiddenProfile != null && hiddenProfile.getPlayer() != null
                    && viewerProfile.getId() != hiddenProfile.getId()) {
                viewerProfile.getPlayer().hidePlayer(plugin, hiddenProfile.getPlayer());
            }
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
                : requireActionTarget(sender, arguments[0], true);
        if (target == null) return true;

        target.getPlayer().getInventory().clear();
        target.getPlayer().setItemOnCursor(null);
        sender.sendMessage(getText("moderation.clear.completed", target.getIgn()));

        if (target.getId() != sender.getId()) {
            target.sendMessage(getText("moderation.clear.received"));
        }

        return true;
    }

    // =====================================================================
    // Generic timed punishments
    // =====================================================================

    private boolean handlePunishment(Profile sender, Punishment punishment, String[] arguments) {
        if (arguments.length == 0) return sendPunishmentUsage(sender, punishment);

        Profile target = requirePunishmentTarget(sender, punishment, arguments[0]);
        if (target == null) return true;
        if (arguments.length == 1) return showPunishment(sender, target, punishment);

        String operation = arguments[1].toLowerCase(Locale.ROOT);
        if (operation.equals("off") || operation.equals("0")) {
            if (arguments.length < 3) return sendPunishmentUsage(sender, punishment);
            return removePunishment(sender, target, punishment, joinArguments(arguments, 2));
        }

        boolean explicitOperation = TIMER_ACTIONS.contains(operation);
        String durationInput = explicitOperation
                ? arguments.length > 2 ? arguments[2] : ""
                : arguments[1];
        int reasonStart = explicitOperation ? 3 : 2;
        if (durationInput.isBlank() || arguments.length <= reasonStart) {
            return sendPunishmentUsage(sender, punishment);
        }

        String reason = joinArguments(arguments, reasonStart);
        if (!isValidReason(reason)) {
            sender.sendMessage(getText("moderation.reason_invalid", MAX_REASON_LENGTH));
            return true;
        }

        if (durationInput.equalsIgnoreCase("infinite")) {
            if ((explicitOperation && !operation.equals("set")) || !punishment.allowsIndefinite) {
                sender.sendMessage(getText("moderation.punishment.infinite_not_allowed",
                        punishment.displayName));
                return true;
            }

            return setIndefinitePunishment(sender, target, punishment, reason);
        }

        Optional<Duration> parsedDuration = TimersModule.parseDuration(durationInput);
        if (parsedDuration.isEmpty() || TimersModule.containsSecondsUnit(durationInput)
                || parsedDuration.get().getSeconds() % 60L != 0L) {
            sender.sendMessage(getText("moderation.punishment.invalid_duration", durationInput));
            return true;
        }

        String requestedOperation = explicitOperation ? operation : "automatic";
        return changeFinitePunishment(sender, target, punishment, requestedOperation,
                parsedDuration.get(), reason);
    }

    private boolean sendPunishmentUsage(Profile sender, Punishment punishment) {
        sender.sendMessage(getText("moderation.punishment.usage", punishment.commandLabel));
        return true;
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
                        punishment.displayName,
                        findCurrentPunishmentReason(target.getId(), punishment)));
            } else {
                sender.sendMessage(getText("moderation.punishment.active_timed", target.getIgn(),
                        punishment.displayName, formatDateTime(timer.expiresAt()),
                        formatModerationDuration(timer.remainingAt(now())),
                        findCurrentPunishmentReason(target.getId(), punishment)));
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
            TimersModule.PlayerTimer previous =
                    timers.findTimer(target, punishment.timerKey).orElse(null);
            if (previous != null && !mayShortenPunishment(sender)) return rejectReduction(sender);

            TimersModule.PlayerTimer current =
                    timers.setIndefiniteTimer(target, punishment.timerKey);
            if (!recordPrius(target, sender, "punishment", punishment.timerKey, "set",
                    null, null, null, reason)) {
                sender.sendMessage(getText("moderation.prius.save_failed"));
            }

            sender.sendMessage(getText("moderation.punishment.set_indefinite", target.getIgn(),
                    punishment.displayName));
            notifyCurrentPunishment(target, punishment, current, reason);
            announceStaffEmergency(sender, target, punishment, "set", "indefinite", reason);
        } catch (SQLException exception) {
            handlePunishmentDatabaseFailure(sender, target, punishment, exception);
        }

        return true;
    }

    private boolean changeFinitePunishment(Profile sender, Profile target, Punishment punishment,
            String requestedOperation, Duration duration, String reason) {
        try {
            TimersModule.PlayerTimer previous =
                    timers.findTimer(target, punishment.timerKey).orElse(null);
            String operation = requestedOperation;

            if (operation.equals("automatic")) operation = previous == null ? "set" : "add";
            if (operation.equals("add") && previous == null) operation = "set";

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
            if ((operation.equals("sub") || operation.equals("set") && previous != null)
                    && !mayShortenPunishment(sender)) return rejectReduction(sender);
            if (!fitsSentenceLimit(previous, operation, duration)) {
                sender.sendMessage(getText("moderation.punishment.too_long", 365));
                return true;
            }

            TimersModule.PlayerTimer current;
            switch (operation) {
                case "set" -> current = timers.setTimer(target, punishment.timerKey, duration);
                case "add" -> current = timers.addTime(target, punishment.timerKey, duration);
                case "sub" -> current = timers.subtractTime(target, punishment.timerKey, duration)
                        .orElse(null);
                default -> {
                    sender.sendMessage(getText("moderation.punishment.unknown_action", operation));
                    return true;
                }
            }

            if (!recordPrius(target, sender, "punishment", punishment.timerKey, operation,
                    duration.getSeconds(), current == null ? null : current.expiresAt(),
                    null, reason)) {
                sender.sendMessage(getText("moderation.prius.save_failed"));
            }

            String formattedDuration = formatModerationDuration(duration);
            if (current == null) {
                sender.sendMessage(getText("moderation.punishment.ended", target.getIgn(),
                        punishment.displayName));
                target.sendMessageIfOnline(getText("moderation.punishment.removed_target",
                        punishment.displayName, reason));
            } else {
                sender.sendMessage(getText("moderation.punishment.changed", target.getIgn(),
                        punishment.displayName, operation, formattedDuration,
                        formatDateTime(current.expiresAt())));
                notifyCurrentPunishment(target, punishment, current, reason);
            }

            announceStaffEmergency(sender, target, punishment, operation,
                    current == null ? "ended" : formattedDuration, reason);
        } catch (IllegalArgumentException | IllegalStateException | DateTimeException exception) {
            sender.sendMessage(getText("moderation.punishment.invalid_duration",
                    formatModerationDuration(duration)));
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
        if (!mayShortenPunishment(sender)) return rejectReduction(sender);

        try {
            if (!timers.resetTimer(target, punishment.timerKey)) {
                sender.sendMessage(getText("moderation.punishment.inactive", target.getIgn(),
                        punishment.displayName));
                return true;
            }

            if (!recordPrius(target, sender, "punishment", punishment.timerKey, "off",
                    null, null, null, reason)) {
                sender.sendMessage(getText("moderation.prius.save_failed"));
            }
            sender.sendMessage(getText("moderation.punishment.removed", target.getIgn(),
                    punishment.displayName));
            target.sendMessageIfOnline(getText("moderation.punishment.removed_target",
                    punishment.displayName, reason));
            announceStaffEmergency(sender, target, punishment, "off", "ended", reason);
        } catch (SQLException exception) {
            handlePunishmentDatabaseFailure(sender, target, punishment, exception);
        }

        return true;
    }

    /** Moderators may extend sentences; Senior Moderators may shorten or replace them. */
    private boolean mayShortenPunishment(Profile sender) {
        return sender.meetsMinimumAssignedGroup(GroupType.SENIOR_MODERATOR);
    }

    private boolean rejectReduction(Profile sender) {
        sender.sendMessage(getText("moderation.punishment.reduction_denied"));
        return true;
    }

    private boolean fitsSentenceLimit(TimersModule.PlayerTimer previous, String operation,
            Duration change) {
        if (operation.equals("sub")) return true;
        if (operation.equals("set") || previous == null) {
            return change.compareTo(MAX_MODERATION_SENTENCE) <= 0;
        }
        if (previous.isIndefinite()) return false;

        Duration proposedSentence = Duration.between(previous.startedAt(),
                previous.expiresAt().plus(change));
        return proposedSentence.compareTo(MAX_MODERATION_SENTENCE) <= 0;
    }

    /** Sends one consolidated current state, never the staff identity or action history. */
    private void notifyCurrentPunishment(Profile target, Punishment punishment,
            TimersModule.PlayerTimer timer, String reason) {
        if (!target.isOnline() || punishment == Punishment.TEMPBAN || timer == null) return;

        if (timer.isIndefinite()) {
            target.sendMessage(getText("moderation.punishment.active_target_indefinite",
                    punishment.displayName, reason));
            return;
        }

        Duration fullSentence = Duration.between(timer.startedAt(), timer.expiresAt());
        target.sendMessage(getText("moderation.punishment.active_target", punishment.displayName,
                formatModerationDuration(fullSentence), formatDateTime(timer.expiresAt()),
                reason));
    }

    /** Sends one line per currently active punishment after login. */
    private void notifyCurrentPunishments(Profile profile) {
        if (!profile.isOnline()) return;

        for (Punishment punishment : Punishment.values()) {
            if (punishment == Punishment.TEMPBAN) continue;

            try {
                TimersModule.PlayerTimer timer =
                        timers.findTimer(profile, punishment.timerKey).orElse(null);
                if (timer != null) notifyCurrentPunishment(profile, punishment, timer,
                        findCurrentPunishmentReason(profile.getId(), punishment));
            } catch (SQLException exception) {
                logError("Failed to notify active " + punishment.timerKey
                        + " for playerId=" + profile.getId() + ".", exception);
            }
        }
    }

    private String findCurrentPunishmentReason(int playerId, Punishment punishment)
            throws SQLException {
        List<Map<String, Object>> rows = getDB().getRows(PRIUS_TABLE,
                List.of("id", "entry_action", "entry_text"),
                "player_id = ? AND entry_class = ? AND entry_type = ?",
                List.of(playerId, "punishment", punishment.timerKey));

        return rows.stream()
                .max(Comparator.comparingInt(row -> asInt(row.get("id"))))
                .filter(row -> !"off".equalsIgnoreCase(String.valueOf(row.get("entry_action"))))
                .map(row -> String.valueOf(row.get("entry_text")))
                .filter(reason -> !reason.isBlank())
                .orElse(lang.plain("moderation.punishment.reason_unavailable"));
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

        /* Delay one tick so the matching PRIUS reason is committed before the kick text is built. */
        if (punishment == Punishment.TEMPBAN && newlyActive && profile.isOnline()) {
            getServer().getScheduler().runTask(plugin, () -> kickActiveTempban(profile));
        }

        if (naturallyExpired) {
            profile.sendMessageIfOnline(getText("moderation.punishment.expired",
                    punishment.displayName));
        }
    }

    private void kickActiveTempban(Profile profile) {
        if (!profile.isOnline()) return;

        try {
            TimersModule.PlayerTimer timer =
                    timers.findTimer(profile, Punishment.TEMPBAN.timerKey).orElse(null);
            if (timer == null) return;

            String reason = findCurrentPunishmentReason(
                    profile.getId(), Punishment.TEMPBAN);
            profile.getPlayer().kick(timer.isIndefinite()
                    ? getText("moderation.tempban.login_indefinite", reason)
                    : getText("moderation.tempban.login_timed", formatDateTime(timer.expiresAt()),
                            formatModerationDuration(timer.remainingAt(now())), reason));
        } catch (SQLException exception) {
            logError("Failed to enforce tempban for playerId=" + profile.getId() + ".", exception);
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
    // Staff emergency policy
    // =====================================================================

    private void announceStaffEmergency(Profile sender, Profile target, Punishment punishment,
            String operation, String duration, String reason) {
        if (!punishment.staffEmergency || !isStaff(target)) return;

        String alert = lang.plain("moderation.staff_emergency.alert", sender.getIgn(),
                target.getIgn(), punishment.displayName, operation, duration,
                reason);
        logWarning(alert);

        for (Profile staff : profiles().getOnlineProfiles()) {
            if (staff.getId() == sender.getId() || staff.getId() == target.getId()
                    || !staff.meetsMinimumAssignedGroup(GroupType.SENIOR_MODERATOR)) continue;
            staff.sendMessage(getText("moderation.staff_emergency.alert", sender.getIgn(),
                    target.getIgn(), punishment.displayName, operation, duration,
                    reason));
        }
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

    private Profile requireActionTarget(Profile sender, String username, boolean requireOnline) {
        Profile target = requireKnownPlayer(sender, username);
        if (target == null) return null;

        if (target.getId() == sender.getId()) {
            sender.sendMessage(getText("moderation.cannot_target_self"));
            return null;
        }
        if (isStaff(target) && !sender.meetsMinimumAssignedGroup(GroupType.ADMIN)) {
            sender.sendMessage(getText("moderation.cannot_target_group", target.getIgn()));
            return null;
        }
        if (!isStrictlyHigherGroup(sender, target)) {
            sender.sendMessage(getText("moderation.cannot_target_group", target.getIgn()));
            return null;
        }
        if (requireOnline && !target.isOnline()) {
            sender.sendMessage(getText("moderation.player_offline", target.getIgn()));
            return null;
        }

        return target;
    }

    private Profile requirePunishmentTarget(Profile sender, Punishment punishment, String username) {
        boolean selfRequested = username.equalsIgnoreCase("self")
                || username.equalsIgnoreCase(sender.getIgn());
        Profile target = selfRequested ? sender : requireKnownPlayer(sender, username);
        if (target == null) return null;

        if (punishment.staffEmergency && isStaff(target)) return target;
        if (selfRequested) {
            sender.sendMessage(getText("moderation.cannot_target_self"));
            return null;
        }
        if (isStaff(target) && !sender.meetsMinimumAssignedGroup(GroupType.ADMIN)) {
            sender.sendMessage(getText("moderation.cannot_target_group", target.getIgn()));
            return null;
        }
        if (!isStrictlyHigherGroup(sender, target)) {
            sender.sendMessage(getText("moderation.cannot_target_group", target.getIgn()));
            return null;
        }

        return target;
    }

    /** Inventory inspection is allowed for the same assigned group or a lower one. */
    private Profile requireInventoryTarget(Profile sender, String username) {
        Profile target = requireKnownPlayer(sender, username);
        if (target == null) return null;

        boolean sameOrLower = sender.getAssignedGroup() == target.getAssignedGroup()
                || sender.getAssignedGroup().inheritsFrom(target.getAssignedGroup());
        if (!sameOrLower) {
            sender.sendMessage(getText("moderation.inventory.group_denied", target.getIgn()));
            return null;
        }
        if (!target.isOnline()) {
            sender.sendMessage(getText("moderation.player_offline", target.getIgn()));
            return null;
        }

        return target;
    }

    /** Prevents moderators from reading staff records, including their own. */
    private boolean mayInspectStaffRecord(Profile sender, Profile target) {
        if (!isStaff(target)) return true;

        boolean allowed = sender.meetsMinimumAssignedGroup(GroupType.ADMIN)
                && isStrictlyHigherGroup(sender, target);
        if (!allowed) sender.sendMessage(getText("moderation.staff_record_private"));
        return allowed;
    }

    private boolean isStaff(Profile profile) {
        return profile != null && profile.meetsMinimumAssignedGroup(GroupType.MODERATOR);
    }

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
                if (label.equalsIgnoreCase("buildoff")) suggestions.add("infinite");
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
    // Formatting and records
    // =====================================================================

    private boolean sendUsage(Profile sender, String syntax) {
        sender.sendMessage(getText("command.syntax.usage", syntax));
        return true;
    }

    private String helpPlain(String command, String field) {
        return lang.plain("moderation.help.commands." + command + "." + field);
    }

    private static boolean isValidReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= MAX_REASON_LENGTH
                && reason.indexOf('\n') < 0 && reason.indexOf('\r') < 0;
    }

    private static int parsePositivePage(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String resolveStaffName(int staffId) {
        if (staffId == 0) return "server";
        Profile staff = getOfflinePlayer(staffId);
        return staff == null || staff.getIgn() == null ? "#" + staffId : staff.getIgn();
    }

    private String formatModerationDuration(Duration duration) {
        long seconds = Math.max(0L, duration == null ? 0L : duration.getSeconds());
        if (seconds > 0L && seconds < 60L) {
            return lang.plain("moderation.punishment.less_than_minute");
        }

        long roundedMinutes = (seconds + 59L) / 60L;
        long weeks = roundedMinutes / 10_080L;
        roundedMinutes %= 10_080L;
        long days = roundedMinutes / 1_440L;
        roundedMinutes %= 1_440L;
        long hours = roundedMinutes / 60L;
        long minutes = roundedMinutes % 60L;
        List<String> parts = new ArrayList<>();

        if (weeks > 0) parts.add(weeks + "w");
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0 || parts.isEmpty()) parts.add(minutes + "m");
        return String.join(" ", parts);
    }

    private static String formatDatabaseDate(Object value) {
        return DateTimeUtils.formatDatabaseDate(value, STAFF_DATE_TIME, "-");
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "-" : dateTime.format(STAFF_DATE_TIME);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
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
        MUTE("mute", "mute", "Mute", false, false),
        JAIL("jail", "jail", "Jail", false, false),
        BUILDOFF("buildoff", "buildoff", "Marked", true, true),
        TEMPBAN("tempban", "tempban", "Temporary ban", false, true);

        private final String commandLabel;
        private final String timerKey;
        private final String displayName;
        private final boolean allowsIndefinite;
        private final boolean staffEmergency;

        Punishment(String commandLabel, String timerKey, String displayName,
                boolean allowsIndefinite, boolean staffEmergency) {
            this.commandLabel = commandLabel;
            this.timerKey = timerKey;
            this.displayName = displayName;
            this.allowsIndefinite = allowsIndefinite;
            this.staffEmergency = staffEmergency;
        }
    }

    private record HelpEntry(String label, Material icon) {}

    private record PriusSummary(int warnings, int mutes, int jails, int buildoffs,
            int tempbans, int permanentBans, int kicks, int notes) {

        private static PriusSummary from(List<Map<String, Object>> rows) {
            int warnings = 0;
            int mutes = 0;
            int jails = 0;
            int buildoffs = 0;
            int tempbans = 0;
            int permanentBans = 0;
            int kicks = 0;
            int notes = 0;

            for (Map<String, Object> row : rows) {
                String entryClass = String.valueOf(row.get("entry_class"));
                String entryType = String.valueOf(row.get("entry_type"));
                String action = String.valueOf(row.get("entry_action"));

                if (entryClass.equalsIgnoreCase("warning")) warnings++;
                if (entryClass.equalsIgnoreCase("note")) notes++;
                if (entryType.equalsIgnoreCase("kick")) kicks++;
                if (!action.equalsIgnoreCase("set")) continue;

                switch (entryType.toLowerCase(Locale.ROOT)) {
                    case "mute" -> mutes++;
                    case "jail" -> jails++;
                    case "buildoff" -> buildoffs++;
                    case "tempban" -> tempbans++;
                    case "ban" -> permanentBans++;
                    default -> { }
                }
            }

            return new PriusSummary(warnings, mutes, jails, buildoffs, tempbans,
                    permanentBans, kicks, notes);
        }
    }
}
