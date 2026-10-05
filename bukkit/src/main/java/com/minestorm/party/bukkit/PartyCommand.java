package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PartyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "create", "invite", "accept", "deny", "leave", "disband", "kick", "transfer",
            "list", "info", "chat", "color", "creator", "help");

    private final MineStormParty plugin;
    private final Map<UUID, Long> disbandConfirm = new HashMap<UUID, Long>();

    public PartyCommand(MineStormParty plugin) { this.plugin = plugin; }

    private PartyManager pm() { return plugin.getPartyManager(); }
    private Messages msg() { return plugin.getMessages(); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(msg().raw("player-only")); return true; }
        Player p = (Player) sender;

        if (cmd.getName().equalsIgnoreCase("pc")) { chat(p, args, 0); return true; }
        if (args.length == 0) { help(p); return true; }

        String sub = args[0].toLowerCase();
        if (sub.equals("create")) create(p);
        else if (sub.equals("invite")) invite(p, args);
        else if (sub.equals("accept")) accept(p, args);
        else if (sub.equals("deny")) deny(p, args);
        else if (sub.equals("leave")) leave(p);
        else if (sub.equals("disband")) disband(p);
        else if (sub.equals("kick")) kick(p, args);
        else if (sub.equals("transfer")) transfer(p, args);
        else if (sub.equals("list") || sub.equals("members")) list(p);
        else if (sub.equals("info")) info(p);
        else if (sub.equals("chat") || sub.equals("c")) chat(p, args, 1);
        else if (sub.equals("color") || sub.equals("colour")) color(p, args);
        else if (sub.equals("creator")) p.sendMessage(msg().raw("creator"));
        else if (sub.equals("help")) help(p);
        else msg().send(p, "unknown-command");
        return true;
    }

    private Party need(Player p) {
        Party party = pm().getParty(p.getUniqueId());
        if (party == null) msg().send(p, "not-in-party");
        return party;
    }

    private long ttlMs() { return Math.max(5, plugin.getConfig().getInt("settings.invite-expire-seconds", 60)) * 1000L; }
    private int maxSize() { return Math.max(2, plugin.getConfig().getInt("settings.party-max-size", 8)); }

    private void help(Player p) {
        p.sendMessage(Msg.color("&b&m------------- &f&lMineStorm &b&lParty &b&m-------------"));
        String[][] h = {
            {"create", "Create a party"},
            {"invite <player>", "Invite a player (any server)"},
            {"accept <leader>", "Accept an invite"},
            {"deny <leader>", "Deny an invite"},
            {"leave", "Leave your party"},
            {"disband", "Disband your party (Leader)"},
            {"kick <player>", "Kick a member (Leader)"},
            {"transfer <player>", "Give leadership (Leader)"},
            {"list", "List party members"},
            {"info", "Show party information"},
            {"chat <msg>", "Party chat (no msg = toggle)"},
            {"color <0-f>", "Set party color (Leader)"},
            {"creator", "Show plugin author"}
        };
        for (String[] x : h) p.sendMessage(Msg.color("&b/party " + x[0] + " &7- &f" + x[1]));
        p.sendMessage(Msg.color("&b/pc <message> &7- &fQuick party chat"));
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void create(Player p) {
        if (!p.hasPermission("minestormparty.create")) { msg().send(p, "no-permission"); return; }
        pm().syncNow(); // MSP-FIXER v2
        if (pm().getParty(p.getUniqueId()) != null) { msg().send(p, "already-in-party"); return; }
        pm().create(p);
        msg().send(p, "party-created");
    }

    private void invite(Player p, String[] a) {
        Messages m = msg();
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party invite <player>"); return; }

        pm().syncNow(); // MSP-FIXER v2: look at the shared database, not a stale cache
        Party party = pm().getParty(p.getUniqueId());
        if (party != null && !party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (party == null) {
            if (!plugin.getConfig().getBoolean("settings.auto-create-on-invite", true)) {
                m.send(p, "not-in-party"); return;
            }
            if (!p.hasPermission("minestormparty.create")) { m.send(p, "no-permission"); return; }
        }

        String name = a[1];
        if (name.equalsIgnoreCase(p.getName())) { m.send(p, "cannot-invite-self"); return; }

        Player target = Bukkit.getPlayerExact(name);
        boolean remote = false;
        if (target != null) {
            if (pm().getParty(target.getUniqueId()) != null) { m.send(p, "player-already-in-party"); return; }
        } else if (plugin.getProxyBridge().isEnabled() || pm().isShared()) {
            // the proxy resolves the name; with a shared database the invite is also stored there
            if (pm().isNameInParty(name)) { m.send(p, "player-already-in-party"); return; }
            remote = true;
        } else {
            m.send(p, "player-offline"); return;
        }

        if (party != null) {
            if (party.size() >= maxSize()) { m.send(p, "party-full", "max", maxSize()); return; }
            if (pm().hasSent(party.getId(), name)) { m.send(p, "invite-already-pending"); return; }
        } else {
            party = pm().create(p);
            m.send(p, "party-created");
        }

        pm().markSent(party.getId(), name, ttlMs());
        if (remote) {
            pm().persistInvite(party, name, ttlMs());
            plugin.getProxyBridge().sendInviteRequest(party, name, ttlMs() / 1000L);
            m.send(p, "invite-sent", "target", name);
        } else {
            Invite inv = new Invite(party.getId(), p.getUniqueId(), p.getName(),
                    System.currentTimeMillis() + ttlMs(), party.getColor(), party.membersCsv());
            pm().addInvite(target.getUniqueId(), inv);
            m.send(p, "invite-sent", "target", target.getName());
            plugin.notifyInvite(target, inv);
        }
    }

    private void accept(Player p, String[] a) {
        Messages m = msg();
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party accept <leader>"); return; }
        pm().syncNow(); // MSP-FIXER v2
        if (pm().getParty(p.getUniqueId()) != null) { m.send(p, "already-in-party"); return; }

        Invite inv = pm().findInvite(p.getUniqueId(), a[1]);
        if (inv == null) { m.send(p, "invite-none"); return; }

        Party party = pm().get(inv.partyId);
        if (party == null) {
            if (pm().isShared()) {
                // the party no longer exists in the shared database
                pm().removeInvite(p.getUniqueId(), inv.partyId);
                m.send(p, "party-not-found");
                return;
            }
            party = pm().restoreFromInvite(inv);
        }
        if (party.size() >= maxSize()) {
            pm().removeInvite(p.getUniqueId(), inv.partyId);
            m.send(p, "party-full", "max", maxSize());
            return;
        }

        pm().consumeInvites(p.getUniqueId(), p.getName());
        pm().addMember(party, p.getUniqueId(), p.getName());
        m.send(p, "invite-accepted-self", "leader", party.getLeaderName());
        plugin.broadcastExcept(party, m.format("invite-accepted", "player", p.getName()), p.getUniqueId());
    }

    private void deny(Player p, String[] a) {
        Messages m = msg();
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party deny <leader>"); return; }
        Invite inv = pm().findInvite(p.getUniqueId(), a[1]);
        if (inv == null) { m.send(p, "invite-none"); return; }

        pm().removeInvite(p.getUniqueId(), inv.partyId);
        m.send(p, "invite-denied");

        Player leader = Bukkit.getPlayer(inv.leaderUuid);
        if (leader != null) m.send(leader, "invite-denied-to-leader", "player", p.getName());
        else plugin.getProxyBridge().sendInviteDeny(inv.leaderUuid, p.getName());
    }

    private void leave(Player p) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        if (party.isLeader(p.getUniqueId())) { m.send(p, "leader-cannot-leave"); return; }
        plugin.broadcastExcept(party, m.format("leave-broadcast", "player", p.getName()), p.getUniqueId());
        pm().removeMember(party, p.getUniqueId(), "leave", p.getName());
        m.send(p, "leave-success");
    }

    private void disband(Player p) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }

        long windowMs = Math.max(3, plugin.getConfig().getInt("settings.disband-confirm-seconds", 15)) * 1000L;
        long now = System.currentTimeMillis();
        Long t = disbandConfirm.get(p.getUniqueId());
        if (t == null || now - t > windowMs) {
            disbandConfirm.put(p.getUniqueId(), now);
            m.send(p, "party-disband-confirm", "seconds", windowMs / 1000L);
            return;
        }
        disbandConfirm.remove(p.getUniqueId());
        plugin.broadcast(party, m.format("party-disbanded", "player", p.getName()));
        pm().disband(party, p.getName());
    }

    private void kick(Player p, String[] a) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party kick <player>"); return; }
        UUID t = party.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "kick-self"); return; }

        String tn = party.getMemberName(t);
        plugin.broadcast(party, m.format("kick-success", "target", tn, "player", p.getName()));
        pm().removeMember(party, t, "kick", p.getName());
    }

    private void transfer(Player p, String[] a) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party transfer <player>"); return; }
        UUID t = party.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "transfer-self"); return; }

        String newName = party.getMemberName(t);
        plugin.broadcast(party, m.format("transfer-success", "player", p.getName(), "target", newName));
        pm().setLeader(party, t, newName);
    }

    private void list(Player p) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        p.sendMessage(m.format("list-header", "leader", party.getLeaderName()));
        for (UUID u : party.getMembers()) {
            String role = party.isLeader(u) ? m.raw("role-leader") : m.raw("role-member");
            p.sendMessage(m.format("list-entry", "name", party.getMemberName(u), "role", role));
        }
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void info(Player p) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        int online = 0;
        for (UUID u : party.getMembers()) if (Bukkit.getPlayer(u) != null) online++;
        String role = party.isLeader(p.getUniqueId()) ? m.raw("role-leader") : m.raw("role-member");
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(party.getCreated()));

        p.sendMessage(m.raw("info-header"));
        p.sendMessage(m.format("info-leader", "leader", party.getLeaderName()));
        p.sendMessage(m.format("info-members", "count", party.size(), "max", maxSize(), "online", online));
        p.sendMessage(m.format("info-you", "role", role));
        p.sendMessage(m.format("info-color", "code", party.getColor(), "name", Msg.colorName(party.getColor())));
        p.sendMessage(m.format("info-created", "date", date));
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void chat(Player p, String[] a, int from) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        if (a.length <= from) {
            Set<UUID> t = plugin.getChatToggled();
            if (t.remove(p.getUniqueId())) m.send(p, "chat-mode-off");
            else { t.add(p.getUniqueId()); m.send(p, "chat-mode-on"); }
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) { if (i > from) sb.append(' '); sb.append(a[i]); }
        plugin.sendPartyChat(p, sb.toString());
    }

    private void color(Player p, String[] a) {
        Messages m = msg();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) {
            m.send(p, "color-current", "code", party.getColor(), "name", Msg.colorName(party.getColor()));
            return;
        }
        if (a[1].length() != 1 || !Msg.isColorCode(a[1].charAt(0))) { m.send(p, "color-invalid"); return; }
        char c = Character.toLowerCase(a[1].charAt(0));
        pm().setColor(party, c);
        m.send(p, "color-set", "code", c, "name", Msg.colorName(c));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player) || c.getName().equalsIgnoreCase("pc")) return Collections.emptyList();
        Player p = (Player) s;
        Party party = pm().getParty(p.getUniqueId());

        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2) {
            List<String> pool = new ArrayList<String>();
            String sub = a[0].toLowerCase();
            if (sub.equals("invite")) {
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) pool.add(o.getName());
            } else if (sub.equals("accept") || sub.equals("deny")) {
                pool.addAll(pm().inviteLeaderNames(p.getUniqueId()));
            } else if (sub.equals("kick") || sub.equals("transfer")) {
                if (party != null) for (UUID u : party.getMembers()) pool.add(party.getMemberName(u));
            } else if (sub.equals("color")) {
                pool.addAll(Msg.colorCodes());
            }
            return filter(pool, a[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String start) {
        List<String> out = new ArrayList<String>();
        String s = start.toLowerCase();
        for (String x : src) if (x.toLowerCase().startsWith(s)) out.add(x);
        return out;
    }
}
