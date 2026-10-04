package com.minestorm.party.bukkit;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
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
            "create","invite","accept","deny","leave","disband","kick","transfer",
            "list","chat","color","creator","help");

    private static final String[] CODES = {"0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"};
    private static final String[] NAMES = {"Black","Dark Blue","Dark Green","Dark Aqua","Dark Red","Dark Purple",
            "Gold","Gray","Dark Gray","Blue","Green","Aqua","Red","Light Purple","Yellow","White"};

    private final MineStormParty plugin;
    private final PartyManager pm;
    private final Map<UUID, Long> disbandConfirm = new HashMap<UUID, Long>();

    public PartyCommand(MineStormParty plugin) {
        this.plugin = plugin;
        this.pm = plugin.getPartyManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(plugin.getMessages().raw("player-only")); return true; }
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
        else if (sub.equals("chat") || sub.equals("c")) chat(p, args, 1);
        else if (sub.equals("color") || sub.equals("colour")) color(p, args);
        else if (sub.equals("creator")) p.sendMessage(plugin.getMessages().raw("creator"));
        else if (sub.equals("help")) help(p);
        else plugin.getMessages().send(p, "unknown-command");
        return true;
    }

    private Party need(Player p) {
        Party party = pm.getParty(p.getUniqueId());
        if (party == null) plugin.getMessages().send(p, "not-in-party");
        return party;
    }

    private long ttl() { return plugin.getConfig().getInt("settings.invite-expire-seconds", 60) * 1000L; }
    private int maxSize() { return plugin.getConfig().getInt("settings.party-max-size", 8); }

    private void clickable(Player to, String text, String command, String hover) {
        TextComponent c = new TextComponent(Msg.color(text));
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[]{ new TextComponent(Msg.color(hover)) }));
        to.spigot().sendMessage((BaseComponent) c);
    }

    private void help(Player p) {
        p.sendMessage(Msg.color("&b&m------------- &f&lMineStorm &b&lParty &b&m-------------"));
        String[][] h = {
            {"create", "Create a party"},
            {"invite <player>", "Invite a player"},
            {"accept <player>", "Accept an invite"},
            {"deny <player>", "Deny an invite"},
            {"leave", "Leave your party"},
            {"disband", "Disband your party (Leader)"},
            {"kick <player>", "Kick a member (Leader)"},
            {"transfer <player>", "Give leadership (Leader)"},
            {"list", "List party members"},
            {"chat <msg>", "Party chat (no msg = toggle)"},
            {"color <0-f>", "Set party chat color (Leader)"},
            {"creator", "Show plugin author"}
        };
        for (String[] x : h) p.sendMessage(Msg.color("&b/party " + x[0] + " &7- &f" + x[1]));
        p.sendMessage(Msg.color("&b/pc <message> &7- &fQuick party chat"));
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void create(Player p) {
        Messages m = plugin.getMessages();
        if (!p.hasPermission("minestormparty.create")) { m.send(p, "no-permission"); return; }
        if (pm.getParty(p.getUniqueId()) != null) { m.send(p, "already-in-party"); return; }
        pm.createParty(p);
        pm.save();
        m.send(p, "party-created");
    }

    private void invite(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party invite <player>"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { m.send(p, "player-offline"); return; }
        if (t.equals(p)) { m.send(p, "cannot-invite-self"); return; }
        if (pm.getParty(t.getUniqueId()) != null) { m.send(p, "player-already-in-party"); return; }
        if (party.size() >= maxSize()) { m.send(p, "party-full", "max", maxSize()); return; }
        if (pm.hasInvite(party.getLeader(), t.getUniqueId())) { m.send(p, "invite-already-pending"); return; }

        pm.addInvite(party.getLeader(), t.getUniqueId(), t.getName(), ttl());
        m.send(p, "invite-sent", "target", "&b" + t.getName());

        // deliver on this server
        m.send(t, "invite-received", "player", "&b" + p.getName(), "seconds", ttl() / 1000L);
        clickable(t, m.raw("invite-click").replace("%leader%", p.getName()),
                "/party accept " + p.getName(),
                m.raw("invite-hover").replace("%leader%", p.getName()));

        // cross-server delivery (harmless if target is local; the receiver checks)
        plugin.getProxyBridge().sendInvite(party, t, ttl() / 1000L);
    }

    private void accept(Player p, String[] a) {
        Messages m = plugin.getMessages();
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party accept <leader>"); return; }
        if (pm.getParty(p.getUniqueId()) != null) { m.send(p, "already-in-party"); return; }
        Player leader = Bukkit.getPlayerExact(a[1]);
        UUID leaderUUID = leader != null ? leader.getUniqueId() : null;
        // fall back to scanning stored invites for name match
        if (leaderUUID == null) {
            for (Party pt : pm.all())
                if (pt.getLeaderName().equalsIgnoreCase(a[1])) { leaderUUID = pt.getLeader(); break; }
        }
        if (leaderUUID == null) { m.send(p, "invite-none"); return; }
        if (!pm.hasInvite(leaderUUID, p.getUniqueId())) {
            // Try the leader's party anyway (in case invite was implicit)
            Party pt = pm.getByLeader(leaderUUID);
            if (pt == null) { m.send(p, "invite-none"); return; }
        }
        Party party = pm.getByLeader(leaderUUID);
        if (party == null) { m.send(p, "party-not-found"); return; }
        if (party.size() >= maxSize()) { m.send(p, "party-full", "max", maxSize()); return; }
        pm.addMember(party, p.getUniqueId(), p.getName());
        pm.clearInvite(leaderUUID, p.getUniqueId());
        pm.save();
        m.send(p, "invite-accepted-self", "leader", "&b" + party.getLeaderName());
        plugin.broadcast(party, m.format("invite-accepted", "player", "&b" + p.getName()));
        // relay to other servers so their caches update
        plugin.getProxyBridge().sendAccept(leaderUUID, p.getUniqueId(), p.getName());
    }

    private void deny(Player p, String[] a) {
        Messages m = plugin.getMessages();
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party deny <leader>"); return; }
        Player leader = Bukkit.getPlayerExact(a[1]);
        UUID leaderUUID = null;
        if (leader != null) leaderUUID = leader.getUniqueId();
        else for (Party pt : pm.all())
            if (pt.getLeaderName().equalsIgnoreCase(a[1])) { leaderUUID = pt.getLeader(); break; }
        if (leaderUUID == null) { m.send(p, "invite-none"); return; }
        pm.clearInvite(leaderUUID, p.getUniqueId());
        m.send(p, "invite-denied");
        plugin.getProxyBridge().sendDeny(leaderUUID, p.getUniqueId(), p.getName());
    }

    private void leave(Player p) {
        Messages m = plugin.getMessages();
        Party party = need(p); if (party == null) return;
        if (party.isLeader(p.getUniqueId())) { m.send(p, "leader-cannot-leave"); return; }
        pm.removeMember(party, p.getUniqueId());
        pm.save();
        plugin.getChatToggled().remove(p.getUniqueId());
        m.send(p, "leave-success");
        plugin.broadcast(party, m.format("leave-broadcast", "player", "&b" + p.getName()));
    }

    private void disband(Player p) {
        Messages m = plugin.getMessages();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        long now = System.currentTimeMillis();
        Long t = disbandConfirm.get(p.getUniqueId());
        if (t == null || now - t > 15000L) {
            disbandConfirm.put(p.getUniqueId(), now);
            m.send(p, "party-disband-confirm");
            return;
        }
        disbandConfirm.remove(p.getUniqueId());
        plugin.broadcast(party, m.format("party-disbanded", "player", "&b" + p.getName()));
        for (UUID u : party.getMembers()) plugin.getChatToggled().remove(u);
        pm.disband(party);
        pm.save();
    }

    private void kick(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party kick <player>"); return; }
        UUID t = party.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "kick-self"); return; }
        String tn = party.getMemberName(t);
        plugin.broadcast(party, m.format("kick-success", "target", "&b" + tn, "player", "&b" + p.getName()));
        pm.removeMember(party, t);
        pm.save();
        plugin.getChatToggled().remove(t);
    }

    private void transfer(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/party transfer <player>"); return; }
        UUID t = party.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "transfer-self"); return; }
        String oldName = p.getName();
        plugin.broadcast(party, m.format("transfer-success", "player", "&b" + oldName,
                "target", "&b" + party.getMemberName(t)));
        Player online = Bukkit.getPlayer(t);
        if (online != null) pm.updateLeader(party, online);
        pm.save();
    }

    private void list(Player p) {
        Party party = need(p); if (party == null) return;
        p.sendMessage(Msg.color(plugin.getMessages().raw("list-header")
                .replace("%leader%", party.getLeaderName())));
        for (UUID u : party.getMembers()) {
            String role = party.isLeader(u) ? "&bLeader" : "&fMember";
            p.sendMessage(Msg.color(plugin.getMessages().raw("list-entry")
                    .replace("%name%", party.getMemberName(u))
                    .replace("%role%", role)));
        }
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void chat(Player p, String[] a, int from) {
        Messages m = plugin.getMessages();
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
        Messages m = plugin.getMessages();
        Party party = need(p); if (party == null) return;
        if (!party.isLeader(p.getUniqueId())) { m.send(p, "only-leader"); return; }
        if (a.length < 2) {
            m.send(p, "color-current", "code", "&" + party.getColor(),
                    "name", nameOf(party.getColor()));
            return;
        }
        char c = a[1].toLowerCase().charAt(0);
        for (String x : CODES) if (x.equals(String.valueOf(c))) {
            party.setColor(c);
            pm.save();
            m.send(p, "color-set", "code", "&" + c, "name", nameOf(c));
            return;
        }
        m.send(p, "invalid-usage", "usage", "/party color <0-9a-f>");
    }

    private String nameOf(char c) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(String.valueOf(c))) return NAMES[i];
        return "Unknown";
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player) || c.getName().equalsIgnoreCase("pc")) return Collections.emptyList();
        Player p = (Player) s;
        Party party = pm.getParty(p.getUniqueId());
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2) {
            List<String> pool = new ArrayList<String>();
            String sub = a[0].toLowerCase();
            if (sub.equals("invite") || sub.equals("accept") || sub.equals("deny")) {
                for (Player o : Bukkit.getOnlinePlayers()) pool.add(o.getName());
                for (Party pt : pm.all()) pool.add(pt.getLeaderName());
            } else if (sub.equals("kick") || sub.equals("transfer")) {
                if (party != null) for (UUID u : party.getMembers()) pool.add(party.getMemberName(u));
            } else if (sub.equals("color")) {
                pool.addAll(Arrays.asList(CODES));
            }
            return filter(pool, a[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String start) {
        List<String> out = new ArrayList<String>();
        for (String x : src) if (x.toLowerCase().startsWith(start.toLowerCase())) out.add(x);
        return out;
    }
}
