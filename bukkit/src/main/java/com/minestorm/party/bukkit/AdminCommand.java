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
 * /mspa — admin command.
 * OP players bypass all permission checks.
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help","reload","save","party","player");

    private final MineStormParty plugin;
    public AdminCommand(MineStormParty plugin) { this.plugin = plugin; }

    /** OP bypass — the main requirement. */
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
            plugin.reloadConfig();
            m.load();
            m.send(s, "admin-reload");
            return true;
        }
        if (sub.equals("save")) {
            if (!has(s, "minestormparty.admin.save")) { m.send(s, "no-permission"); return true; }
            plugin.getPartyManager().save();
            m.send(s, "data-saved");
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
        if (args.length < 2) { m.send(s, "invalid-usage", "usage", "/mspa party <create|delete|color|list|info|addmember|removemember> ..."); return true; }
        String op = args[1].toLowerCase();

        if (op.equals("create")) {
            if (!has(s, "minestormparty.admin.party.create")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa party create <owner>"); return true; }
            Player owner = Bukkit.getPlayerExact(args[2]);
            if (owner == null) { m.send(s, "player-offline"); return true; }
            if (pm.getParty(owner.getUniqueId()) != null) { m.send(s, "already-in-party"); return true; }
            pm.createParty(owner);
            pm.save();
            m.send(s, "admin-guild-created", "target", "&b" + owner.getName());
            return true;
        }

        if (op.equals("delete")) {
            if (!has(s, "minestormparty.admin.party.delete")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa party delete <leaderName>"); return true; }
            Party target = findByName(args[2]);
            if (target == null) { m.send(s, "admin-guild-not-found", "name", args[2]); return true; }
            plugin.broadcast(target, m.format("party-disbanded", "player", "&b" + s.getName()));
            pm.disband(target);
            pm.save();
            m.send(s, "admin-guild-deleted", "target", "&b" + args[2]);
            return true;
        }

        if (op.equals("color")) {
            if (!has(s, "minestormparty.admin.party.color")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/mspa party color <leaderName> <0-9a-f>"); return true; }
            Party target = findByName(args[2]);
            if (target == null) { m.send(s, "admin-guild-not-found", "name", args[2]); return true; }
            char c = args[3].toLowerCase().charAt(0);
            target.setColor(c);
            pm.save();
            m.send(s, "admin-color-set", "code", "&" + c, "name", String.valueOf(c));
            return true;
        }

        if (op.equals("list")) {
            if (!has(s, "minestormparty.admin.player.info")) { m.send(s, "no-permission"); return true; }
            s.sendMessage(Msg.color("&b&m-------- Parties (" + pm.all().size() + ") --------"));
            for (Party p : pm.all())
                s.sendMessage(Msg.color("&b" + p.getLeaderName() + " &7- &f" + p.size() + " members"));
            return true;
        }

        if (op.equals("info")) {
            if (!has(s, "minestormparty.admin.player.info")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa party info <leaderName>"); return true; }
            Party target = findByName(args[2]);
            if (target == null) { m.send(s, "admin-guild-not-found", "name", args[2]); return true; }
            s.sendMessage(Msg.color("&bLeader: &f" + target.getLeaderName()));
            s.sendMessage(Msg.color("&bColor: &" + target.getColor()));
            for (UUID u : target.getMembers())
                s.sendMessage(Msg.color(" &7- &f" + target.getMemberName(u)));
            return true;
        }

        if (op.equals("addmember")) {
            if (!has(s, "minestormparty.admin.party.member.add")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/mspa party addmember <leader> <player>"); return true; }
            Party target = findByName(args[2]);
            if (target == null) { m.send(s, "admin-guild-not-found", "name", args[2]); return true; }
            Player t = Bukkit.getPlayerExact(args[3]);
            if (t == null) { m.send(s, "player-offline"); return true; }
            if (pm.getParty(t.getUniqueId()) != null) { m.send(s, "player-already-in-party"); return true; }
            pm.addMember(target, t.getUniqueId(), t.getName());
            pm.save();
            m.send(s, "admin-member-added", "target", "&b" + t.getName(), "leader", "&b" + target.getLeaderName());
            return true;
        }

        if (op.equals("removemember")) {
            if (!has(s, "minestormparty.admin.party.member.remove")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/mspa party removemember <leader> <player>"); return true; }
            Party target = findByName(args[2]);
            if (target == null) { m.send(s, "admin-guild-not-found", "name", args[2]); return true; }
            UUID u = target.findMember(args[3]);
            if (u == null) { m.send(s, "target-not-member"); return true; }
            pm.removeMember(target, u);
            pm.save();
            m.send(s, "admin-member-removed", "target", "&b" + args[3]);
            return true;
        }

        m.send(s, "unknown-command");
        return true;
    }

    private Party findByName(String name) {
        for (Party p : plugin.getPartyManager().all())
            if (p.getLeaderName().equalsIgnoreCase(name)) return p;
        return null;
    }

    private boolean playerSub(CommandSender s, String[] args) {
        Messages m = plugin.getMessages();
        PartyManager pm = plugin.getPartyManager();
        if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/mspa player <info|remove> <player>"); return true; }
        String op = args[1].toLowerCase();
        Player t = Bukkit.getPlayerExact(args[2]);
        UUID uuid = null;
        String name = args[2];
        if (t != null) uuid = t.getUniqueId();
        else for (Party p : pm.all()) {
            UUID u = p.findMember(name); if (u != null) { uuid = u; break; }
        }
        if (uuid == null) { m.send(s, "player-offline"); return true; }

        if (op.equals("info")) {
            if (!has(s, "minestormparty.admin.player.info")) { m.send(s, "no-permission"); return true; }
            Party p = pm.getParty(uuid);
            if (p == null) { m.send(s, "admin-player-noguild", "player", name); return true; }
            m.send(s, "admin-player-info", "player", name, "leader", "&b" + p.getLeaderName(),
                    "count", p.size());
            return true;
        }
        if (op.equals("remove")) {
            if (!has(s, "minestormparty.admin.player.remove")) { m.send(s, "no-permission"); return true; }
            Party p = pm.getParty(uuid);
            if (p == null) { m.send(s, "admin-player-noguild", "player", name); return true; }
            if (p.isLeader(uuid)) { pm.disband(p); }
            else { pm.removeMember(p, uuid); }
            pm.save();
            m.send(s, "admin-player-removed", "player", name);
            return true;
        }
        m.send(s, "unknown-command");
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(Msg.color(plugin.getMessages().raw("admin-header")));
        String[][] lines = {
            {"reload", "Reload config + messages"},
            {"save", "Force save database"},
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
        for (String[] l : lines)
            s.sendMessage(Msg.color("&b/mspa " + l[0] + " &7- &f" + l[1]));
        s.sendMessage(Msg.color("&7OP players bypass all permissions. Others need &bminestormparty.admin&7."));
        s.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("party"))
            return filter(Arrays.asList("create","delete","color","list","info","addmember","removemember"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("player"))
            return filter(Arrays.asList("info","remove"), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("party")) {
            List<String> names = new ArrayList<String>();
            for (Party p : plugin.getPartyManager().all()) names.add(p.getLeaderName());
            return filter(names, a[2]);
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("player")) {
            List<String> ps = new ArrayList<String>();
            for (Player p : Bukkit.getOnlinePlayers()) ps.add(p.getName());
            return filter(ps, a[2]);
        }
        if (a.length == 4 && a[0].equalsIgnoreCase("party")) {
            if (a[1].equalsIgnoreCase("color"))
                return filter(Arrays.asList("0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"), a[3]);
            List<String> ps = new ArrayList<String>();
            for (Player p : Bukkit.getOnlinePlayers()) ps.add(p.getName());
            return filter(ps, a[3]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String start) {
        List<String> out = new ArrayList<String>();
        for (String x : src) if (x.toLowerCase().startsWith(start.toLowerCase())) out.add(x);
        return out;
    }
}
