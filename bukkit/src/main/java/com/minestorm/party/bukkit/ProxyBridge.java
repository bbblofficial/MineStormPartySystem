package com.minestorm.party.bukkit;

import com.minestorm.party.common.Net;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;

/**
 * Sends / receives cross-server party messages through the proxy relay.
 * Wire format: see {@link Net}. Incoming messages are only processed when
 * settings.cross-server is true (otherwise clients could forge them).
 */
public class ProxyBridge implements PluginMessageListener {

    private final MineStormParty plugin;
    private boolean enabled;

    public ProxyBridge(MineStormParty plugin) { this.plugin = plugin; }

    public void enable() {
        try {
            Messenger m = plugin.getServer().getMessenger();
            m.registerOutgoingPluginChannel(plugin, Net.CHANNEL);
            m.registerIncomingPluginChannel(plugin, Net.CHANNEL, this);
            enabled = true;
        } catch (Throwable t) {
            enabled = false;
            plugin.getLogger().warning("Plugin messaging unavailable: " + t.getMessage());
        }
    }

    public boolean isEnabled() { return enabled; }

    // ------------------------------------------------------------------
    // outgoing
    // ------------------------------------------------------------------
    public void sendCreate(Party p) {
        send(Net.P_CREATE, p.getId().toString(), p.getLeader().toString(), p.getLeaderName(),
                String.valueOf(p.getColor()), String.valueOf(p.getCreated()));
    }

    public void sendAdd(Party p, UUID uuid, String name) {
        send(Net.P_ADD, p.getId().toString(), uuid.toString(), name);
    }

    public void sendRemove(Party p, UUID uuid, String name, String reason, String actor) {
        send(Net.P_REMOVE, p.getId().toString(), uuid.toString(), name, reason, actor);
    }

    public void sendLeader(Party p, String oldLeaderName) {
        send(Net.P_LEADER, p.getId().toString(), p.getLeader().toString(), p.getLeaderName(), oldLeaderName);
    }

    public void sendColor(Party p) {
        send(Net.P_COLOR, p.getId().toString(), String.valueOf(p.getColor()));
    }

    public void sendDisband(UUID partyId, String actor) {
        send(Net.P_DISBAND, partyId.toString(), actor);
    }

    public void sendChat(Party p, String sender, String message) {
        send(Net.CHAT, p.getId().toString(), sender, message);
    }

    public void sendNotice(Party p, String key, String name, UUID except) {
        send(Net.NOTICE, p.getId().toString(), key, name, except == null ? "" : except.toString());
    }

    public void sendInviteRequest(Party p, String targetName, long seconds) {
        send(Net.INVITE_REQ, p.getId().toString(), p.getLeader().toString(), p.getLeaderName(),
                targetName, String.valueOf(seconds), String.valueOf(p.getColor()), p.membersCsv());
    }

    public void sendInviteDeny(UUID leader, String denierName) {
        send(Net.INVITE_DENY, leader.toString(), denierName);
    }

    /** Re-broadcasts every local party (used by /mspa sync). Returns the party count. */
    public int syncAll() {
        int n = 0;
        for (Party p : plugin.getPartyManager().all()) {
            sendCreate(p);
            for (UUID u : p.getMembers()) {
                if (!p.isLeader(u)) sendAdd(p, u, p.getMemberName(u));
            }
            n++;
        }
        return n;
    }

