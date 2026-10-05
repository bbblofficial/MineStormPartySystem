package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PartyListener implements Listener {

    private final MineStormParty plugin;

    public PartyListener(MineStormParty plugin) { this.plugin = plugin; }

    /** MSP-FIXER v2: the player may have joined / left a party on another server a moment ago. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        plugin.getPartyManager().syncFromAsyncThread();
    }

    private boolean suppress() {
        return plugin.getConfig().getBoolean("settings.suppress-public-join-quit", false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final Party party = plugin.getPartyManager().getParty(p.getUniqueId());
        if (party == null) return;

        plugin.getPartyManager().syncName(p);
        if (suppress()) e.setJoinMessage(null);

        String fmt = plugin.getConfig().getString("formats.member-join", MineStormParty.DEFAULT_JOIN_FORMAT);
        plugin.broadcastExcept(party, plugin.formatParty(fmt, party, p.getName()), p.getUniqueId());

        // let members on other servers know (delayed: the client must have registered the channel)
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                plugin.getProxyBridge().sendNotice(party, "join", p.getName(), p.getUniqueId());
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        final Player p = e.getPlayer();
        plugin.getChatToggled().remove(p.getUniqueId());
        plugin.getPartyManager().clearInvites(p.getUniqueId());

        final Party party = plugin.getPartyManager().getParty(p.getUniqueId());
        if (party == null) return;
        if (suppress()) e.setQuitMessage(null);

        String fmt = plugin.getConfig().getString("formats.member-quit", MineStormParty.DEFAULT_QUIT_FORMAT);
        plugin.broadcastExcept(party, plugin.formatParty(fmt, party, p.getName()), p.getUniqueId());

        // next tick: the quitting player is no longer a valid message carrier
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                plugin.getProxyBridge().sendNotice(party, "quit", p.getName(), p.getUniqueId());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKick(PlayerKickEvent e) {
        if (suppress() && plugin.getPartyManager().getParty(e.getPlayer().getUniqueId()) != null) {
            e.setLeaveMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        if (!plugin.getChatToggled().contains(p.getUniqueId())) return;
        e.setCancelled(true);
        final String msg = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (plugin.getPartyManager().getParty(p.getUniqueId()) == null) {
                    plugin.getChatToggled().remove(p.getUniqueId());
                    plugin.getMessages().send(p, "chat-no-party");
                } else {
                    plugin.sendPartyChat(p, msg);
                }
            }
        });
    }
}
