package com.minestorm.party.bukkit;

import com.minestorm.party.common.Net;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;

/**
 * Sends/receives cross-server party messages through the proxy.
 * Message format (UTF strings joined by Net.SEP):
 *   TYPE | k1 | v1 | k2 | v2 | ...
 */
public class ProxyBridge implements PluginMessageListener {

    private final MineStormParty plugin;

    public ProxyBridge(MineStormParty plugin) { this.plugin = plugin; }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Net.CHANNEL.equals(channel)) return;
        try {
            String raw = new String(message, "UTF-8");
            String[] parts = raw.split(Net.SEP, -1);
            if (parts.length == 0) return;
            String type = parts[0];

            if (Net.INVITE.equals(type)) {
                // INVITE|leaderUUID|leaderName|targetUUID|targetName|color|seconds
                UUID targetUUID = UUID.fromString(parts[3]);
                Player target = Bukkit.getPlayer(targetUUID);
                if (target == null) return;
                String leaderName = parts[2];
                String leaderU = parts[1];
                String seconds = parts.length > 6 ? parts[6] : "60";
                plugin.getMessages().send(target, "invite-received",
                        "player", "&b" + leaderName, "seconds", seconds);
                final String acceptCmd = "/party accept " + leaderName;
                plugin.getMessages().sendRaw(target,
                        plugin.getMessages().raw("invite-click").replace("%leader%", leaderName));
                // Persist locally so /party accept works even before the leader tells us
                try {
                    UUID leaderUUID = UUID.fromString(leaderU);
                    plugin.getPartyManager().addInvite(leaderUUID, targetUUID, target.getName(),
                            Long.parseLong(seconds) * 1000L);
                } catch (Exception ignored) {}
            } else if (Net.INVITE_ACCEPT.equals(type)) {
                // INVITE_ACCEPT|leaderUUID|accepterUUID|accepterName
                UUID leaderUUID = UUID.fromString(parts[1]);
                Party p = plugin.getPartyManager().getByLeader(leaderUUID);
                if (p == null) return;
                UUID u = UUID.fromString(parts[2]);
                if (p.isMember(u)) return;
                plugin.getPartyManager().addMember(p, u, parts[3]);
                plugin.getPartyManager().save();
                plugin.broadcast(p, plugin.getMessages().format("invite-accepted", "player", "&b" + parts[3]));
            } else if (Net.INVITE_DENY.equals(type)) {
                UUID leaderUUID = UUID.fromString(parts[1]);
                Party p = plugin.getPartyManager().getByLeader(leaderUUID);
                if (p == null) return;
                plugin.broadcast(p, plugin.getMessages().format("invite-denied-to-leader",
                        "player", "&b" + parts[3]));
            } else if (Net.CHAT.equals(type)) {
                // CHAT|leaderUUID|senderName|message
                UUID leaderUUID = UUID.fromString(parts[1]);
                Party p = plugin.getPartyManager().getByLeader(leaderUUID);
                if (p == null) return;
                String fmt = plugin.getConfig().getString("formats.party-chat",
                        "&bParty > &f%player% &7» &f%message%");
                String out = Msg.color(fmt.replace("%player%", parts[2])
                                          .replace("%leader%", p.getLeaderName()))
                                .replace("%message%", parts[3]);
                plugin.broadcast(p, out);
            } else if (Net.SYNC_ADD.equals(type)) {
                UUID leaderUUID = UUID.fromString(parts[1]);
                UUID u = UUID.fromString(parts[3]);
                Party p = plugin.getPartyManager().getByLeader(leaderUUID);
                if (p != null && !p.isMember(u))
                    plugin.getPartyManager().addMember(p, u, parts[4]);
            } else if (Net.SYNC_REMOVE.equals(type)) {
                UUID leaderUUID = UUID.fromString(parts[1]);
                UUID u = UUID.fromString(parts[3]);
                Party p = plugin.getPartyManager().getByLeader(leaderUUID);
                if (p != null) plugin.getPartyManager().removeMember(p, u);
            } else if (Net.SYNC_DISBAND.equals(type)) {
                UUID leaderUUID = UUID.fromString(parts[1]);
                Party p = plugin.getPartyManager().getByLeader(leaderUUID);
                if (p != null) plugin.getPartyManager().disband(p);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Proxy message parse error: " + t.getMessage());
        }
    }

    public void sendInvite(Party party, Player target, long seconds) {
        send(Net.INVITE,
                party.getLeader().toString(), party.getLeaderName(),
                target.getUniqueId().toString(), target.getName(),
                String.valueOf(party.getColor()), String.valueOf(seconds));
    }
    public void sendAccept(UUID leader, UUID accepter, String accepterName) {
        send(Net.INVITE_ACCEPT, leader.toString(), accepter.toString(), accepterName);
    }
    public void sendDeny(UUID leader, UUID denier, String denierName) {
        send(Net.INVITE_DENY, leader.toString(), denier.toString(), denierName);
    }
    public void sendChat(UUID leader, String senderName, String message) {
        send(Net.CHAT, leader.toString(), senderName, message);
    }

    private void send(String type, String... args) {
        try {
            StringBuilder sb = new StringBuilder(type);
            for (String a : args) { sb.append(Net.SEP).append(a == null ? "" : a); }
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bout);
            out.writeUTF(sb.toString());
            out.close();
            // Send via the first available player (Bungee forwarding).
            Player carrier = null;
            for (Player p : Bukkit.getOnlinePlayers()) { carrier = p; break; }
            if (carrier == null) return;
            carrier.sendPluginMessage(plugin, "minestormparty:main", bout.toByteArray());
        } catch (Throwable ignored) {}
    }
}
