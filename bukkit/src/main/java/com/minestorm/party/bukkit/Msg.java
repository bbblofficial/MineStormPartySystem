package com.minestorm.party.bukkit;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public final class Msg {

    private static final String CODES = "0123456789abcdef";
    private static final String[] NAMES = {
            "Black", "Dark Blue", "Dark Green", "Dark Aqua", "Dark Red", "Dark Purple",
            "Gold", "Gray", "Dark Gray", "Blue", "Green", "Aqua", "Red", "Light Purple",
            "Yellow", "White"};

    private Msg() {}

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public static boolean isColorCode(char c) {
        return CODES.indexOf(Character.toLowerCase(c)) >= 0;
    }

    public static String colorName(char c) {
        int i = CODES.indexOf(Character.toLowerCase(c));
        return i < 0 ? "Unknown" : NAMES[i];
    }

    public static List<String> colorCodes() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < CODES.length(); i++) out.add(String.valueOf(CODES.charAt(i)));
        return out;
    }
}
