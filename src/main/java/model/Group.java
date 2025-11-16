package model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import enums.Perm;
import net.md_5.bungee.api.ChatColor;

public class Group {

    // Data
    public ChatColor color;
    public String name;
    public String prefix;

    // Limits
    public Integer limitClaim;
    public Integer limitWarps;
    public Integer limitShops;

    // Rewards
    public Double rewardCash;
    public String bonus;
    public String bonusCommand;
    public String newCommand;

    public Integer trip;

    // Lists
    // NOTE: we keep this as List<String> for now to avoid breaking other code,
    // but all *new* logic will treat it as a lowercase, de-duplicated list of labels.
    public List<String> commands = new ArrayList<>();
    public List<Perm> flags = new ArrayList<>();

    // Initialization
    public Group(String name, String prefix, ChatColor color, Integer trip,
                 Integer claim, Integer warps, Integer shops,
                 Double cash, String bonus, String newCommand, String bonusCommand) {

        this.name = name;
        this.prefix = prefix;
        this.color = color;

        this.limitClaim = claim;
        this.limitWarps = warps;
        this.limitShops = shops;

        this.rewardCash = cash;
        this.bonus = bonus;
        this.bonusCommand = bonusCommand;
        this.newCommand = newCommand;

        this.trip = trip;
    }

    // ===================== Flags =====================

    public void addFlag(Perm flag) {
        if (flag != null && !flags.contains(flag)) {
            flags.add(flag);
        }
    }

    public void removeFlag(Perm flag) {
        flags.remove(flag);
    }

    public void inheritFlags(Group group) {
        if (group == null) return;
        for (Perm flag : group.flags) {
            if (!flags.contains(flag)) {
                flags.add(flag);
            }
        }
    }

    // ===================== Commands =====================

    private String normalizeCommand(String cmd) {
        return cmd == null ? null : cmd.toLowerCase(Locale.ROOT);
    }

    private void addSingleCommandInternal(String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        String key = normalizeCommand(cmd);

        // Avoid duplicates
        if (!commands.contains(key)) {
            commands.add(key);
        }
    }

    /**
     * Adds a single command label/alias to this group.
     * Stored in lowercase, duplicate-safe.
     */
    public void addCommand(String cmd) {
        addSingleCommandInternal(cmd);
    }

    public void addCommand(String[] cmd) {
        if (cmd == null) return;
        for (String c : cmd) {
            addSingleCommandInternal(c);
        }
    }

    public void addCommand(List<String> cmd) {
        if (cmd == null) return;
        for (String c : cmd) {
            addSingleCommandInternal(c);
        }
    }

    public void addCommand(Set<String> cmd) {
        if (cmd == null) return;
        for (String c : cmd) {
            addSingleCommandInternal(c);
        }
    }

    public void addCommand(Collection<String> cmd) {
        if (cmd == null) return;
        for (String c : cmd) {
            addSingleCommandInternal(c);
        }
    }

    public void addCommand(HashMap<String, String> cmd) {
        if (cmd == null) return;
        for (String value : cmd.values()) {
            addSingleCommandInternal(value);
        }
    }

    /**
     * Inherits commands from another group.
     * Uses the same normalized add logic (lowercase + de-duplication).
     */
    public void inheritCommands(Group group) {
        if (group == null) return;
        for (String cmd : group.commands) {
            addSingleCommandInternal(cmd);
        }
    }

    /**
     * Checks if this group has access to the given command/alias.
     * Case-insensitive.
     */
    public boolean hasCommand(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        String key = normalizeCommand(cmd);
        return commands.contains(key);
    }

    /**
     * Removes a command/alias from this group.
     * Case-insensitive.
     */
    public void removeCommand(String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        String key = normalizeCommand(cmd);
        commands.removeIf(c -> c.equals(key));
    }
    
}