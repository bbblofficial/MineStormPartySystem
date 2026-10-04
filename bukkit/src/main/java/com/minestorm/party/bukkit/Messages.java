package com.minestorm.party.bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Messages {

    private final MineStormParty plugin;
    private FileConfiguration cfg;
    private String prefix = "";

    public Messages(MineStormParty plugin) { this.plugin = plugin; }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration d = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(d);
                cfg.options().copyDefaults(true);
                cfg.save(f);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("messages.yml save failed: " + ex.getMessage());
        }
        prefix = cfg.getString("prefix", "&b&lMineStorm &f&lParty &8» &f");
    }

    public String raw(String key) {
        String s = cfg.getString(key);
        return s == null ? "" : Msg.color(s.replace("%prefix%", prefix));
    }

    public String format(String key, Object... kv) {
        String s = cfg.getString(key, "");
        s = s.replace("%prefix%", prefix);
        if (kv != null) for (int i = 0; i + 1 < kv.length; i += 2)
            s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        return Msg.color(s);
    }

    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(format(key, kv)); }
    public void sendRaw(CommandSender to, String msg) { to.sendMessage(Msg.color(msg)); }
}
