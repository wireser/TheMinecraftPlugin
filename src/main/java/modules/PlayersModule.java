package modules;

import enums.FriendshipStatus;
import enums.GroupType;
import enums.NicknameFormattingLevel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import playerdata.Profile;
import utils.Validator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns lightweight player-facing systems which do not justify a separate
 * gameplay module: friendships, explicit trust, nicknames and welcomes.
 *
 * <p>Persistent reads and writes remain inside {@link Profile}. This module
 * decides command behaviour and messages, but contains no SQL.</p>
 */
public final class PlayersModule extends BaseModule {

    private static final List<String> RANDOM_WELCOME_KEYS = List.of(
            "players.welcome.random.1",
            "players.welcome.random.2",
            "players.welcome.random.3",
            "players.welcome.random.4"
    );

    /** Last account name seen by the join listener during this server session. */
    private String lastJoinedUsername;

    public PlayersModule() {
        super("Players", "1.0.0");
    }

    /** Registers only the root commands; subcommand access is checked below. */
    @Override
    protected void registerCommands() {
        addCommand("trust", command -> command.description("Trust a player with your protected space.")
                .syntax("/trust [player]").minimumGroup(GroupType.PLAYER));
        addCommand("untrust", command -> command.description("Remove explicit trust from a player.")
                .syntax("/untrust [player]").minimumGroup(GroupType.PLAYER));
        addCommand("friend", command -> command.description("Send or accept a friend request.")
                .syntax("/friend [player]").minimumGroup(GroupType.PLAYER));
        addCommand("unfriend", command -> command.description("Cancel a request or remove a friend.")
                .syntax("/unfriend [player]").minimumGroup(GroupType.PLAYER));
        addCommand("list", command -> command.description("View one of the server's player lists.")
                .syntax("/list [trust|friends|online|staff]").minimumGroup(GroupType.PUNISHED));
        addCommand("nick", command -> command.description("View a player's nickname.")
                .syntax("/nick [player|set|reset]").minimumGroup(GroupType.PUNISHED));
        addCommand("welcome", command -> command.description("Welcome the latest or a named player.")
                .syntax("/welcome [player|help]").minimumGroup(GroupType.PUNISHED));
    }

