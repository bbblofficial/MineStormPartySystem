package com.minestorm.party.velocity;

import com.google.inject.Inject;
import com.minestorm.party.common.Net;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * MineStormParty proxy relay (Velocity) - stateless.
 * Only trusts messages coming from backend servers; anything a client sends on
 * the channel is dropped, so players cannot forge party packets.
 *
 * Created by Muvixo.
 */
@Plugin(
        id = "minestormparty",
        name = "MineStormParty",
        version = "1.1.0",
        description = "Cross-server Party System - Proxy Relay",
        authors = {"Muvixo"}
)
public class MineStormPartyVelocity {

    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(Net.CHANNEL);

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MineStormPartyVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("MineStormParty-Velocity enabled. Created by Muvixo.");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        server.getChannelRegistrar().unregister(CHANNEL);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getIdentifier())) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled()); // never forward our channel

        if (!(e.getSource() instanceof ServerConnection)) return; // ignore clients
        ServerConnection origin = (ServerConnection) e.getSource();
        byte[] data = e.getData();

        String[] parts;
        try { parts = Net.decode(data); } catch (Exception ex) { return; }
        if (parts.length == 0) return;
        String type = parts[0];

        if (Net.isBroadcast(type)) {
            String originName = origin.getServerInfo().getName();
            for (RegisteredServer rs : server.getAllServers()) {
                if (rs.getServerInfo().getName().equals(originName)) continue;
                rs.sendPluginMessage(CHANNEL, data);
            }

        } else if (Net.INVITE_REQ.equals(type) && parts.length >= 8) {
            Optional<Player> target = server.getPlayer(parts[4]);
            if (target.isPresent() && target.get().getCurrentServer().isPresent()) {
                target.get().getCurrentServer().get().sendPluginMessage(CHANNEL,
                        Net.toInvite(parts, target.get().getUniqueId().toString(), target.get().getUsername()));
            } else {
                routeToPlayer(parts[2], Net.encode(Net.INVITE_FAIL, parts[2], parts[4]));
            }

        } else if (Net.isPlayerRouted(type) && parts.length >= 2) {
            routeToPlayer(parts[1], data);
        }
    }

    private void routeToPlayer(String uuid, byte[] data) {
        try {
            Optional<Player> pl = server.getPlayer(UUID.fromString(uuid));
            if (pl.isPresent() && pl.get().getCurrentServer().isPresent()) {
                pl.get().getCurrentServer().get().sendPluginMessage(CHANNEL, data);
            }
        } catch (IllegalArgumentException ignored) { }
    }
}
