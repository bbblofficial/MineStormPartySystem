package com.minestorm.party.bukkit;

import com.minestorm.party.common.Net;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MineStormParty extends JavaPlugin {

    private PartyManager partyManager;
    private Messages messages;
    private ProxyBridge proxyBridge;
    private boolean papi;

    private final Set<UUID> chatToggled =
            Collections.synchronizedSet(new HashSet<UUID>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();

        partyManager = new PartyManager(this);
        partyManager.load();

        proxyBridge = new ProxyBridge(this);
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "minestormparty:main");
            getServer().getMessenger().registerIncomingPluginChannel(this, "minestormparty:main", proxyBridge);
        } catch (Throwable t) {
            getLogger().warning("Plugin messaging unavailable: " + t.getMessage());
        }

        PartyCommand cmd = new PartyCommand(this);
        AdminCommand admin = new AdminCommand(this);

        getCommand("party").setExecutor(cmd);
        getCommand("party").setTabCompleter(cmd);
        getCommand("pc").setExecutor(cmd);
        getCommand("mspa").setExecutor(admin);
        getCommand("mspa").setTabCompleter(admin);

        Bukkit.getPluginManager().registerEvents(new PartyListener(this), (Plugin) this);

        hookPapi();
        for (Player p : Bukkit.getOnlinePlayers()) checkPendingInvites(p);

        getLogger().info("MineStormParty enabled" + (papi ? " (PAPI hooked)." : "."));
        getLogger().info("Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        if (partyManager != null) partyManager.save();
    }

    private void hookPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try { papi = new PartyPlaceholders(this).register(); }
        catch (Throwable t) { papi = false; }
    }

    /** Called when a player joins, or receives a proxy-delivered invite. */
    public void checkPendingInvites(Player p) {
        // Invites are stored on the server where the leader lives; invites to
        // cross-server players are delivered via ProxyBridge.onPluginMessage.
    }

    public PartyManager getPartyManager() { return partyManager; }
    public Messages getMessages() { return messages; }
    public ProxyBridge getProxyBridge() { return proxyBridge; }
    public boolean hasPapi() { return papi; }
    public Set<UUID> getChatToggled() { return chatToggled; }

    public void broadcast(Party party, String colored) {
        for (UUID u : party.getMembers()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(colored);
        }
    }

    public void broadcastExcept(Party party, String colored, UUID except) {
        for (UUID u : party.getMembers()) {
            if (u.equals(except)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(colored);
        }
    }

    public void sendPartyChat(Player sender, String message) {
        Party party = partyManager.getParty(sender.getUniqueId());
        if (party == null) return;
        String fmt = getConfig().getString("formats.party-chat",
                "&bParty > &f%player% &7» &f%message%");
        String msg = sender.hasPermission("minestormparty.chat.color")
                ? Msg.color(message) : message;
        String out = Msg.color(fmt.replace("%player%", sender.getName())
                                  .replace("%leader%", party.getLeaderName()))
                        .replace("%message%", msg);
        broadcast(party, out);
        // cross-server relay
        proxyBridge.sendChat(party.getLeader(), sender.getName(), msg);
    }
}
