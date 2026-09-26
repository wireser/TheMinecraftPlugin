package modules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import enums.FriendshipStatus;
import enums.GroupType;
import enums.NicknameFormattingLevel;
import playerdata.Profile;
import utils.Validator;

/**
 * Owns lightweight player systems shared by the rest of the plugin.
 *
 * <p>This module currently handles explicit trust, friendships, player lists,
 * nicknames and join welcomes. Persistent work stays behind {@link Profile};
 * command handlers decide policy and never contain SQL.</p>
 */
public final class PlayersModule extends BaseModule {

    /** Maximum number of random welcome templates accepted from lang.yml. */
    private static final int MAX_RANDOM_WELCOME_MESSAGES = 32;

    /** Used when the configured welcome list is missing, empty or invalid. */
    private static final String DEFAULT_RANDOM_WELCOME_MESSAGE =
            "Hello %1! Good to see you!";

    /** Nickname policy lives here so it can later move to module configuration. */
    private static final int MINIMUM_NICKNAME_LENGTH = 3;
    private static final int MAXIMUM_NICKNAME_LENGTH = 20;
    private static final int MAXIMUM_FORMATTED_NICKNAME_LENGTH = 255;
    private static final int MAXIMUM_NICKNAME_SEPARATORS = 3;
    private static final Pattern NICKNAME_VISIBLE_TEXT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._]{" + (MINIMUM_NICKNAME_LENGTH - 2) + ","
                    + (MAXIMUM_NICKNAME_LENGTH - 2) + "}[A-Za-z0-9]");
    private static final Pattern NICKNAME_RGB_COLOR = Pattern.compile("(?i)&#[0-9a-f]{6}");
    private static final Pattern NICKNAME_COLOR_CODE =
            Pattern.compile("(?i)(?:&#[0-9a-f]{6}|&[0-9a-fr])");

    /** Most recent account name observed during this server session. */
    private String lastJoinedUsername;

    /** Active welcome state keyed by the joined player's permanent id. */
    private final Map<Integer, WelcomeSession> welcomeSessions = new HashMap<>();

    public PlayersModule() {
        super("Players", "1.0.0");
    }

    /** Registers the root commands owned by this module. */
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
                .syntax("/nick [username|nickname]").minimumGroup(GroupType.PUNISHED));
        addCommand("welcome", command -> command.description("Welcome the latest or a named player.")
                .syntax("/welcome [player|help]").minimumGroup(GroupType.PUNISHED));
    }

    /** Routes a registered root label to its command handler. */
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

    /** Records session-only join state and reports pending friend requests. */
    public void handlePlayerJoin(Profile profile) {
        if (profile == null) return;

        lastJoinedUsername = profile.getIgn();
        welcomeSessions.put(profile.getId(), new WelcomeSession());
        int pendingRequests = profile.getIncomingFriendRequestIds().size();

        if (pendingRequests > 0) {
            profile.sendMessage(getText("players.friend.pending_notice", pendingRequests));
        }
    }

    /** Removes welcome state when an online profile leaves the server. */
    @Override
    public void onProfileUnloaded(Profile profile) {
        if (profile == null) return;

        welcomeSessions.remove(profile.getId());

        if (profile.getIgn() != null && profile.getIgn().equalsIgnoreCase(lastJoinedUsername)) {
            lastJoinedUsername = null;
        }
    }

    /** Clears session-only state when the module stops. */
    @Override
    protected void onDisable() {
        lastJoinedUsername = null;
        welcomeSessions.clear();
    }

    /** Adds one-way trust unless friendship already grants the same access. */
    private boolean handleTrust(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "trust");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null) return true;

        if (sender.getId() == target.getId()) {
            sender.sendMessage(getText("players.trust.cannot_trust_self"));
            return true;
        }

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
        target.sendMessageIfOnline(getText("players.trust.added_target", sender.getIgn()));

        return true;
    }

    /** Removes explicit one-way trust without changing friendship. */
    private boolean handleUntrust(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "trust");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null) return true;

        if (sender.getId() == target.getId()) {
            sender.sendMessage(getText("players.trust.cannot_untrust_self"));
            return true;
        }

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
        target.sendMessageIfOnline(getText("players.trust.removed_target", sender.getIgn()));

        return true;
    }

    /** Sends a friend request or accepts the target's incoming request. */
    private boolean handleFriend(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "friends");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null) return true;

        if (sender.getId() == target.getId()) {
            sender.sendMessage(getText("players.friend.cannot_befriend_self"));
            return true;
        }

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
            target.sendMessageIfOnline(getText("players.friend.accepted_target", sender.getIgn()));
        } else if (after == FriendshipStatus.OUTGOING_REQUEST) {
            sender.sendMessage(getText("players.friend.request_sent", target.getIgn()));
            target.sendMessageIfOnline(getText("players.friend.request_received", sender.getIgn()));
        } else {
            sender.sendMessage(getText("players.data_update_failed"));
        }

        return true;
    }

    /** Cancels, declines or removes the pair's current friendship row. */
    private boolean handleUnfriend(Profile sender, String[] arguments) {
        if (arguments.length == 0) return showFutureMenu(sender, "friends");

        Profile target = requireKnownUsername(sender, arguments[0]);
        if (target == null) return true;

        if (sender.getId() == target.getId()) {
            sender.sendMessage(getText("players.friend.cannot_unfriend_self"));
            return true;
        }

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
        target.sendMessageIfOnline(getText(targetKey, sender.getIgn()));

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

    /** Shows nickname data or routes moderator-only nickname changes. */
    private boolean handleNick(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            if (sender.getNick() == null) {
                sender.sendMessage(getText("players.nick.own_missing"));
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
            sender.sendMessage(getText("players.nick.target_missing", target.getIgn()));
        } else {
            sender.sendMessage(getText("players.nick.target", target.getIgn(), target.getNick()));
        }

        return true;
    }

    /** Handles public welcomes and routes moderator-only saved-message actions. */
    private boolean handleWelcome(Profile sender, String[] arguments) {
        if (arguments.length == 0) {
            Profile target = lastJoinedUsername == null
                    ? null
                    : profiles().resolveOnlineByUsername(lastJoinedUsername);

            if (target == null) {
                sender.sendMessage(getText("players.welcome.no_recent_arrival"));
            } else {
                sendRandomWelcome(sender, target);
            }

            return true;
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);

        if (action.equals("help")) {
            String key = sender.meetsMinimumGroup(GroupType.MODERATOR)
                    ? "players.welcome.staff_help"
                    : "players.welcome.player_help";
            sender.sendMessage(getText(key));
            return true;
        }

        if (action.equals("show") || action.equals("set") || action.equals("reset")) {
            if (!sender.meetsMinimumGroup(GroupType.MODERATOR)) {
                sender.sendMessage(getText("command.no_sub_permission"));
                return true;
            }

            return switch (action) {
                case "show" -> handleWelcomeShow(sender, arguments);
                case "set" -> handleWelcomeSet(sender, arguments);
                case "reset" -> handleWelcomeReset(sender, arguments);
                default -> false;
            };
        }

        if (arguments.length != 1) {
            sender.sendMessage(getText("command.syntax.usage", "/welcome [player|help]"));
            return true;
        }

        Profile target = profiles().resolveOnlineByUsername(arguments[0]);

        if (target == null) {
            sender.sendMessage(getText("players.welcome.target_offline", arguments[0]));
        } else {
            sendRandomWelcome(sender, target);
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

        if (!Validator.isValidNickname(nickname, formattingLevel,
                MAXIMUM_FORMATTED_NICKNAME_LENGTH, MAXIMUM_NICKNAME_SEPARATORS,
                NICKNAME_VISIBLE_TEXT, NICKNAME_RGB_COLOR, NICKNAME_COLOR_CODE)) {
            String groupName = target.getGroup() == null
                    ? "current"
                    : target.getGroup().getDisplayName();
            sender.sendMessage(getText("players.nick.invalid", groupName));
            return true;
        }

        String plainNickname = Validator.normalizeNicknameForLookup(nickname, NICKNAME_COLOR_CODE);
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

        if (!target.setNick(nickname, plainNickname)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.nick.updated", target.getIgn(), nickname));
        target.sendMessageIfOnline(getText("players.nick.updated_target", nickname));

        return true;
    }

    /** Clears both nickname columns for the requested player. */
    private boolean handleNicknameReset(Profile sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage(getText("command.syntax.usage", "/nick reset <username>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;

        if (target.getNick() == null) {
            sender.sendMessage(getText("players.nick.already_missing", target.getIgn()));
            return true;
        }

        if (!target.setNick(null, null)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.nick.cleared", target.getIgn()));
        target.sendMessageIfOnline(getText("players.nick.cleared_target", sender.getIgn()));

        return true;
    }

    /** Displays the complete saved personal welcome using language placeholders. */
    private boolean handleWelcomeShow(Profile sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage(getText("command.syntax.usage", "/welcome show <username>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;

        String key = target.getWelcome() == null
                ? "players.welcome.personal_missing"
                : "players.welcome.personal_display";
        sender.sendMessage(getText(key, target.getIgn(), target.getWelcome()));
        return true;
    }

    /** Validates and stores all words following the target username. */
    private boolean handleWelcomeSet(Profile sender, String[] arguments) {
        if (arguments.length < 3) {
            sender.sendMessage(getText("command.syntax.usage",
                    "/welcome set <username> <message...>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;

        String welcomeMessage = String.join(" ",
                java.util.Arrays.copyOfRange(arguments, 2, arguments.length));

        if (!Validator.isValidWelcomeMessage(welcomeMessage)) {
            sender.sendMessage(getText("players.welcome.personal_invalid"));
            return true;
        }

        if (!target.setWelcome(welcomeMessage)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.welcome.personal_updated", target.getIgn()));
        return true;
    }

    /** Clears one player's saved personal welcome. */
    private boolean handleWelcomeReset(Profile sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage(getText("command.syntax.usage", "/welcome reset <username>"));
            return true;
        }

        Profile target = requireKnownUsername(sender, arguments[1]);
        if (target == null) return true;

        if (!target.setWelcome(null)) {
            sender.sendMessage(getText("players.data_update_failed"));
            return true;
        }

        sender.sendMessage(getText("players.welcome.personal_cleared", target.getIgn()));
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

    /** Resolves persistent player IDs to readable last-known account names. */
    private List<String> resolveNames(Collection<Integer> playerIds) {
        List<String> names = new ArrayList<>();

        for (Integer playerId : playerIds) {
            if (playerId == null) continue;

            Profile profile = getOfflinePlayer(playerId);
            names.add(profile == null || profile.getIgn() == null ? "#" + playerId : profile.getIgn());
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void sendNames(Profile sender, String key, List<String> names) {
        String joinedNames = names.isEmpty() ? lang.plain("players.list.none")
                : String.join(", ", names);
        sender.sendMessage(getText(key, joinedNames));
    }

    /** Resolves only registered Minecraft usernames for mutation commands. */
    private Profile requireKnownUsername(Profile sender, String username) {
        Profile target = getOfflinePlayer(username);

        if (target == null) {
            sender.sendMessage(getText("players.player_not_found", username));
        }

        return target;
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

    /** Determines which nickname color syntax the nickname owner may use. */
    private NicknameFormattingLevel nicknameFormattingLevel(Profile target) {
        if (target.meetsMinimumGroup(GroupType.MODERATOR)) return NicknameFormattingLevel.RGB_COLORS;
        if (target.meetsMinimumGroup(GroupType.DONATOR)) return NicknameFormattingLevel.LEGACY_COLORS;
        return NicknameFormattingLevel.PLAIN;
    }

    /**
     * Sends a generated greeting through the sender's real chat pipeline.
     *
     * <p>Each sender may greet an arrival once. Templates are dealt from a
     * shuffled pool without replacement, so a crowd does not repeat the same
     * line until every configured alternative has been used.</p>
     */
    private void sendRandomWelcome(Profile sender, Profile target) {
        if (sender.getId() == target.getId()) {
            sender.sendMessage(getText("players.welcome.cannot_welcome_self"));
            return;
        }

        WelcomeSession session = welcomeSessions.computeIfAbsent(
                target.getId(), _ /*ignored*/ -> new WelcomeSession());

        if (!session.welcomerIds.add(sender.getId())) {
            sender.sendMessage(getText("players.welcome.already_welcomed", target.getIgn()));
            return;
        }

        List<String> messages = lang.list("players.welcome.random_messages",
                MAX_RANDOM_WELCOME_MESSAGES, DEFAULT_RANDOM_WELCOME_MESSAGE)
                .stream().distinct().toList();
        String template = takeWelcomeTemplate(session, messages);

        if (!sender.sendChatMessage(lang.render(template, target.getIgn()))) {
            session.welcomerIds.remove(sender.getId());
            sender.sendMessage(getText("players.welcome.send_failed"));
        }
    }

    /**
     * Selects a template without replacement and avoids repeating the final
     * template from the preceding cycle when another option exists.
     */
    private String takeWelcomeTemplate(WelcomeSession session, List<String> messages) {
        if (session.unusedMessages.isEmpty()) {
            session.unusedMessages.addAll(messages);
            Collections.shuffle(session.unusedMessages);

            int lastIndex = session.unusedMessages.size() - 1;

            if (lastIndex > 0
                    && Objects.equals(session.unusedMessages.get(lastIndex), session.lastMessage)) {
                Collections.swap(session.unusedMessages, lastIndex, 0);
            }
        }

        String selectedMessage = session.unusedMessages.remove(session.unusedMessages.size() - 1);
        session.lastMessage = selectedMessage;
        return selectedMessage;
    }

    /**
     * Session-only anti-spam state for one online welcome target.
     */
    private static final class WelcomeSession {

        private final Set<Integer> welcomerIds = new HashSet<>();
        private final List<String> unusedMessages = new ArrayList<>();
        private String lastMessage;
    }
}
