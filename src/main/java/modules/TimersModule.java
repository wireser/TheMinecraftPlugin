package modules;

import enums.GroupType;
import playerdata.Profile;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns sparse, real-time player timers stored in {@code player_timers}.
 *
 * <p>A missing row means inactive, a {@code NULL expires_at} means active
 * indefinitely, and a future timestamp means active until that moment. No row
 * is created merely because a profile joins or a timer type is registered.</p>
 *
 * <p>Online timers are cached in RAM and updated together with the database.
 * The timestamp remains authoritative: the shared minute scheduler discovers
 * expirations and invokes registered module callbacks without waiting for the
 * player to trigger another event.</p>
 */
public final class TimersModule extends BaseModule {

    private static final String TIMER_TABLE = "player_timers";
    private static final int MAX_COMMAND_SUGGESTIONS = 20;
    private static final Pattern TIMER_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])");
    private static final DateTimeFormatter ADMIN_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final List<String> TIMER_COLUMNS =
            List.of("timer_key", "started_at", "expires_at");
    private static final List<String> DURATION_SUGGESTIONS =
            List.of("30m", "1h", "1d", "1w");
    private static final Set<String> ACTIONS =
            Set.of("get", "set", "add", "sub", "reset");

    /** Active timers cached only for live online profiles. */
    private final Map<Integer, Map<String, PlayerTimer>> onlineTimers =
            new ConcurrentHashMap<>();

    /** Timer metadata and change callbacks registered by owning modules. */
    private final Map<String, TimerRegistration> registrations =
            new ConcurrentHashMap<>();

    public TimersModule() {
        super("Timers", "1.0.0");
    }

    /** Registers the single administrative timer command. */
    @Override
    protected void registerCommands() {
        addCommand("ticker", command -> command
                .description("Inspect or repair a player's timers.")
                .syntax("/ticker <get|set|add|sub|reset> <player> <timer> [time|infinite]")
                .minimumGroup(GroupType.ADMIN)
                .tabHandler(this::suggestTickerArguments));
    }

    /** Routes the module's only command. */
    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null || !label.equalsIgnoreCase("ticker")) return false;
        return handleTicker(sender, arguments == null ? new String[0] : arguments);
    }

    /** Loads existing rows into RAM; it never creates an empty database row. */
    @Override
    public void onProfileLoaded(Profile profile) {
        if (profile == null || profile.getId() <= 0) return;

        try {
            Map<String, PlayerTimer> loadedTimers = loadActiveTimers(profile);
            onlineTimers.put(profile.getId(), loadedTimers);

            for (PlayerTimer timer : loadedTimers.values()) {
                notifyTimerChanged(profile, null, timer, TimerChangeReason.PROFILE_LOADED);
            }
        } catch (SQLException | IllegalStateException exception) {
            onlineTimers.remove(profile.getId());
            logError("Failed to load timers for playerId=" + profile.getId() + ".", exception);
        }
    }

    /** Discards only the leaving player's RAM cache. */
    @Override
    public void onProfileUnloaded(Profile profile) {
        if (profile != null) onlineTimers.remove(profile.getId());
    }

    /** Clears session caches without deleting persistent timers or registrations. */
    @Override
    protected void onDisable() {
        onlineTimers.clear();
    }

    /** Proactively expires online timers and fires their registered callbacks. */
    @Override
    public void onMinute() {
        LocalDateTime now = now();

        for (Profile profile : profiles().getOnlineProfiles()) {
            Map<String, PlayerTimer> timers = onlineTimers.get(profile.getId());
            if (timers == null || timers.isEmpty()) continue;

            for (PlayerTimer timer : List.copyOf(timers.values())) {
                if (timer.isActiveAt(now)) continue;

                try {
                    removeTimer(profile, timer, TimerChangeReason.EXPIRED);
                } catch (SQLException exception) {
                    logError("Failed to expire timer '" + timer.key()
                            + "' for playerId=" + profile.getId() + ".", exception);
                }
            }
        }
    }

    // =====================================================================
    // Timer type registration
    // =====================================================================

    /**
     * Registers a timer key for discovery and autocomplete without attaching a
     * change callback. Registration stores no player data.
     */
    public void registerTimer(String timerKey, BaseModule owner) {
        registerTimer(timerKey, owner, null);
    }

    /**
     * Registers a timer key and a callback owned by another module.
     *
     * <p>The callback receives active timers when a profile loads, successful
     * mutations, and natural expirations. The owning module can therefore keep
     * derived RAM state such as an effective group override synchronized.</p>
     *
     * @param timerKey stable lowercase key, optionally namespaced with dots
     * @param owner module responsible for the timer's gameplay meaning
     * @param changeHandler optional observer for successful state changes
     * @throws IllegalArgumentException if the key is invalid
     * @throws IllegalStateException if another module owns the same key
     */
    public void registerTimer(String timerKey, BaseModule owner,
            Consumer<TimerChange> changeHandler) {
        Objects.requireNonNull(owner, "owner");
        String normalizedKey = requireTimerKey(timerKey);
        TimerRegistration existing = registrations.get(normalizedKey);

        if (existing != null && existing.owner() != owner) {
            throw new IllegalStateException("Timer '" + normalizedKey
                    + "' is already registered by " + existing.owner().getModuleName() + ".");
        }

        registrations.put(normalizedKey, new TimerRegistration(owner, changeHandler));
    }

    /** Removes timer metadata only when the requesting module owns it. */
    public boolean unregisterTimer(String timerKey, BaseModule owner) {
        if (owner == null) return false;

        String normalizedKey = normalizeTimerKey(timerKey);
        if (normalizedKey == null) return false;

        TimerRegistration registration = registrations.get(normalizedKey);
        return registration != null && registration.owner() == owner
                && registrations.remove(normalizedKey, registration);
    }

    /** @return sorted immutable timer keys currently registered by modules */
    public List<String> getRegisteredTimerKeys() {
        return List.copyOf(new TreeSet<>(registrations.keySet()));
    }

    // =====================================================================
    // Public timer service
    // =====================================================================

    /** Finds one active timer from RAM for online players or SQL for offline players. */
    public Optional<PlayerTimer> findTimer(Profile profile, String timerKey) throws SQLException {
        requirePlayer(profile);
        String normalizedKey = requireTimerKey(timerKey);
        Map<String, PlayerTimer> cachedTimers = onlineTimers.get(profile.getId());
        PlayerTimer timer = cachedTimers != null
                ? cachedTimers.get(normalizedKey)
                : loadStoredTimer(profile.getId(), normalizedKey);

        if (timer == null) return Optional.empty();

        if (!timer.isActiveAt(now())) {
            removeTimer(profile, timer, TimerChangeReason.EXPIRED);
            return Optional.empty();
        }

        if (profile.isCachedOnlineProfile() && cachedTimers == null) {
            onlineTimers.computeIfAbsent(profile.getId(), ignored -> new ConcurrentHashMap<>())
                    .put(normalizedKey, timer);
        }

        return Optional.of(timer);
    }

    /** Checks whether a timer is currently active. */
    public boolean hasTimer(Profile profile, String timerKey) throws SQLException {
        return findTimer(profile, timerKey).isPresent();
    }

    /** Returns every active timer, sorted by key. */
    public List<PlayerTimer> getTimers(Profile profile) throws SQLException {
        requirePlayer(profile);
        Map<String, PlayerTimer> cachedTimers = onlineTimers.get(profile.getId());

        if (cachedTimers == null) {
            Map<String, PlayerTimer> loaded = loadActiveTimers(profile);
            if (profile.isCachedOnlineProfile()) onlineTimers.put(profile.getId(), loaded);
            return sortedTimers(loaded.values());
        }

        for (PlayerTimer timer : List.copyOf(cachedTimers.values())) {
            if (!timer.isActiveAt(now())) removeTimer(profile, timer, TimerChangeReason.EXPIRED);
        }

        return sortedTimers(cachedTimers.values());
    }

    /** Replaces any existing value with a finite timer beginning now. */
    public PlayerTimer setTimer(Profile profile, String timerKey, Duration duration)
            throws SQLException {
        requirePlayer(profile);
        String normalizedKey = requireTimerKey(timerKey);
        long seconds = requireWholePositiveSeconds(duration);
        LocalDateTime startedAt = now();
        LocalDateTime expiresAt = addSeconds(startedAt, seconds);
        PlayerTimer previous = findTimer(profile, normalizedKey).orElse(null);
        PlayerTimer current = new PlayerTimer(profile.getId(), normalizedKey, startedAt, expiresAt);

        saveTimer(current);
        cacheTimer(profile, current);
        notifyTimerChanged(profile, previous, current, TimerChangeReason.SET);
        return current;
    }

    /** Replaces any existing value with an indefinite timer beginning now. */
    public PlayerTimer setIndefiniteTimer(Profile profile, String timerKey) throws SQLException {
        requirePlayer(profile);
        String normalizedKey = requireTimerKey(timerKey);
        PlayerTimer previous = findTimer(profile, normalizedKey).orElse(null);
        PlayerTimer current = new PlayerTimer(profile.getId(), normalizedKey, now(), null);

        saveTimer(current);
        cacheTimer(profile, current);
        notifyTimerChanged(profile, previous, current, TimerChangeReason.SET);
        return current;
    }

    /**
     * Adds time to a finite timer. An inactive timer begins now; an indefinite
     * timer cannot be extended because it has no endpoint.
     */
    public PlayerTimer addTime(Profile profile, String timerKey, Duration duration)
            throws SQLException {
        requirePlayer(profile);
        String normalizedKey = requireTimerKey(timerKey);
        long seconds = requireWholePositiveSeconds(duration);
        PlayerTimer previous = findTimer(profile, normalizedKey).orElse(null);

        if (previous != null && previous.isIndefinite()) {
            throw new IllegalStateException("An indefinite timer cannot be extended.");
        }

        LocalDateTime startedAt = previous != null ? previous.startedAt() : now();
        LocalDateTime base = previous != null ? previous.expiresAt() : startedAt;
        PlayerTimer current = new PlayerTimer(profile.getId(), normalizedKey,
                startedAt, addSeconds(base, seconds));

        saveTimer(current);
        cacheTimer(profile, current);
        notifyTimerChanged(profile, previous, current, TimerChangeReason.ADDED);
        return current;
    }

    /**
     * Removes time from a finite timer. If the new endpoint is not in the
     * future, the row is deleted and an empty result is returned.
     */
    public Optional<PlayerTimer> subtractTime(Profile profile, String timerKey,
            Duration duration) throws SQLException {
        requirePlayer(profile);
        String normalizedKey = requireTimerKey(timerKey);
        long seconds = requireWholePositiveSeconds(duration);
        PlayerTimer previous = findTimer(profile, normalizedKey)
                .orElseThrow(() -> new IllegalStateException("The timer is not active."));

        if (previous.isIndefinite()) {
            throw new IllegalStateException("An indefinite timer has no time to subtract.");
        }

        LocalDateTime expiresAt = previous.expiresAt().minusSeconds(seconds);
        if (!expiresAt.isAfter(now())) {
            removeTimer(profile, previous, TimerChangeReason.SUBTRACTED);
            return Optional.empty();
        }

        PlayerTimer current = new PlayerTimer(profile.getId(), normalizedKey,
                previous.startedAt(), expiresAt);
        saveTimer(current);
        cacheTimer(profile, current);
        notifyTimerChanged(profile, previous, current, TimerChangeReason.SUBTRACTED);
        return Optional.of(current);
    }

    /** Deletes an active timer and notifies its registered owner. */
    public boolean resetTimer(Profile profile, String timerKey) throws SQLException {
        requirePlayer(profile);
        String normalizedKey = requireTimerKey(timerKey);
        PlayerTimer previous = findTimer(profile, normalizedKey).orElse(null);
        if (previous == null) return false;

        removeTimer(profile, previous, TimerChangeReason.RESET);
        return true;
    }

    // =====================================================================
    // Administrative command
    // =====================================================================

    private boolean handleTicker(Profile sender, String[] arguments) {
        if (arguments.length == 0
                || arguments.length == 1 && arguments[0].equalsIgnoreCase("help")) {
            sender.sendMessage(getText("timers.usage"));
            return true;
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            sender.sendMessage(getText("timers.unknown_action", arguments[0]));
            return true;
        }

        boolean needsDuration = action.equals("set") || action.equals("add") || action.equals("sub");
        int expectedArguments = needsDuration ? 4 : 3;
        if (arguments.length != expectedArguments) {
            sender.sendMessage(getText("timers.usage"));
            return true;
        }

        Profile target = getOfflinePlayer(arguments[1]);
        if (target == null) {
            sender.sendMessage(getText("timers.player_not_found", arguments[1]));
            return true;
        }

        String timerKey = normalizeTimerKey(arguments[2]);
        if (timerKey == null) {
            sender.sendMessage(getText("timers.invalid_key", arguments[2]));
            return true;
        }

        try {
            return switch (action) {
                case "get" -> showTimer(sender, target, timerKey);
                case "set" -> setTimerFromCommand(sender, target, timerKey, arguments[3]);
                case "add" -> addTimerFromCommand(sender, target, timerKey, arguments[3]);
                case "sub" -> subtractTimerFromCommand(sender, target, timerKey, arguments[3]);
                case "reset" -> resetTimerFromCommand(sender, target, timerKey);
                default -> true;
            };
        } catch (SQLException exception) {
            logError("Timer command failed for playerId=" + target.getId()
                    + ", timer='" + timerKey + "'.", exception);
            sender.sendMessage(getText("timers.database_error"));
            return true;
        } catch (IllegalArgumentException | DateTimeException exception) {
            sender.sendMessage(getText("timers.invalid_duration", needsDuration ? arguments[3] : ""));
            return true;
        }
    }

    private boolean showTimer(Profile sender, Profile target, String timerKey) throws SQLException {
        PlayerTimer timer = findTimer(target, timerKey).orElse(null);

        if (timer == null) {
            sender.sendMessage(getText("timers.inactive", target.getIgn(), timerKey));
        } else if (timer.isIndefinite()) {
            sender.sendMessage(getText("timers.active_indefinite", target.getIgn(), timerKey));
        } else {
            sender.sendMessage(getText("timers.active_finite", target.getIgn(), timerKey,
                    formatDateTime(timer.expiresAt()), formatDuration(timer.remainingAt(now()))));
        }

        return true;
    }

    private boolean setTimerFromCommand(Profile sender, Profile target, String timerKey,
            String durationInput) throws SQLException {
        if (durationInput.equalsIgnoreCase("infinite")) {
            setIndefiniteTimer(target, timerKey);
            sender.sendMessage(getText("timers.set_indefinite", target.getIgn(), timerKey));
            return true;
        }

        Duration duration = requireDuration(durationInput);
        PlayerTimer timer = setTimer(target, timerKey, duration);
        sender.sendMessage(getText("timers.set_finite", target.getIgn(), timerKey,
                formatDateTime(timer.expiresAt()), formatDuration(duration)));
        return true;
    }

    private boolean addTimerFromCommand(Profile sender, Profile target, String timerKey,
            String durationInput) throws SQLException {
        Duration duration = requireDuration(durationInput);
        PlayerTimer previous = findTimer(target, timerKey).orElse(null);

        if (previous != null && previous.isIndefinite()) {
            sender.sendMessage(getText("timers.indefinite_change_rejected", target.getIgn(), timerKey));
            return true;
        }

        PlayerTimer timer = addTime(target, timerKey, duration);
        sender.sendMessage(getText("timers.added", formatDuration(duration), target.getIgn(),
                timerKey, formatDateTime(timer.expiresAt())));
        return true;
    }

    private boolean subtractTimerFromCommand(Profile sender, Profile target, String timerKey,
            String durationInput) throws SQLException {
        Duration duration = requireDuration(durationInput);
        PlayerTimer previous = findTimer(target, timerKey).orElse(null);

        if (previous == null) {
            sender.sendMessage(getText("timers.inactive", target.getIgn(), timerKey));
            return true;
        }

        if (previous.isIndefinite()) {
            sender.sendMessage(getText("timers.indefinite_change_rejected", target.getIgn(), timerKey));
            return true;
        }

        Optional<PlayerTimer> current = subtractTime(target, timerKey, duration);
        if (current.isEmpty()) {
            sender.sendMessage(getText("timers.ended_by_subtraction", target.getIgn(), timerKey));
        } else {
            sender.sendMessage(getText("timers.subtracted", formatDuration(duration), target.getIgn(),
                    timerKey, formatDateTime(current.get().expiresAt())));
        }

        return true;
    }

    private boolean resetTimerFromCommand(Profile sender, Profile target, String timerKey)
            throws SQLException {
        sender.sendMessage(getText(resetTimer(target, timerKey)
                ? "timers.reset"
                : "timers.inactive", target.getIgn(), timerKey));
        return true;
    }

    /** Suggests actions, registered players, timer keys and common durations. */
    private List<String> suggestTickerArguments(Profile sender, String label, String[] arguments) {
        if (arguments.length == 1) {
            List<String> actions = new ArrayList<>(ACTIONS);
            actions.add("help");
            return actions;
        }

        if (arguments.length == 2 && ACTIONS.contains(arguments[0].toLowerCase(Locale.ROOT))) {
            return profiles().suggestRegisteredUsernames(arguments[1], 0, MAX_COMMAND_SUGGESTIONS);
        }

        if (arguments.length == 3 && ACTIONS.contains(arguments[0].toLowerCase(Locale.ROOT))) {
            Set<String> suggestions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            suggestions.addAll(registrations.keySet());
            Profile target = getOfflinePlayer(arguments[1]);

            if (target != null) {
                try {
                    for (PlayerTimer timer : getTimers(target)) suggestions.add(timer.key());
                } catch (SQLException exception) {
                    logError("Failed to suggest timers for playerId=" + target.getId() + ".", exception);
                }
            }

            return List.copyOf(suggestions);
        }

        if (arguments.length == 4) {
            String action = arguments[0].toLowerCase(Locale.ROOT);
            if (action.equals("set")) {
                List<String> suggestions = new ArrayList<>(DURATION_SUGGESTIONS);
                suggestions.add("infinite");
                return suggestions;
            }
            if (action.equals("add") || action.equals("sub")) return DURATION_SUGGESTIONS;
        }

        return List.of();
    }

    // =====================================================================
    // Persistence and cache internals
    // =====================================================================

    private Map<String, PlayerTimer> loadActiveTimers(Profile profile) throws SQLException {
        Map<String, PlayerTimer> activeTimers = new ConcurrentHashMap<>();

        for (Map<String, Object> row : getDB().getRows(
                TIMER_TABLE, TIMER_COLUMNS, "player_id = ?", List.of(profile.getId()))) {
            PlayerTimer timer = readTimer(profile.getId(), row);

            if (timer.isActiveAt(now())) {
                activeTimers.put(timer.key(), timer);
            } else {
                discardExpiredStoredTimer(timer);
            }
        }

        return activeTimers;
    }

    private PlayerTimer loadStoredTimer(int playerId, String timerKey) throws SQLException {
        Map<String, Object> row = getDB().getRow(TIMER_TABLE, TIMER_COLUMNS,
                "player_id = ? AND timer_key = ?", List.of(playerId, timerKey));
        return row.isEmpty() ? null : readTimer(playerId, row);
    }

    private PlayerTimer readTimer(int playerId, Map<String, Object> row) {
        Object rawKey = row.get("timer_key");
        if (rawKey == null) throw new IllegalStateException("Timer row has no timer_key.");

        String key = requireTimerKey(rawKey.toString());
        LocalDateTime startedAt = readDateTime(row.get("started_at"), "started_at", false);
        LocalDateTime expiresAt = readDateTime(row.get("expires_at"), "expires_at", true);
        return new PlayerTimer(playerId, key, startedAt, expiresAt);
    }

    private void saveTimer(PlayerTimer timer) throws SQLException {
        getDB().upsert(TIMER_TABLE,
                List.of("player_id", "timer_key"), List.of(timer.playerId(), timer.key()),
                List.of("started_at", "expires_at"),
                Arrays.asList(timer.startedAt(), timer.expiresAt()));
    }

    private void removeTimer(Profile profile, PlayerTimer timer, TimerChangeReason reason)
            throws SQLException {
        getDB().delete(TIMER_TABLE, "player_id = ? AND timer_key = ?",
                timer.playerId(), timer.key());

        Map<String, PlayerTimer> cachedTimers = onlineTimers.get(timer.playerId());
        if (cachedTimers != null) cachedTimers.remove(timer.key());
        notifyTimerChanged(profile, timer, null, reason);
    }

    /** Deletes an expiry discovered while loading without sending a late notification. */
    private void discardExpiredStoredTimer(PlayerTimer timer) throws SQLException {
        getDB().delete(TIMER_TABLE, "player_id = ? AND timer_key = ?",
                timer.playerId(), timer.key());
    }

    private void cacheTimer(Profile profile, PlayerTimer timer) {
        if (!profile.isCachedOnlineProfile()) return;

        onlineTimers.computeIfAbsent(profile.getId(), ignored -> new ConcurrentHashMap<>())
                .put(timer.key(), timer);
    }

    private void notifyTimerChanged(Profile profile, PlayerTimer previousTimer,
            PlayerTimer currentTimer, TimerChangeReason reason) {
        String timerKey = currentTimer != null ? currentTimer.key() : previousTimer.key();
        TimerRegistration registration = registrations.get(timerKey);

        if (registration == null || registration.changeHandler() == null) return;
        if (!registration.owner().isEnabled() && !registration.owner().isPaused()) return;

        try {
            registration.changeHandler().accept(
                    new TimerChange(profile, previousTimer, currentTimer, reason));
        } catch (RuntimeException exception) {
            logError("Timer callback failed for '" + timerKey + "' owned by "
                    + registration.owner().getModuleName() + ".", exception);
        }
    }

    private static List<PlayerTimer> sortedTimers(Collection<PlayerTimer> timers) {
        return timers.stream().sorted(Comparator.comparing(PlayerTimer::key)).toList();
    }

    // =====================================================================
    // Validation, parsing and formatting
    // =====================================================================

    /** Returns a normalized timer key, or {@code null} when it is invalid. */
    public static String normalizeTimerKey(String timerKey) {
        if (timerKey == null) return null;

        String normalized = timerKey.trim().toLowerCase(Locale.ROOT);
        return TIMER_KEY.matcher(normalized).matches() ? normalized : null;
    }

    /** Parses combinations such as {@code 16d20h}, {@code 1h30m} or {@code 45s}. */
    public static Optional<Duration> parseDuration(String input) {
        if (input == null || input.isBlank()) return Optional.empty();

        String normalized = input.toLowerCase(Locale.ROOT);
        Matcher matcher = DURATION_PART.matcher(normalized);
        Set<Character> usedUnits = new HashSet<>();
        long totalSeconds = 0;
        int position = 0;

        try {
            while (matcher.find()) {
                if (matcher.start() != position) return Optional.empty();

                long amount = Long.parseLong(matcher.group(1));
                char unit = matcher.group(2).charAt(0);
                if (!usedUnits.add(unit)) return Optional.empty();

                long unitSeconds = switch (unit) {
                    case 's' -> 1L;
                    case 'm' -> 60L;
                    case 'h' -> 3600L;
                    case 'd' -> 86400L;
                    case 'w' -> 604800L;
                    default -> throw new IllegalArgumentException("Unsupported duration unit.");
                };

                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, unitSeconds));
                position = matcher.end();
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }

        return position == normalized.length() && totalSeconds > 0
                ? Optional.of(Duration.ofSeconds(totalSeconds))
                : Optional.empty();
    }

    /** Formats a duration with the same week/day/hour/minute/second units accepted by commands. */
    public static String formatDuration(Duration duration) {
        long remainingSeconds = Math.max(0L, duration == null ? 0L : duration.getSeconds());
        long weeks = remainingSeconds / 604800L;
        remainingSeconds %= 604800L;
        long days = remainingSeconds / 86400L;
        remainingSeconds %= 86400L;
        long hours = remainingSeconds / 3600L;
        remainingSeconds %= 3600L;
        long minutes = remainingSeconds / 60L;
        long seconds = remainingSeconds % 60L;
        List<String> parts = new ArrayList<>();

        if (weeks > 0) parts.add(weeks + "w");
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    private static String requireTimerKey(String timerKey) {
        String normalized = normalizeTimerKey(timerKey);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid timer key: " + timerKey);
        }
        return normalized;
    }

    private static Duration requireDuration(String input) {
        return parseDuration(input).orElseThrow(
                () -> new IllegalArgumentException("Invalid duration: " + input));
    }

    private static long requireWholePositiveSeconds(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.getNano() != 0) {
            throw new IllegalArgumentException("Timer duration must contain positive whole seconds.");
        }
        return duration.getSeconds();
    }

    private static void requirePlayer(Profile profile) {
        if (profile == null || profile.getId() <= 0) {
            throw new IllegalArgumentException("A timer requires a persisted player profile.");
        }
    }

    private static LocalDateTime addSeconds(LocalDateTime dateTime, long seconds) {
        try {
            return dateTime.plusSeconds(seconds);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("Timer duration exceeds the supported date range.", exception);
        }
    }

    private static LocalDateTime readDateTime(Object value, String column, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            throw new IllegalStateException("Timer row has no " + column + ".");
        }
        if (value instanceof LocalDateTime dateTime) return dateTime.withNano(0);
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().withNano(0);

        try {
            return LocalDateTime.parse(value.toString().replace(' ', 'T')).withNano(0);
        } catch (DateTimeException exception) {
            throw new IllegalStateException("Timer column " + column
                    + " is not a valid date-time: " + value, exception);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(ADMIN_DATE_TIME);
    }

    /** Immutable active timer snapshot. A null expiry means indefinite. */
    public record PlayerTimer(int playerId, String key, LocalDateTime startedAt,
            LocalDateTime expiresAt) {

        public PlayerTimer {
            if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive.");
            key = requireTimerKey(key);
            Objects.requireNonNull(startedAt, "startedAt");
        }

        public boolean isIndefinite() {
            return expiresAt == null;
        }

        public boolean isActiveAt(LocalDateTime moment) {
            Objects.requireNonNull(moment, "moment");
            return expiresAt == null || expiresAt.isAfter(moment);
        }

        public Duration remainingAt(LocalDateTime moment) {
            if (expiresAt == null) throw new IllegalStateException("An indefinite timer has no remainder.");
            return Duration.between(moment, expiresAt).isNegative()
                    ? Duration.ZERO
                    : Duration.between(moment, expiresAt);
        }
    }

    /** Why a registered timer changed. */
    public enum TimerChangeReason {
        PROFILE_LOADED,
        SET,
        ADDED,
        SUBTRACTED,
        RESET,
        EXPIRED
    }

    /**
     * Event delivered to the module that registered a timer key. A null
     * {@code currentTimer} means the timer became inactive.
     */
    public record TimerChange(Profile profile, PlayerTimer previousTimer,
            PlayerTimer currentTimer, TimerChangeReason reason) {

        public TimerChange {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(reason, "reason");
            if (previousTimer == null && currentTimer == null) {
                throw new IllegalArgumentException("A timer change requires a previous or current timer.");
            }
        }

        public String timerKey() {
            return currentTimer != null ? currentTimer.key() : previousTimer.key();
        }

        public boolean isActive() {
            return currentTimer != null;
        }
    }

    private record TimerRegistration(BaseModule owner, Consumer<TimerChange> changeHandler) {
    }
}
