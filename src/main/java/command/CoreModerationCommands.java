package command;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.OfflinePlayer;

import enums.GroupType;
import io.papermc.paper.ban.BanListType;
import main.Main;
import managers.LanguageManager;
import playerdata.Profile;
import playerdata.ProfileManager;

/**
 * Registers the permanent-ban safety commands owned by plugin core.
 *
 * <p>Permanent bans remain available even when the optional moderation module
 * is disabled. The {@code players.flag_banned} value is the plugin's durable
 * state and Paper's profile ban list is updated as the server-level enforcement
 * layer. Timed bans deliberately remain in {@code ModerationModule} as the
 * generic {@code tempban} timer.</p>
 */
public final class CoreModerationCommands {

    private static final String PRIUS_TABLE = "player_prius_entries";
    private static final int MAX_COMMAND_SUGGESTIONS = 20;
    private static final int MAX_REASON_LENGTH = 500;

    private final Main plugin;
    private final CommandCentral commands;
    private final ProfileManager profiles;
    private final LanguageManager language;

    public CoreModerationCommands(Main plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.commands = Objects.requireNonNull(plugin.getCommandCentral(), "commandCentral");
        this.profiles = Objects.requireNonNull(plugin.getProfileManager(), "profileManager");
        this.language = Objects.requireNonNull(plugin.getLanguageManager(), "languageManager");
    }

    /** Registers only the two permanent-ban commands. */
    public void registerCommands() {
        commands.register(new CommandRegistry.Builder("ban", this::handleBan)
                .description(language.plain("core.ban.summary"))
                .syntax(language.plain("core.ban.syntax"))
                .minimumGroup(GroupType.ADMIN)
                .tabHandler(this::suggestRegisteredPlayers)
                .build());
        commands.register(new CommandRegistry.Builder("unban", this::handleUnban)
                .description(language.plain("core.unban.summary"))
                .syntax(language.plain("core.unban.syntax"))
                .minimumGroup(GroupType.ADMIN)
                .tabHandler(this::suggestRegisteredPlayers)
                .build());
    }

    /** Removes core registrations during plugin shutdown. */
    public void unregisterCommands() {
        commands.unregister("ban");
        commands.unregister("unban");
    }

    /**
     * Rebuilds missing Paper profile bans from the authoritative database flag.
     * This makes a manual Paper ban-file loss repairable on the next boot.
     */
    public void synchronizePermanentBans() {
        try {
            List<Map<String, Object>> bannedPlayers = plugin.db().getRows("players",
                    List.of("uuid"), "flag_banned = ?", List.of(true));

            for (Map<String, Object> row : bannedPlayers) {
                Object rawUuid = row.get("uuid");
                if (rawUuid == null) continue;

                try {
                    OfflinePlayer player = plugin.getServer().getOfflinePlayer(
                            UUID.fromString(rawUuid.toString()));
                    if (!player.isBanned()) {
                        player.ban(language.plain("core.ban.default_reason"),
                                (Duration) null, plugin.getName());
                    }
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Invalid UUID on a permanently banned player row: " + rawUuid,
                            exception);
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not synchronize permanent bans. Confirm players.flag_banned exists.",
                    exception);
        }
    }

    private void handleBan(Profile sender, String label, String[] arguments) {
        if (arguments.length < 1) {
            sender.sendMessage(language.line("command.syntax.usage",
                    language.plain("core.ban.syntax")));
            return;
        }

        Profile target = requirePunishableTarget(sender, arguments[0]);
        if (target == null) return;

        String reason = arguments.length == 1
                ? language.plain("core.ban.default_reason")
                : joinArguments(arguments, 1);
        if (!isValidReason(reason)) {
            sender.sendMessage(language.line("core.ban.invalid_reason", MAX_REASON_LENGTH));
            return;
        }

        try {
            if (isPermanentlyBanned(target)) {
                sender.sendMessage(language.line("core.ban.already_banned", target.getIgn()));
                return;
            }

            int changed = plugin.db().update("players", List.of("flag_banned"), List.of(true),
                    "id = ?", target.getId());
            if (changed != 1) {
                sender.sendMessage(language.line("core.ban.database_error"));
                return;
            }

            OfflinePlayer paperPlayer = plugin.getServer().getOfflinePlayer(target.getUuid());
            try {
                paperPlayer.ban(reason, (Duration) null, sender.getIgn());
            } catch (RuntimeException exception) {
                plugin.db().update("players", List.of("flag_banned"), List.of(false),
                        "id = ?", target.getId());
                throw exception;
            }

            recordPrius(target, sender, "ban", "Permanent ban | reason: " + reason);
            if (target.isOnline()) {
                target.getPlayer().kick(language.line("core.ban.disconnect",
                        escapeMiniMessage(reason)));
            }

            sender.sendMessage(language.line("core.ban.completed", target.getIgn()));
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to permanently ban playerId=" + target.getId() + ".", exception);
            sender.sendMessage(language.line("core.ban.database_error"));
        }
    }

