package com.minestorm.party.bukkit;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class PartyPlaceholders extends PlaceholderExpansion {

    private final MineStormParty plugin;
    public PartyPlaceholders(MineStormParty plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "minestormparty"; }
    @Override public String getAuthor() { return "Muvixo"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onPlaceholderRequest(Player p, String id) {
        if (p == null) return "";
        Party party = plugin.getPartyManager().getParty(p.getUniqueId());
        String none = plugin.getConfig().getString("placeholders.no-party", "");
        String key = id.toLowerCase();
        if (party == null) {
            if (key.equals("has")) return "false";
            if (key.equals("members") || key.equals("online") || key.equals("size")) return "0";
            return none;
        }
        String col = "&" + party.getColor();
        if (key.equals("has")) return "true";
        if (key.equals("leader")) return party.getLeaderName();
        if (key.equals("leader_colored")) return Msg.color(col + party.getLeaderName());
        if (key.equals("is_leader")) return party.isLeader(p.getUniqueId()) ? "true" : "false";
        if (key.equals("color")) return Msg.color(col);
        if (key.equals("color_code")) return String.valueOf(party.getColor());
        if (key.equals("size") || key.equals("members")) return String.valueOf(party.size());
        if (key.equals("prefix")) return Msg.color(col + "[" + party.getLeaderName() + "'s Party]");
        if (key.equals("online")) {
            int n = 0;
            for (UUID u : party.getMembers()) if (Bukkit.getPlayer(u) != null) n++;
            return String.valueOf(n);
        }
        return null;
    }
}
