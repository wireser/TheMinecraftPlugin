package modules;

import command.CommandRegistry;
import enums.GroupType;
import playerdata.Profile;
import utils.NumberUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns persistent player balances and their online-session RAM cache.
 *
 * <p>This first skeleton deliberately contains no payment, transfer, fee or
 * bank-location behaviour. Those rules will be implemented only after their
 * command contracts have been agreed.</p>
 */
public final class EconomyModule extends BaseModule {

    private static final String MONEY_TABLE = "player_money";
    private static final String BALANCES_TABLE = "player_balances";

    private static final List<String> MONEY_COLUMNS = List.of(
            "pocket_balance",
            "bank_balance"
    );

    private static final List<String> BALANCE_COLUMNS = List.of(
            "points",
            "merit",
            "server_experience",
            "karma"
    );

    /** Online balance data indexed by the canonical {@code players.id}. */
    private final Map<Integer, PlayerBalances> loadedPlayerBalances =
            new ConcurrentHashMap<>();

    public EconomyModule() {
        super("Economy", "1.0.0");
    }

    @Override
    protected void registerCommands() {
        addCommand(new CommandRegistry.Builder("balance", this::onCommand)
                .description("View your pocket and bank balances.")
                .syntax("/balance")
                .moduleName(getModuleName())
                .minimumGroup(GroupType.PLAYER)
                .build());

        addCommand(new CommandRegistry.Builder("wallet", this::onCommand)
                .description("View the money currently in your wallet.")
                .syntax("/wallet")
                .moduleName(getModuleName())
                .minimumGroup(GroupType.PLAYER)
                .build());

        addCommand(new CommandRegistry.Builder("pay", this::onCommand)
                .description("Pay a nearby player from your wallet.")
                .syntax("/pay <player> <amount>")
                .moduleName(getModuleName())
                .minimumGroup(GroupType.PLAYER)
                .build());

        addCommand(new CommandRegistry.Builder("transfer", this::onCommand)
                .description("Transfer bank money to another player.")
                .syntax("/transfer <player> <amount>")
                .moduleName(getModuleName())
                .minimumGroup(GroupType.PLAYER)
                .build());
    }

    /**
     * Routes registered labels to their future command handlers.
     *
     * <p>The handlers are intentionally empty in this skeleton.</p>
     */
    @Override
    public boolean onCommand(Profile sender, String label, String[] arguments) {
        if (sender == null || label == null) {
            return false;
        }

        return switch (label.toLowerCase(Locale.ROOT)) {
            case "balance" -> handleBalance(sender);
            case "wallet" -> handleWallet(sender);
            case "pay" -> handlePay(sender, arguments);
            case "transfer" -> handleTransfer(sender, arguments);
            default -> false;
        };
    }

    /** Creates missing rows and loads this online player's balances into RAM. */
    @Override
    public void onProfileLoaded(Profile profile) {
        if (profile == null || profile.getId() <= 0) {
            return;
        }

        try {
            ensurePlayerRowsExist(profile.getId());
            loadedPlayerBalances.put(
                    profile.getId(),
                    loadPlayerBalances(profile.getId())
            );
        } catch (SQLException | IllegalStateException exception) {
            loadedPlayerBalances.remove(profile.getId());
            getLogger().severe(
                    "[Economy] Failed to load balances for playerId="
                    + profile.getId()
                    + ": "
                    + exception.getMessage()
            );
        }
    }

    /** Removes session data without deleting persistent balances. */
    @Override
    public void onProfileUnloaded(Profile profile) {
        if (profile != null) {
            loadedPlayerBalances.remove(profile.getId());
        }
    }

    /** Clears all Economy-owned RAM when the module is disabled. */
    @Override
    protected void onDisable() {
        loadedPlayerBalances.clear();
    }

    /**
     * Returns the immutable cached balance snapshot for an online profile.
     */
    public Optional<PlayerBalances> findLoadedBalances(Profile profile) {
        if (profile == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                loadedPlayerBalances.get(profile.getId())
        );
    }