    private void handleUnban(Profile sender, String label, String[] arguments) {
        if (arguments.length < 1) {
            sender.sendMessage(language.line("command.syntax.usage",
                    language.plain("core.unban.syntax")));
            return;
        }

        Profile target = profiles.resolveByUsername(arguments[0]);
        if (target == null || target.getId() <= 0) {
            sender.sendMessage(language.line("core.ban.player_not_found", arguments[0]));
            return;
        }

        String reason = arguments.length == 1
                ? language.plain("core.unban.default_reason")
                : joinArguments(arguments, 1);
        if (!isValidReason(reason)) {
            sender.sendMessage(language.line("core.ban.invalid_reason", MAX_REASON_LENGTH));
            return;
        }

        try {
            if (!isPermanentlyBanned(target)) {
                sender.sendMessage(language.line("core.unban.not_banned", target.getIgn()));
                return;
            }

            int changed = plugin.db().update("players", List.of("flag_banned"), List.of(false),
                    "id = ?", target.getId());
            if (changed != 1) {
                sender.sendMessage(language.line("core.ban.database_error"));
                return;
            }

            OfflinePlayer paperPlayer = plugin.getServer().getOfflinePlayer(target.getUuid());
            try {
                plugin.getServer().getBanList(BanListType.PROFILE)
                        .pardon(paperPlayer.getPlayerProfile());
            } catch (RuntimeException exception) {
                plugin.db().update("players", List.of("flag_banned"), List.of(true),
                        "id = ?", target.getId());
                throw exception;
            }

            recordPrius(target, sender, "unban", "Permanent ban removed | reason: " + reason);
            sender.sendMessage(language.line("core.unban.completed", target.getIgn()));
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to unban playerId=" + target.getId() + ".", exception);
            sender.sendMessage(language.line("core.ban.database_error"));
        }
    }

    private boolean isPermanentlyBanned(Profile target) throws SQLException {
        return Boolean.TRUE.equals(plugin.db().getBoolean("players", "flag_banned",
                "id = ?", List.of(target.getId())));
    }

    private Profile requirePunishableTarget(Profile sender, String username) {
        Profile target = profiles.resolveByUsername(username);
        if (target == null || target.getId() <= 0) {
            sender.sendMessage(language.line("core.ban.player_not_found", username));
            return null;
        }

        if (target.getId() == sender.getId()) {
            sender.sendMessage(language.line("core.ban.cannot_target_self"));
            return null;
        }

        boolean strictlyHigher = sender.getAssignedGroup() != target.getAssignedGroup()
                && sender.getAssignedGroup().inheritsFrom(target.getAssignedGroup());
        if (!strictlyHigher) {
            sender.sendMessage(language.line("core.ban.cannot_target_group", target.getIgn()));
            return null;
        }

        return target;
    }

    private void recordPrius(Profile target, Profile staff, String entryType, String text) {
        try {
            plugin.db().insert(PRIUS_TABLE,
                    List.of("player_id", "staff_id", "entry_class", "entry_type", "entry_text"),
                    List.of(target.getId(), staff.getId(), "punishment", entryType, text));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Permanent ban changed, but its PRIUS entry could not be saved for playerId="
                            + target.getId() + ".", exception);
            staff.sendMessage(language.line("core.ban.prius_failed"));
        }
    }

    private List<String> suggestRegisteredPlayers(Profile sender, String label, String[] arguments) {
        if (arguments.length != 1) return List.of();
        return profiles.suggestRegisteredUsernames(arguments[0], sender.getId(),
                MAX_COMMAND_SUGGESTIONS);
    }

    private static String joinArguments(String[] arguments, int firstIndex) {
        return String.join(" ", List.of(arguments).subList(firstIndex, arguments.length)).trim();
    }

    private static boolean isValidReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= MAX_REASON_LENGTH
                && reason.indexOf('\n') < 0 && reason.indexOf('\r') < 0;
    }

    private static String escapeMiniMessage(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("<", "\\<");
    }
}
