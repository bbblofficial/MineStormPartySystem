package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /mspa - admin command.
 * OP players (and the console) bypass all permission checks.
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help", "reload", "save", "sync", "party", "player");
    private static final List<String> PARTY_OPS = Arrays.asList(
            "create", "delete", "color", "list", "info", "addmember", "removemember");

    private final MineStormParty plugin;

    public AdminCommand(MineStormParty plugin) { this.plugin = plugin; }

    /** OP bypass - the main requirement. */
    private boolean has(CommandSender s, String node) {
        return s.isOp() || s.hasPermission(node) || s.hasPermission("minestormparty.admin");
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        Messages m = plugin.getMessages();
        if (args.length == 0) { help(s); return true; }
        String sub = args[0].toLowerCase();

        if (sub.equals("help")) { help(s); return true; }

        if (sub.equals("reload")) {
            if (!has(s, "minestormparty.admin.reload")) { m.send(s, "no-permission"); return true; }
            plugin.reloadAll();
            m.send(s, "admin-reload");
            return true;
        }
        if (sub.equals("save")) {
            if (!has(s, "minestormparty.admin.save")) { m.send(s, "no-permission"); return true; }
            plugin.getPartyManager().saveAll();
            m.send(s, "data-saved");
            return true;
        }
        if (sub.equals("sync")) {
            if (!has(s, "minestormparty.admin.sync")) { m.send(s, "no-permission"); return true; }
            m.send(s, "admin-synced", "count", plugin.getProxyBridge().syncAll());
            return true;
        }
        if (sub.equals("party")) return partySub(s, args);
        if (sub.equals("player")) return playerSub(s, args);

        m.send(s, "unknown-command");
        return true;
    }

    private boolean partySub(CommandSender s, String[] args) {
        Messages m = plugin.getMessages();
        PartyManager pm = plugin.getPartyManager();
        if (args.length < 2) {
            m.send(s, "invalid-usage", "usage", "/mspa party <create|delete|color|list|info|addmember|removemember> ...");
            return true;
        }
        String op = args[1].toLowerCase();

        if (op.equals("create")) {
            if (!has(s, "minestormparty.admin.party.create")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa party create <owner>"); return true; }
            Player owner = Bukkit.getPlayerExact(args[2]);
            if (owner == null) { m.send(s, "player-offline"); return true; }
            if (pm.getParty(owner.getUniqueId()) != null) { m.send(s, "already-in-party"); return true; }
            pm.create(owner);
            m.send(s, "admin-party-created", "target", owner.getName());
            return true;
        }

        if (op.equals("delete")) {
            if (!has(s, "minestormparty.admin.party.delete")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa party delete <leaderName>"); return true; }
            Party target = pm.findByLeaderName(args[2]);
            if (target == null) { m.send(s, "admin-party-not-found", "name", args[2]); return true; }
            plugin.broadcast(target, m.format("party-disbanded", "player", s.getName()));
            pm.disband(target, s.getName());
            m.send(s, "admin-party-deleted", "target", args[2]);
            return true;
        }

        if (op.equals("color")) {
            if (!has(s, "minestormparty.admin.party.color")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/mspa party color <leaderName> <0-f>"); return true; }
            Party target = pm.findByLeaderName(args[2]);
            if (target == null) { m.send(s, "admin-party-not-found", "name", args[2]); return true; }
            if (args[3].length() != 1 || !Msg.isColorCode(args[3].charAt(0))) { m.send(s, "color-invalid"); return true; }
            char c = Character.toLowerCase(args[3].charAt(0));
            pm.setColor(target, c);
            m.send(s, "admin-color-set", "code", c, "name", Msg.colorName(c));
            return true;
        }

        if (op.equals("list")) {
            if (!has(s, "minestormparty.admin.player.info")) { m.send(s, "no-permission"); return true; }
            List<Party> all = pm.all();
            s.sendMessage(Msg.color("&b&m-------- Parties (" + all.size() + ") --------"));
            for (Party p : all) {
                s.sendMessage(Msg.color("&b" + p.getLeaderName() + " &7- &f" + p.size() + " members"));
            }
            return true;
        }

        if (op.equals("info")) {
            if (!has(s, "minestormparty.admin.player.info")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa party info <leaderName>"); return true; }
            Party target = pm.findByLeaderName(args[2]);
            if (target == null) { m.send(s, "admin-party-not-found", "name", args[2]); return true; }
            s.sendMessage(Msg.color("&bLeader: &f" + target.getLeaderName()));
            s.sendMessage(Msg.color("&bColor: &" + target.getColor() + Msg.colorName(target.getColor())));
            for (UUID u : target.getMembers()) s.sendMessage(Msg.color(" &7- &f" + target.getMemberName(u)));
            return true;
        }

        if (op.equals("addmember")) {
            if (!has(s, "minestormparty.admin.party.member.add")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/mspa party addmember <leader> <player>"); return true; }
            Party target = pm.findByLeaderName(args[2]);
            if (target == null) { m.send(s, "admin-party-not-found", "name", args[2]); return true; }
            Player t = Bukkit.getPlayerExact(args[3]);
            if (t == null) { m.send(s, "player-offline"); return true; }
            if (pm.getParty(t.getUniqueId()) != null) { m.send(s, "player-already-in-party"); return true; }
            pm.addMember(target, t.getUniqueId(), t.getName());
            plugin.broadcastExcept(target, m.format("invite-accepted", "player", t.getName()), t.getUniqueId());
            m.send(s, "admin-member-added", "target", t.getName(), "leader", target.getLeaderName());
            return true;
        }

        if (op.equals("removemember")) {
            if (!has(s, "minestormparty.admin.party.member.remove")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/mspa party removemember <leader> <player>"); return true; }
            Party target = pm.findByLeaderName(args[2]);
            if (target == null) { m.send(s, "admin-party-not-found", "name", args[2]); return true; }
            UUID u = target.findMember(args[3]);
            if (u == null) { m.send(s, "target-not-member"); return true; }
            if (target.isLeader(u)) { m.send(s, "admin-cannot-remove-leader"); return true; }
            plugin.broadcast(target, m.format("leave-broadcast", "player", args[3]));
            pm.removeMember(target, u, "admin", s.getName());
            m.send(s, "admin-member-removed", "target", args[3]);
            return true;
        }

        m.send(s, "unknown-command");
        return true;
    }

    private boolean playerSub(CommandSender s, String[] args) {
        Messages m = plugin.getMessages();
        PartyManager pm = plugin.getPartyManager();
        if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa player <info|remove> <player>"); return true; }
        String op = args[1].toLowerCase();
        String name = args[2];

        UUID uuid = null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            uuid = online.getUniqueId();
        } else {
            for (Party p : pm.all()) {
                UUID u = p.findMember(name);
                if (u != null) { uuid = u; break; }
            }
        }
        if (uuid == null) { m.send(s, "player-offline"); return true; }

        if (op.equals("info")) {
            if (!has(s, "minestormparty.admin.player.info")) { m.send(s, "no-permission"); return true; }
            Party p = pm.getParty(uuid);
            if (p == null) { m.send(s, "admin-player-noparty", "player", name); return true; }
            m.send(s, "admin-player-info", "player", name, "leader", p.getLeaderName(), "count", p.size());
            return true;
        }

        if (op.equals("remove")) {
            if (!has(s, "minestormparty.admin.player.remove")) { m.send(s, "no-permission"); return true; }
            Party p = pm.getParty(uuid);
            if (p == null) { m.send(s, "admin-player-noparty", "player", name); return true; }
            if (p.isLeader(uuid)) {
                plugin.broadcast(p, m.format("party-disbanded", "player", s.getName()));
                pm.disband(p, s.getName());
            } else {
                plugin.broadcast(p, m.format("leave-broadcast", "player", name));
                pm.removeMember(p, uuid, "admin", s.getName());
            }
            m.send(s, "admin-player-removed", "player", name);
            return true;
        }

        m.send(s, "unknown-command");
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(plugin.getMessages().raw("admin-header"));
        String[][] lines = {
            {"reload", "Reload config + messages"},
            {"save", "Force save database"},
            {"sync", "Re-broadcast all parties to the network"},
            {"party create <owner>", "Create a party for a player"},
            {"party delete <leader>", "Delete a party"},
            {"party list", "List all parties"},
            {"party info <leader>", "Show a party"},
            {"party color <leader> <0-f>", "Set party color"},
            {"party addmember <leader> <player>", "Add a member"},
            {"party removemember <leader> <player>", "Remove a member"},
            {"player info <player>", "Show a player's party"},
            {"player remove <player>", "Remove a player from their party"}
        };
        for (String[] l : lines) s.sendMessage(Msg.color("&b/mspa " + l[0] + " &7- &f" + l[1]));
        s.sendMessage(Msg.color("&7OP players bypass all permissions. Others need &bminestormparty.admin&7."));
        s.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);

        if (a.length == 2 && a[0].equalsIgnoreCase("party")) return filter(PARTY_OPS, a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("player")) return filter(Arrays.asList("info", "remove"), a[1]);

        if (a.length == 3 && a[0].equalsIgnoreCase("party")) {
            if (a[1].equalsIgnoreCase("create")) return filter(onlineNames(), a[2]);
            List<String> names = new ArrayList<String>();
            for (Party p : plugin.getPartyManager().all()) names.add(p.getLeaderName());
            return filter(names, a[2]);
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("player")) return filter(onlineNames(), a[2]);

        if (a.length == 4 && a[0].equalsIgnoreCase("party")) {
            if (a[1].equalsIgnoreCase("color")) return filter(Msg.colorCodes(), a[3]);
            if (a[1].equalsIgnoreCase("addmember")) return filter(onlineNames(), a[3]);
            if (a[1].equalsIgnoreCase("removemember")) {
                Party p = plugin.getPartyManager().findByLeaderName(a[2]);
                List<String> names = new ArrayList<String>();
                if (p != null) for (UUID u : p.getMembers()) names.add(p.getMemberName(u));
                return filter(names, a[3]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> onlineNames() {
        List<String> ps = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) ps.add(p.getName());
        return ps;
    }

    private List<String> filter(List<String> src, String start) {
        List<String> out = new ArrayList<String>();
        String s = start.toLowerCase();
        for (String x : src) if (x.toLowerCase().startsWith(s)) out.add(x);
        return out;
    }
}
