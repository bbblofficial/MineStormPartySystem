package com.minestorm.party.bukkit;

import org.bukkit.ChatColor;

public final class Msg {
    private Msg() {}
    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
    public static String strip(String s) { return ChatColor.stripColor(color(s)); }
}
