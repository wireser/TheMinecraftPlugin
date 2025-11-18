package modules.economy;

import command.CommandRegistry;
import enums.Currency;
import modules.BaseModule;
import net.kyori.adventure.text.Component;
import playerdata.Profile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Basic money economy module using Currency.MONEY.
 *
 * Commands:
 *  - /balance [player]
 *  - /pay <player> <amount>
 *  - /eco <set|add|take> <player> <amount>   (admin)
 */
public final class Main extends BaseModule {

    public static final Currency ECON_CURRENCY = Currency.MONEY;

    public Main() {
        super("Economy", "1.0.0");
    }

    @Override
    protected void onLoad() {
        // Ensure required config keys exist, apply defaults if missing.
        getConfig().addDefault("config.default_balance", 0.0D);

        getConfig().addDefault("config.currency.symbol", "$");
        getConfig().addDefault("config.currency.decimals", 2);

        getConfig().addDefault("config.pay.min_amount", 0.01D);
        getConfig().addDefault("config.pay.max_amount", 1000000.0D);
        getConfig().addDefault("config.pay.allow_self", false);
        getConfig().addDefault("config.pay.tax.enabled", false);
        getConfig().addDefault("config.pay.tax.percent", 0.0D);

        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    @Override
    protected void registerCommands() {
        // /balance [player]
        addCommand(new CommandRegistry.Builder("balance", this::handleBalanceCommand)
            .aliases("bal", "money")
            .description("Check your balance or another player's balance.")
            .syntax("/balance [player]")
            .permissionNode("tmp.economy.balance")
            .moduleName(getModuleName())
            .build()
        );

        // /pay <player> <amount>
        addCommand(new CommandRegistry.Builder("pay", this::handlePayCommand)
            .description("Send money to another player.")
            .syntax("/pay <player> <amount>")
            .permissionNode("tmp.economy.pay")
            .moduleName(getModuleName())
            .build()
        );

        // /eco <set|add|take> <player> <amount>
        addCommand(new CommandRegistry.Builder("eco", this::handleEcoCommand)
            .description("Admin economy control: set/add/take balances.")
            .syntax("/eco <set|add|take> <player> <amount>")
            .permissionNode("tmp.economy.admin")
            .moduleName(getModuleName())
            .build()
        );
    }

    // =====================================================================
    // /balance
    // =====================================================================

    private void handleBalanceCommand(Profile sender, String label, String[] args) {
        String symbol = getConfig().getString("config.currency.symbol", "$");

        if (args.length == 0) {
            // Own balance
            BigDecimal bal = sender.getBalance(ECON_CURRENCY);
            String formatted = formatAmount(bal);

            Component msg = getText("economy.balance.self", formatted, symbol);
            sender.sendMessage(msg);
            return;
        }

        // /balance <player> – need extra permission
        if (!sender.hasPermission("tmp.economy.balance.other")) {
            sender.sendMessage(getText("economy.no_permission_other"));
            return;
        }

        String targetName = args[0];
        Profile target = getOfflinePlayer(targetName);
        if (target == null) {
            sender.sendMessage(getText("economy.player_not_found", targetName));
            return;
        }

        BigDecimal bal = target.getBalance(ECON_CURRENCY);
        String formatted = formatAmount(bal);

        Component msg = getText("economy.balance.other",
                target.getIgn() != null ? target.getIgn() : targetName,
                formatted,
                symbol
        );
        sender.sendMessage(msg);
    }

    // =====================================================================
    // /pay
    // =====================================================================

    private void handlePayCommand(Profile sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(getText("economy.pay.usage"));
            return;
        }

        String targetName = args[0];
        String amountRaw  = args[1];

        boolean allowSelf = getConfig().getBoolean("config.pay.allow_self", false);

        Profile target = getOfflinePlayer(targetName);
        if (target == null) {
            sender.sendMessage(getText("economy.player_not_found", targetName));
            return;
        }

        if (!allowSelf && target.getUuid() != null && target.getUuid().equals(sender.getUuid())) {
            sender.sendMessage(getText("economy.pay.self"));
            return;
        }

        BigDecimal amount = parseAmount(amountRaw);
        if (amount == null) {
            sender.sendMessage(getText("economy.pay.invalid_amount", amountRaw));
            return;
        }

        double min = getConfig().getDouble("config.pay.min_amount", 0.01D);
        double max = getConfig().getDouble("config.pay.max_amount", 1000000.0D);

        if (amount.doubleValue() < min || amount.doubleValue() > max) {
            sender.sendMessage(getText("economy.pay.out_of_range", formatDouble(min), formatDouble(max)));
            return;
        }

        // Tax
        boolean taxEnabled = getConfig().getBoolean("config.pay.tax.enabled", false);
        double taxPercent  = getConfig().getDouble("config.pay.tax.percent", 0.0D);

        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal net = amount;

        if (taxEnabled && taxPercent > 0.0D) {
            BigDecimal pct = BigDecimal.valueOf(taxPercent).movePointLeft(2); // percent -> fraction
            tax = amount.multiply(pct).setScale(getDecimals(), RoundingMode.HALF_UP);
            net = amount.subtract(tax);
            if (net.compareTo(BigDecimal.ZERO) <= 0) {
                sender.sendMessage(getText("economy.pay.invalid_amount", amountRaw));
                return;
            }
        }

        BigDecimal senderBal = sender.getBalance(ECON_CURRENCY);
        if (senderBal.compareTo(amount) < 0) {
            sender.sendMessage(getText("economy.pay.insufficient"));
            return;
        }

        // Perform transfer
        BigDecimal neg = amount.negate();
        sender.addBalance(ECON_CURRENCY, neg);
        target.addBalance(ECON_CURRENCY, net);

        String symbol = getConfig().getString("config.currency.symbol", "$");
        String amountStr = formatAmount(amount);
        String netStr    = formatAmount(net);

        // Sender msg
        sender.sendMessage(getText("economy.pay.success.sender",
                amountStr, symbol,
                target.getIgn() != null ? target.getIgn() : targetName,
                taxEnabled ? netStr : ""
        ));

        // Receiver msg (if online)
        Profile onlineTarget = getPlayer(target.getUuid());
        if (onlineTarget != null && onlineTarget.getPlayer() != null) {
            onlineTarget.sendMessage(getText("economy.pay.success.receiver",
                    netStr, symbol,
                    sender.getIgn() != null ? sender.getIgn() : "Unknown"
            ));
        }
    }

