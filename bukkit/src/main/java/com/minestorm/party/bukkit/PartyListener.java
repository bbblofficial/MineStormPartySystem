package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PartyListener implements Listener {

    private final MineStormParty plugin;
    public PartyListener(MineStormParty plugin) { this.plugin = plugin; }

    private boolean suppress() {
        return plugin.getConfig().getBoolean("settings.suppress-public-join-quit", false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Party party = plugin.getPartyManager().getParty(p.getUniqueId());
        if (party == null) return;
        if (!p.getName().equals(party.getMemberName(p.getUniqueId()))) {
            party.setMemberName(p.getUniqueId(), p.getName());
            plugin.getPartyManager().save();
        }
        if (suppress()) e.setJoinMessage(null);
        String fmt = plugin.getConfig().getString("formats.member-join", "&bParty > &f%player% &bjoined the Server!");
        plugin.broadcastExcept(party, Msg.color(fmt.replace("%player%", p.getName())), p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.getChatToggled().remove(p.getUniqueId());
        Party party = plugin.getPartyManager().getParty(p.getUniqueId());
        if (party == null) return;
        if (suppress()) e.setQuitMessage(null);
        String fmt = plugin.getConfig().getString("formats.member-quit", "&bParty > &f%player% &bleft the Server!");
        plugin.broadcastExcept(party, Msg.color(fmt.replace("%player%", p.getName())), p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKick(PlayerKickEvent e) {
        if (suppress() && plugin.getPartyManager().getParty(e.getPlayer().getUniqueId()) != null)
            e.setLeaveMessage(null);
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
