package com.minestorm.party.bungee;

import com.minestorm.party.common.Net;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * MineStormParty proxy relay (BungeeCord).
 * Forwards party messages to whichever backend the target is on.
 *
 * Created by Muvixo.
 */
public class MineStormPartyBungee extends Plugin implements Listener {

    @Override
    public void onEnable() {
        ProxyServer.getInstance().registerChannel(Net.CHANNEL);
        ProxyServer.getInstance().registerChannel("BungeeCord");
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        getLogger().info("MineStormParty-Bungee enabled. Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        ProxyServer.getInstance().unregisterChannel(Net.CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!Net.CHANNEL.equals(e.getTag())) return;
        if (!(e.getSender() instanceof Server)) return;

        try {
            String raw = new String(e.getData(), "UTF-8");
            String[] parts = raw.split(Net.SEP, -1);
            if (parts.length == 0) return;
            String type = parts[0];
            String targetUuid = null;
            // message-specific target lookup
            if (Net.INVITE.equals(type) && parts.length > 3) targetUuid = parts[3];
            else if (Net.INVITE_ACCEPT.equals(type) && parts.length > 2) targetUuid = parts[1]; // back to leader
            else if (Net.INVITE_DENY.equals(type) && parts.length > 2) targetUuid = parts[1];
            else if (Net.CHAT.equals(type) && parts.length > 2) {
                // broadcast to all servers
                broadcastToAll(e.getData(), e.getSender());
                e.setCancelled(true);
                return;
            }
            if (targetUuid != null) {
                try {
                    ProxiedPlayer tp = getProxy().getPlayer(java.util.UUID.fromString(targetUuid));
                    if (tp != null && tp.getServer() != null) {
                        tp.getServer().sendData(Net.CHANNEL, e.getData());
                        e.setCancelled(true);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            // otherwise broadcast
            broadcastToAll(e.getData(), e.getSender());
        } catch (Exception ignored) {}
        e.setCancelled(true);
    }

    private void broadcastToAll(byte[] data, Object except) {
        for (Server sv : getProxy().getServers().values().stream()
                .map(net.md_5.bungee.api.config.ServerInfo::getName)
                .map(getProxy()::getServerInfo)
                .filter(si -> si != null)
                .collect(java.util.stream.Collectors.toList()).stream()
                .map(si -> {
                    try { return si; } catch (Exception e) { return null; }
                }).filter(si -> si != null)
                .toArray(net.md_5.bungee.api.config.ServerInfo[]::new)) {
            try { sv.sendData(Net.CHANNEL, data); } catch (Exception ignored) {}
        }
    }
}