    private void send(String type, String... args) {
        if (!enabled) return;
        Player carrier = null;
        for (Player p : Bukkit.getOnlinePlayers()) { carrier = p; break; }
        if (carrier == null) return; // plugin messages need a player connection
        try {
            carrier.sendPluginMessage(plugin, Net.CHANNEL, Net.encode(type, args));
        } catch (Throwable t) {
            plugin.getLogger().fine("Plugin message failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // incoming
    // ------------------------------------------------------------------
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled || !Net.CHANNEL.equals(channel)) return;
        try {
            handle(Net.decode(message));
        } catch (Throwable t) {
            plugin.getLogger().warning("Bad proxy message: " + t);
        }
    }

    private static UUID uuid(String s) { return UUID.fromString(s); }

    private void handle(String[] p) {
        if (p.length == 0) return;
        PartyManager pm = plugin.getPartyManager();
        Messages m = plugin.getMessages();
        String type = p[0];

        if (Net.P_CREATE.equals(type) && p.length >= 6) {
            pm.remoteCreate(uuid(p[1]), uuid(p[2]), p[3], p[4].charAt(0), Long.parseLong(p[5]));

        } else if (Net.P_ADD.equals(type) && p.length >= 4) {
            Party party = pm.get(uuid(p[1]));
            if (party == null) return;
            UUID u = uuid(p[2]);
            if (party.isMember(u)) return;
            pm.remoteAdd(party, u, p[3]);
            plugin.broadcastExcept(party, m.format("invite-accepted", "player", p[3]), u);

        } else if (Net.P_REMOVE.equals(type) && p.length >= 6) {
            Party party = pm.get(uuid(p[1]));
            if (party == null) return;
            UUID u = uuid(p[2]);
            if (!party.isMember(u) || party.isLeader(u)) return;
            if ("kick".equals(p[4])) {
                plugin.broadcast(party, m.format("kick-success", "target", p[3], "player", p[5]));
            } else {
                plugin.broadcastExcept(party, m.format("leave-broadcast", "player", p[3]), u);
            }
            pm.remoteRemove(party, u);

        } else if (Net.P_LEADER.equals(type) && p.length >= 5) {
            Party party = pm.get(uuid(p[1]));
            if (party == null) return;
            plugin.broadcast(party, m.format("transfer-success", "player", p[4], "target", p[3]));
            pm.remoteLeader(party, uuid(p[2]), p[3]);

        } else if (Net.P_COLOR.equals(type) && p.length >= 3) {
            Party party = pm.get(uuid(p[1]));
            if (party != null && !p[2].isEmpty()) pm.remoteColor(party, p[2].charAt(0));

        } else if (Net.P_DISBAND.equals(type) && p.length >= 3) {
            Party party = pm.get(uuid(p[1]));
            if (party == null) return;
            plugin.broadcast(party, m.format("party-disbanded", "player", p[2]));
            pm.remoteDisband(party);

        } else if (Net.CHAT.equals(type) && p.length >= 4) {
            Party party = pm.get(uuid(p[1]));
            if (party != null) plugin.broadcast(party, plugin.formatChat(party, p[2], p[3]));

        } else if (Net.NOTICE.equals(type) && p.length >= 5) {
            Party party = pm.get(uuid(p[1]));
            if (party == null) return;
            boolean join = "join".equals(p[2]);
            String fmt = plugin.getConfig().getString(join ? "formats.member-join" : "formats.member-quit",
                    join ? MineStormParty.DEFAULT_JOIN_FORMAT : MineStormParty.DEFAULT_QUIT_FORMAT);
            UUID except = p[4].isEmpty() ? null : uuid(p[4]);
            plugin.broadcastExcept(party, plugin.formatParty(fmt, party, p[3]), except);

        } else if (Net.INVITE.equals(type) && p.length >= 9) {
            UUID targetId = uuid(p[4]);
            Player target = Bukkit.getPlayer(targetId);
            if (target == null) return;
            if (pm.getParty(targetId) != null) {
                send(Net.INVITE_BUSY, p[2], target.getName());
                return;
            }
            long expires = System.currentTimeMillis() + Long.parseLong(p[6]) * 1000L;
            Invite inv = new Invite(uuid(p[1]), uuid(p[2]), p[3], expires, p[7].charAt(0), p[8]);
            if (pm.hasInvite(targetId, inv.partyId) || pm.isResolved(inv.partyId, target.getName())) return;
            pm.addInvite(targetId, inv);
            plugin.notifyInvite(target, inv);

        } else if (Net.INVITE_DENY.equals(type) && p.length >= 3) {
            pm.cancelInvite(uuid(p[1]), p[2]);
            Player leader = Bukkit.getPlayer(uuid(p[1]));
            if (leader != null) m.send(leader, "invite-denied-to-leader", "player", p[2]);

        } else if (Net.INVITE_FAIL.equals(type) && p.length >= 3) {
            pm.cancelInvite(uuid(p[1]), p[2]);
            Player leader = Bukkit.getPlayer(uuid(p[1]));
            if (leader != null) m.send(leader, "player-offline");

        } else if (Net.INVITE_BUSY.equals(type) && p.length >= 3) {
            pm.cancelInvite(uuid(p[1]), p[2]);
            Player leader = Bukkit.getPlayer(uuid(p[1]));
            if (leader != null) m.send(leader, "player-already-in-party");
        }
    }
}