    // =====================================================================
    // /eco
    // =====================================================================

    private void handleEcoCommand(Profile sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(getText("economy.eco.usage"));
            return;
        }

        String action     = args[0].toLowerCase(Locale.ROOT);
        String targetName = args[1];
        String amountRaw  = args[2];

        Profile target = getOfflinePlayer(targetName);
        if (target == null) {
            sender.sendMessage(getText("economy.player_not_found", targetName));
            return;
        }

        BigDecimal amount = parseAmountAllowZero(amountRaw);
        if (amount == null) {
            sender.sendMessage(getText("economy.pay.invalid_amount", amountRaw));
            return;
        }

        BigDecimal current = target.getBalance(ECON_CURRENCY);
        BigDecimal newBal;

        switch (action) {
            case "set" -> {
                newBal = amount;
                target.setBalance(ECON_CURRENCY, newBal);
                sender.sendMessage(getText("economy.eco.set",
                        target.getIgn() != null ? target.getIgn() : targetName,
                        formatAmount(newBal)
                ));
            }
            case "add" -> {
                newBal = current.add(amount);
                target.setBalance(ECON_CURRENCY, newBal);
                sender.sendMessage(getText("economy.eco.add",
                        formatAmount(amount),
                        target.getIgn() != null ? target.getIgn() : targetName,
                        formatAmount(newBal)
                ));
            }
            case "take" -> {
                newBal = current.subtract(amount);
                if (newBal.compareTo(BigDecimal.ZERO) < 0) {
                    newBal = BigDecimal.ZERO;
                }
                target.setBalance(ECON_CURRENCY, newBal);
                sender.sendMessage(getText("economy.eco.take",
                        formatAmount(amount),
                        target.getIgn() != null ? target.getIgn() : targetName,
                        formatAmount(newBal)
                ));
            }
            default -> {
                sender.sendMessage(getText("economy.eco.usage"));
            }
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private int getDecimals() {
        int dec = getConfig().getInt("config.currency.decimals", 2);
        if (dec < 0) dec = 0;
        if (dec > 8) dec = 8;
        return dec;
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        int decimals = getDecimals();
        amount = amount.setScale(decimals, RoundingMode.DOWN);
        return amount.toPlainString();
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            String normalized = raw.replace(',', '.');
            BigDecimal val = new BigDecimal(normalized);
            if (val.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return val.setScale(getDecimals(), RoundingMode.DOWN);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseAmountAllowZero(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            String normalized = raw.replace(',', '.');
            BigDecimal val = new BigDecimal(normalized);
            if (val.compareTo(BigDecimal.ZERO) < 0) {
                return null;
            }
            return val.setScale(getDecimals(), RoundingMode.DOWN);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDouble(double value) {
        int decimals = getDecimals();
        BigDecimal bd = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.DOWN);
        return bd.toPlainString();
    }

}