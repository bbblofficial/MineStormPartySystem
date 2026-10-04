package com.minestorm.party.bukkit;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** MineStormParty - Created by Muvixo. */
public class MineStormParty extends JavaPlugin {

    public static final String DEFAULT_CHAT_FORMAT = "&%color%Party > &f%player% &7» &f%message%";
    public static final String DEFAULT_JOIN_FORMAT = "&%color%Party > &f%player% &%color%joined the Server!";
    public static final String DEFAULT_QUIT_FORMAT = "&%color%Party > &f%player% &%color%left the Server!";

    private PartyManager partyManager;
    private Messages messages;
    private ProxyBridge proxyBridge;
    private boolean papi;

    private final Set<UUID> chatToggled = Collections.synchronizedSet(new HashSet<UUID>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();

        partyManager = new PartyManager(this);
        proxyBridge = new ProxyBridge(this);
        partyManager.load();

        if (getConfig().getBoolean("settings.cross-server", true)) {
            proxyBridge.enable();
        }

        PartyCommand partyCmd = new PartyCommand(this);
        AdminCommand adminCmd = new AdminCommand(this);
        bind("party", partyCmd, partyCmd);
        bind("pc", partyCmd, null);
        bind("mspa", adminCmd, adminCmd);

        Bukkit.getPluginManager().registerEvents(new PartyListener(this), this);

        // purge expired invites every 5 seconds
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { partyManager.purgeExpired(); }
        }, 100L, 100L);

        hookPapi();
        getLogger().info("MineStormParty enabled"
                + (proxyBridge.isEnabled() ? " (cross-server)" : " (standalone)")
                + (papi ? " (PAPI hooked)." : "."));
        getLogger().info("Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        if (partyManager != null) partyManager.shutdown();
    }

    private void bind(String name, CommandExecutor ex, TabCompleter tc) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor(ex);
        if (tc != null) c.setTabCompleter(tc);
    }

    private void hookPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try { papi = new PartyPlaceholders(this).register(); }
        catch (Throwable t) { papi = false; }
    }

    public void reloadAll() {
        reloadConfig();
        messages.load();
    }

    public PartyManager getPartyManager() { return partyManager; }
    public Messages getMessages() { return messages; }
    public ProxyBridge getProxyBridge() { return proxyBridge; }
    public boolean hasPapi() { return papi; }
    public Set<UUID> getChatToggled() { return chatToggled; }

    // ------------------------------------------------------------------
    // broadcast helpers (local online members only)
    // ------------------------------------------------------------------
    public void broadcast(Party party, String colored) { broadcastExcept(party, colored, null); }

    public void broadcastExcept(Party party, String colored, UUID except) {
        for (UUID u : party.getMembers()) {
            if (except != null && u.equals(except)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(colored);
        }
    }

    /** Applies %player% %leader% %color% to a template and colorizes it. */
    public String formatParty(String template, Party party, String playerName) {
        return Msg.color(template
                .replace("%color%", String.valueOf(party.getColor()))
                .replace("%player%", playerName)
                .replace("%leader%", party.getLeaderName()));
    }

    public String formatChat(Party party, String senderName, String text) {
        String fmt = getConfig().getString("formats.party-chat", DEFAULT_CHAT_FORMAT);
        return formatParty(fmt, party, senderName).replace("%message%", text);
    }

    public void sendPartyChat(Player sender, String message) {
        Party party = partyManager.getParty(sender.getUniqueId());
        if (party == null) return;
        String text = sender.hasPermission("minestormparty.chat.color") ? Msg.color(message) : message;
        broadcast(party, formatChat(party, sender.getName(), text));
        proxyBridge.sendChat(party, sender.getName(), text);
    }

    // ------------------------------------------------------------------
    // invite notification with clickable [ACCEPT] [DENY]
    // ------------------------------------------------------------------
    public void notifyInvite(Player target, Invite inv) {
        long seconds = Math.max(1L, (inv.expires - System.currentTimeMillis()) / 1000L);
        messages.send(target, "invite-received", "player", inv.leaderName, "seconds", seconds);

        TextComponent accept = new TextComponent(messages.raw("invite-accept-button"));
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/party accept " + inv.leaderName));
        accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{
                new TextComponent(messages.format("invite-hover-accept", "leader", inv.leaderName))}));

        TextComponent deny = new TextComponent(messages.raw("invite-deny-button"));
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/party deny " + inv.leaderName));
        deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{
                new TextComponent(messages.format("invite-hover-deny", "leader", inv.leaderName))}));

        TextComponent line = new TextComponent("");
        line.addExtra(accept);
        line.addExtra(new TextComponent(" "));
        line.addExtra(deny);
        target.spigot().sendMessage(line);
    }
}
