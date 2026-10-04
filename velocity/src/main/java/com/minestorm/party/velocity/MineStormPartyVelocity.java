package com.minestorm.party.velocity;

import com.google.inject.Inject;
import com.minestorm.party.common.Net;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * MineStormParty proxy relay (Velocity).
 * Forwards party messages to whichever backend the target is on.
 *
 * Created by Muvixo.
 */
@Plugin(
        id = "minestormparty",
        name = "MineStormParty",
        version = "1.0.0",
        description = "Cross-server Party System - Proxy Relay",
        authors = {"Muvixo"}
)
public class MineStormPartyVelocity {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(Net.CHANNEL);

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public MineStormPartyVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
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
        if (!(e.getSource() instanceof ServerConnection)) return;

        try {
            String raw = new String(e.getData(), "UTF-8");
            String[] parts = raw.split(Net.SEP, -1);
            if (parts.length == 0) return;
            String type = parts[0];

            String targetUuid = null;
            if (Net.INVITE.equals(type) && parts.length > 3) targetUuid = parts[3];
            else if (Net.INVITE_ACCEPT.equals(type) && parts.length > 2) targetUuid = parts[1];
            else if (Net.INVITE_DENY.equals(type) && parts.length > 2) targetUuid = parts[1];
            else if (Net.CHAT.equals(type) || Net.SYNC_ADD.equals(type)
                    || Net.SYNC_REMOVE.equals(type) || Net.SYNC_DISBAND.equals(type)) {
                broadcastToAll(e.getData(), e.getSource());
                e.setResult(PluginMessageEvent.ForwardResult.handled());
                return;
            }

            if (targetUuid != null) {
                try {
                    Optional<Player> tp = server.getPlayer(UUID.fromString(targetUuid));
                    if (tp.isPresent() && tp.get().getCurrentServer().isPresent()) {
                        tp.get().getCurrentServer().get().sendPluginMessage(CHANNEL, e.getData());
                        e.setResult(PluginMessageEvent.ForwardResult.handled());
                        return;
                    }
                } catch (Exception ignored) {}
            }
            broadcastToAll(e.getData(), e.getSource());
        } catch (Exception ignored) {}

        e.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    private void broadcastToAll(byte[] data, Object except) {
        for (com.velocitypowered.api.proxy.server.RegisteredServer sv : server.getAllServers()) {
            try { sv.sendPluginMessage(CHANNEL, data); } catch (Exception ignored) {}
        }
    }
}