    /** Routes root labels to their command behaviour. */
    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null) return false;

        return switch (label.toLowerCase(Locale.ROOT)) {
            case "trust" -> handleTrust(sender, arguments);
            case "untrust" -> handleUntrust(sender, arguments);
            case "friend" -> handleFriend(sender, arguments);
            case "unfriend" -> handleUnfriend(sender, arguments);
            case "list" -> handleList(sender, arguments);
            case "nick" -> handleNick(sender, arguments);
            case "welcome" -> handleWelcome(sender, arguments);
            default -> false;
        };
    }

    /**
     * Called by the central join listener after the profile and module data are
     * ready. The latest name is intentionally session-only.
     */
    public void handlePlayerJoin(Profile profile) {
        if (profile == null) return;

        lastJoinedUsername = profile.getIgn();
        int pendingRequests = profile.getIncomingFriendRequestIds().size();
        if (pendingRequests > 0) {
            profile.sendMessage(getText("players.friend.pending_notice", pendingRequests));
        }
    }

    /** Discards session-only join history when the module is stopped. */
    @Override
    protected void onDisable() {
        lastJoinedUsername = null;
    }

    /** Adds one-way trust unless friendship already grants the same access. */
    private boolean handleTrust(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "trust");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null || rejectSelfTarget(sender, target)) return true;
        if (sender.isFriendWith(target)) {
            sender.sendMessage(getText("players.trust.friend_already_trusted", target.getIgn()));
            return true;
        }
        if (sender.isTrustingPlayer(target)) {
            sender.sendMessage(getText("players.trust.already_trusted", target.getIgn()));
            return true;
        }
        if (!sender.addTrustedPlayer(target)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.trust.added", target.getIgn()));
        notifyOnline(target, "players.trust.added_target", sender.getIgn());
        return true;
    }

    /** Removes explicit one-way trust without changing friendship. */
    private boolean handleUntrust(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "trust");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null || rejectSelfTarget(sender, target)) return true;
        if (!sender.isTrustingPlayer(target)) {
            String key = sender.isFriendWith(target)
                    ? "players.trust.friend_access_remains"
                    : "players.trust.not_trusted";
            sender.sendMessage(getText(key, target.getIgn()));
            return true;
        }
        if (!sender.removeTrustedPlayer(target)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.trust.removed", target.getIgn()));
        notifyOnline(target, "players.trust.removed_target", sender.getIgn());
        return true;
    }

    /** Sends a request, or accepts an incoming request from the same player. */
    private boolean handleFriend(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "friends");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null || rejectSelfTarget(sender, target)) return true;

        FriendshipStatus before = sender.getFriendshipStatus(target);
        if (before == FriendshipStatus.ACCEPTED) {
            sender.sendMessage(getText("players.friend.already_friends", target.getIgn()));
            return true;
        }
        if (before == FriendshipStatus.OUTGOING_REQUEST) {
            sender.sendMessage(getText("players.friend.already_requested", target.getIgn()));
            return true;
        }

        FriendshipStatus after = sender.sendFriendRequest(target);
        if (before == FriendshipStatus.INCOMING_REQUEST && after == FriendshipStatus.ACCEPTED) {
            sender.sendMessage(getText("players.friend.accepted", target.getIgn()));
            notifyOnline(target, "players.friend.accepted_target", sender.getIgn());
        } else if (after == FriendshipStatus.OUTGOING_REQUEST) {
            sender.sendMessage(getText("players.friend.request_sent", target.getIgn()));
            notifyOnline(target, "players.friend.request_received", sender.getIgn());
        } else {
            sender.sendMessage(getText("players.data_update_failed"));
        }
        return true;
    }

    /** Cancels, declines or removes the pair's current friendship row. */
    private boolean handleUnfriend(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "friends");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null || rejectSelfTarget(sender, target)) return true;

        FriendshipStatus status = sender.getFriendshipStatus(target);
        if (status == FriendshipStatus.NONE) {
            sender.sendMessage(getText("players.friend.no_relationship", target.getIgn()));
            return true;
        }
        if (!sender.deleteFriendship(target)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        String senderKey = switch (status) {
            case ACCEPTED -> "players.friend.removed";
            case OUTGOING_REQUEST -> "players.friend.request_cancelled";
            case INCOMING_REQUEST -> "players.friend.request_declined";
            default -> "players.data_update_failed";
        };
        String targetKey = switch (status) {
            case ACCEPTED -> "players.friend.removed_target";
            case OUTGOING_REQUEST -> "players.friend.request_cancelled_target";
            case INCOMING_REQUEST -> "players.friend.request_declined_target";
            default -> "players.data_update_failed";
        };

        sender.sendMessage(getText(senderKey, target.getIgn()));
        notifyOnline(target, targetKey, sender.getIgn());
        return true;
    }

    /** Lists social relations or the currently online population. */
    private boolean handleList(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            String key = sender.meetsMinimumGroup(GroupType.PLAYER)
                    ? "players.list.available_player"
                    : "players.list.available_basic";
            sender.sendMessage(getText(key));
            return true;
        }
        if (arguments.length != 1) {
            sender.sendMessage(getText("command.syntax.usage", "/list [trust|friends|online|staff]"));
            return true;
        }

        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "trust" -> listTrustedPlayers(sender);
            case "friends" -> listFriendships(sender);
            case "online" -> listOnlinePlayers(sender, false);
            case "staff" -> listOnlinePlayers(sender, true);
            default -> {
                sender.sendMessage(getText("players.list.unknown", arguments[0]));
                yield true;
            }
        };
    }

    /** Shows nickname data or performs moderator-only changes. */
    private boolean handleNick(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            if (sender.getNick() == null) {
                sender.sendMessage(getText("players.nick.own_unset"));
            } else {
                sender.sendMessage(getText("players.nick.own", sender.getNick()));
            }
            return true;
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (action.equals("set") || action.equals("reset")) {
            if (!sender.meetsMinimumGroup(GroupType.MODERATOR)) {
                sender.sendMessage(getText("command.no_sub_permission"));
                return true;
            }
            return action.equals("set")
                    ? handleNicknameSet(sender, arguments)
                    : handleNicknameReset(sender, arguments);
        }

        if (arguments.length != 1) {
            sender.sendMessage(getText("command.syntax.usage", "/nick [username|nickname]"));
            return true;
        }

        Profile target = profiles().resolveByUsernameOrNickname(arguments[0]);
        if (target == null) {
            sender.sendMessage(getText("players.player_not_found", arguments[0]));
        } else if (target.getNick() == null) {
            sender.sendMessage(getText("players.nick.target_unset", target.getIgn()));
        } else {
            sender.sendMessage(getText("players.nick.target", target.getIgn(), target.getNick()));
        }
        return true;
    }

    /** Handles public welcomes and moderator management of saved join text. */
    private boolean handleWelcome(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            if (lastJoinedUsername == null) {
                sender.sendMessage(getText("players.welcome.no_recent_player"));
            } else {
                broadcastRandomWelcome(lastJoinedUsername);
            }
            return true;
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) {
            sender.sendMessage(getText(sender.meetsMinimumGroup(GroupType.MODERATOR)
                    ? "players.welcome.help_staff"
                    : "players.welcome.help_player"));
            return true;
        }
        if (action.equals("show") || action.equals("set") || action.equals("reset")) {
            if (!sender.meetsMinimumGroup(GroupType.MODERATOR)) {
                sender.sendMessage(getText("command.no_sub_permission"));
                return true;
            }
            return handleWelcomeManagement(sender, action, arguments);
        }
        if (arguments.length != 1) {
            sender.sendMessage(getText("command.syntax.usage", "/welcome [online player]"));
            return true;
        }

        Profile target = profiles().resolveOnlineByUsername(arguments[0]);
        if (target == null) {
            sender.sendMessage(getText("players.welcome.player_not_online", arguments[0]));
        } else {
            broadcastRandomWelcome(target.getIgn());
        }
        return true;
    }

    /** Validates and stores a moderator-supplied nickname. */
    private boolean handleNicknameSet(Profile sender, String[] arguments) {
        if (arguments.length != 3) {
            sender.sendMessage(getText("command.syntax.usage", "/nick set <username> <nickname>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;

        String nickname = arguments[2];
        NicknameFormattingLevel formattingLevel = nicknameFormattingLevel(target);
        if (!Validator.isValidNickname(nickname, formattingLevel)) {
            sender.sendMessage(getText("players.nick.invalid", target.getGroup().getDisplayName()));
            return true;
        }

        String plainNickname = Validator.normalizeNicknameForLookup(nickname);
        Profile usernameOwner = profiles().resolveByUsername(plainNickname);
        if (usernameOwner != null && usernameOwner.getId() != target.getId()) {
            sender.sendMessage(getText("players.nick.matches_username", usernameOwner.getIgn()));
            return true;
        }

        Profile nicknameOwner = profiles().resolveByNickname(plainNickname);
        if (nicknameOwner != null && nicknameOwner.getId() != target.getId()) {
            sender.sendMessage(getText("players.nick.already_used", nicknameOwner.getIgn()));
            return true;
        }
        if (!target.setNick(nickname, formattingLevel)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.nick.set", target.getIgn(), nickname));
        notifyOnline(target, "players.nick.set_target", nickname);
        return true;
    }

    /** Clears both stored nickname columns through the Profile API. */
    private boolean handleNicknameReset(Profile sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage(getText("command.syntax.usage", "/nick reset <username>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;
        if (target.getNick() == null) {
            sender.sendMessage(getText("players.nick.already_unset", target.getIgn()));
            return true;
        }
        if (!target.setNick(null, NicknameFormattingLevel.PLAIN)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.nick.reset", target.getIgn()));
        notifyOnline(target, "players.nick.reset_target", sender.getIgn());
        return true;
    }

    /** Executes the staff-only saved welcome subcommands. */
    private boolean handleWelcomeManagement(Profile sender, String action, String[] arguments) {
        int expectedArguments = action.equals("set") ? 3 : 2;
        if (arguments.length < expectedArguments) {
            sender.sendMessage(getText("command.syntax.usage",
                    action.equals("set")
                            ? "/welcome set <username> <message>"
                            : "/welcome " + action + " <username>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;

        if (action.equals("show")) {
            if (arguments.length != 2) {
                sender.sendMessage(getText("command.syntax.usage", "/welcome show <username>"));
            } else if (target.getWelcome() == null) {
                sender.sendMessage(getText("players.welcome.saved_unset", target.getIgn()));
            } else {
                sender.sendMessage(getText("players.welcome.saved_prefix", target.getIgn())
                        .append(Component.text(target.getWelcome(), NamedTextColor.WHITE)));
            }
            return true;
        }

        if (action.equals("reset")) {
            if (arguments.length != 2) {
                sender.sendMessage(getText("command.syntax.usage", "/welcome reset <username>"));
            } else if (!target.setWelcome(null)) {
                sender.sendMessage(getText("players.data_update_failed"));
            } else {
                sender.sendMessage(getText("players.welcome.reset", target.getIgn()));
            }
            return true;
        }

        String welcomeMessage = String.join(" ",
                java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
        if (!Validator.isValidWelcomeMessage(welcomeMessage)) {
            sender.sendMessage(getText("players.welcome.invalid"));
        } else if (!target.setWelcome(welcomeMessage)) {
            sender.sendMessage(getText("players.data_update_failed"));
        } else {
            sender.sendMessage(getText("players.welcome.set", target.getIgn()));
        }
        return true;
    }

    private boolean listTrustedPlayers(Profile sender) {
        if (!requirePlayerGroup(sender)) return true;
        sendNames(sender, "players.list.trust", resolveNames(sender.getTrustedIds()));
        return true;
    }

    private boolean listFriendships(Profile sender) {
        if (!requirePlayerGroup(sender)) return true;
        sendNames(sender, "players.list.friends", resolveNames(sender.getFriendIds()));
        sendNames(sender, "players.list.incoming", resolveNames(sender.getIncomingFriendRequestIds()));
        sendNames(sender, "players.list.outgoing", resolveNames(sender.getOutgoingFriendRequestIds()));
        return true;
    }

    private boolean listOnlinePlayers(Profile sender, boolean staffOnly) {
        List<String> names = profiles().getOnlineProfiles().stream()
                .filter(profile -> !staffOnly || profile.meetsMinimumGroup(GroupType.MODERATOR))
                .map(Profile::getIgn)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        sendNames(sender, staffOnly ? "players.list.staff" : "players.list.online", names);
        return true;
    }

    /** Converts database IDs to readable last-known account names. */
    private List<String> resolveNames(Collection<Integer> playerIds) {
        List<String> names = new ArrayList<>();
        for (Integer playerId : playerIds) {
            if (playerId == null) continue;
            Profile profile = getOfflinePlayer(playerId);
            names.add(profile == null || profile.getIgn() == null
                    ? "#" + playerId
                    : profile.getIgn());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void sendNames(Profile sender, String key, List<String> names) {
        String joinedNames = names.isEmpty()
                ? lang.plain("players.list.none")
                : String.join(", ", names);
        sender.sendMessage(getText(key, joinedNames));
    }

    /** Resolves only real usernames for commands which modify another profile. */
    private Profile requireKnownUsername(Profile sender, String username) {
        Profile target = getOfflinePlayer(username);
        if (target == null) sender.sendMessage(getText("players.player_not_found", username));
        return target;
    }

    private boolean rejectSelfTarget(Profile sender, Profile target) {
        if (sender.getId() != target.getId()) return false;
        sender.sendMessage(getText("players.cannot_target_self"));
        return true;
    }

    private boolean requirePlayerGroup(Profile sender) {
        if (sender.meetsMinimumGroup(GroupType.PLAYER)) return true;
        sender.sendMessage(getText("command.no_sub_permission"));
        return false;
    }

    private boolean showFutureMenu(Profile sender, String menuName) {
        sender.sendMessage(getText("players.menu_coming_soon", menuName));
        return true;
    }

    /** Determines permitted nickname color syntax from the nickname owner's group. */
    private NicknameFormattingLevel nicknameFormattingLevel(Profile target) {
        if (target.meetsMinimumGroup(GroupType.MODERATOR)) return NicknameFormattingLevel.RGB_COLORS;
        if (target.meetsMinimumGroup(GroupType.DONATOR)) return NicknameFormattingLevel.LEGACY_COLORS;
        return NicknameFormattingLevel.PLAIN;
    }

    private void notifyOnline(Profile target, String key, Object... arguments) {
        if (target != null && target.isOnline()) target.sendMessage(getText(key, arguments));
    }

    private void broadcastRandomWelcome(String username) {
        int index = ThreadLocalRandom.current().nextInt(RANDOM_WELCOME_KEYS.size());
        Component message = getText(RANDOM_WELCOME_KEYS.get(index), username);
        getServer().broadcast(message);
    }
}
