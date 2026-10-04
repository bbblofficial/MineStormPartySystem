package com.minestorm.party.bungee;

import com.minestorm.party.common.Net;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.util.UUID;

/**
 * MineStormParty proxy relay (BungeeCord) - stateless.
 * Only trusts messages coming from backend servers; anything a client sends on
 * the channel is dropped, so players cannot forge party packets.
 *
 * Created by Muvixo.
 */
public class MineStormPartyBungee extends Plugin implements Listener {

    @Override
    public void onEnable() {
        getProxy().registerChannel(Net.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("MineStormParty-Bungee enabled. Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(Net.CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!Net.CHANNEL.equals(e.getTag())) return;
        e.setCancelled(true); // never forward our channel to clients or back to the sender

        if (!(e.getSender() instanceof Server)) return; // ignore clients
        Server origin = (Server) e.getSender();
        byte[] data = e.getData();

        String[] parts;
        try { parts = Net.decode(data); } catch (Exception ex) { return; }
        if (parts.length == 0) return;
        String type = parts[0];

        if (Net.isBroadcast(type)) {
            broadcast(data, origin.getInfo());

        } else if (Net.INVITE_REQ.equals(type) && parts.length >= 8) {
            ProxiedPlayer target = getProxy().getPlayer(parts[4]);
            if (target != null && target.getServer() != null) {
                target.getServer().sendData(Net.CHANNEL,
                        Net.toInvite(parts, target.getUniqueId().toString(), target.getName()));
            } else {
                routeToPlayer(parts[2], Net.encode(Net.INVITE_FAIL, parts[2], parts[4]));
            }

        } else if (Net.isPlayerRouted(type) && parts.length >= 2) {
            routeToPlayer(parts[1], data);
        }
    }

    private void broadcast(byte[] data, ServerInfo origin) {
        for (ServerInfo si : getProxy().getServers().values()) {
            if (origin != null && si.getName().equals(origin.getName())) continue;
            si.sendData(Net.CHANNEL, data, false); // false = never queue stale messages
        }
    }

    private void routeToPlayer(String uuid, byte[] data) {
        try {
            ProxiedPlayer pl = getProxy().getPlayer(UUID.fromString(uuid));
            if (pl != null && pl.getServer() != null) pl.getServer().sendData(Net.CHANNEL, data);
        } catch (IllegalArgumentException ignored) { }
    }
}