    /**
     * Requires initialized Economy data instead of silently returning zero.
     * A missing cache entry indicates a lifecycle or database failure.
     */
    public PlayerBalances requireLoadedBalances(Profile profile) {
        Objects.requireNonNull(profile, "profile");

        PlayerBalances balances = loadedPlayerBalances.get(profile.getId());
        if (balances == null) {
            throw new IllegalStateException(
                    "Economy data is not loaded for playerId=" + profile.getId()
            );
        }

        return balances;
    }

    public boolean isPlayerDataLoaded(Profile profile) {
        return profile != null
                && loadedPlayerBalances.containsKey(profile.getId());
    }

    private void ensurePlayerRowsExist(int playerId) throws SQLException {
        getDB().insertIfAbsent(
                MONEY_TABLE,
                List.of("player_id"),
                List.of(playerId)
        );

        getDB().insertIfAbsent(
                BALANCES_TABLE,
                List.of("player_id"),
                List.of(playerId)
        );
    }

    private PlayerBalances loadPlayerBalances(int playerId) throws SQLException {
        Map<String, Object> moneyRow = getDB().getRow(
                MONEY_TABLE,
                MONEY_COLUMNS,
                "player_id = ?",
                List.of(playerId)
        );

        Map<String, Object> balancesRow = getDB().getRow(
                BALANCES_TABLE,
                BALANCE_COLUMNS,
                "player_id = ?",
                List.of(playerId)
        );

        if (moneyRow.isEmpty() || balancesRow.isEmpty()) {
            throw new IllegalStateException(
                    "Required balance rows are missing for playerId=" + playerId
            );
        }

        return new PlayerBalances(
                requiredDecimal(moneyRow, "pocket_balance"),
                requiredDecimal(moneyRow, "bank_balance"),
                requiredInt(balancesRow, "points"),
                requiredInt(balancesRow, "merit"),
                requiredLong(balancesRow, "server_experience"),
                requiredInt(balancesRow, "karma")
        );
    }

    private static BigDecimal requiredDecimal(
            Map<String, Object> row,
            String column
    ) {
        Object value = requireColumn(row, column);
        try {
            return value instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw invalidNumber(column, value, exception);
        }
    }

    private static int requiredInt(Map<String, Object> row, String column) {
        Object value = requireColumn(row, column);
        try {
            return value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw invalidNumber(column, value, exception);
        }
    }

    private static long requiredLong(Map<String, Object> row, String column) {
        Object value = requireColumn(row, column);
        try {
            return value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            throw invalidNumber(column, value, exception);
        }
    }

    private static IllegalStateException invalidNumber(
            String column,
            Object value,
            NumberFormatException cause
    ) {
        return new IllegalStateException(
                "Database column " + column + " is not numeric: " + value,
                cause
        );
    }

    private static Object requireColumn(
            Map<String, Object> row,
            String column
    ) {
        Object value = row.get(column);
        if (value == null) {
            throw new IllegalStateException(
                    "Required database column is null or missing: " + column
            );
        }
        return value;
    }

    private boolean handleBalance(Profile sender) {
        PlayerBalances balances = requireLoadedBalances(sender);

        sender.sendMessage(getText(
                "economy.balance",
                NumberUtils.formatMoney(balances.bankBalance())
        ));

        return true;
    }

    private boolean handleWallet(Profile sender) {
        PlayerBalances balances = requireLoadedBalances(sender);

        sender.sendMessage(getText(
                "economy.wallet",
                NumberUtils.formatMoney(balances.pocketBalance())
        ));

        return true;
    }

    private boolean handlePay(Profile sender, String[] arguments) {
        // Nearby wallet payment behaviour will be designed separately.
        return true;
    }

    private boolean handleTransfer(Profile sender, String[] arguments) {
        // Bank-location and transaction behaviour will be designed separately.
        return true;
    }

    /**
     * Immutable RAM snapshot of every value currently owned by Economy.
     */
    public record PlayerBalances(
            BigDecimal pocketBalance,
            BigDecimal bankBalance,
            int points,
            int merit,
            long serverExperience,
            int karma
    ) {
        public PlayerBalances {
            Objects.requireNonNull(pocketBalance, "pocketBalance");
            Objects.requireNonNull(bankBalance, "bankBalance");
        }
    }
}
