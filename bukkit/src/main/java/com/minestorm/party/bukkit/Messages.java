package com.minestorm.party.bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Loads messages.yml as UTF-8 and falls back to the defaults bundled in the jar. */
public class Messages {

    private final MineStormParty plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private String prefix = "";

    public Messages(MineStormParty plugin) { this.plugin = plugin; }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);

        YamlConfiguration loaded = new YamlConfiguration();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            loaded.load(r);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read messages.yml: " + ex.getMessage());
        }

        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read bundled messages.yml: " + ex.getMessage());
        }

        cfg = loaded;
        prefix = cfg.getString("prefix", "&b&lMineStorm &f&lParty &8» &f");
    }

    public String raw(String key) { return format(key); }

    public String format(String key, Object... kv) {
        String s = cfg.getString(key);
        if (s == null) s = "&c[missing message: " + key + "]";
        s = s.replace("%prefix%", prefix);
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
            }
        }
        return Msg.color(s);
    }

    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(format(key, kv)); }
    public void sendRaw(CommandSender to, String msg) { to.sendMessage(Msg.color(msg)); }
}
