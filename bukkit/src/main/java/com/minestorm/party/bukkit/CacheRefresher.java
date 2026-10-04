package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;

/**
 * Reloads the party cache from the shared MySQL database on a timer so
 * changes made on OTHER servers become visible here without a restart.
 *
 * Only active when database.type is mysql and refresh-interval-seconds > 0.
 */
public class CacheRefresher implements Runnable {

    private final MineStormParty plugin;
    private int taskId = -1;

    public CacheRefresher(MineStormParty plugin) { this.plugin = plugin; }

    public void start() {
        if (plugin.getPartyManager().getDatabaseType() != Database.Type.MYSQL) return;
        int sec = plugin.getConfig().getInt("database.refresh-interval-seconds", 5);
        if (sec <= 0) return;
        long ticks = sec * 20L;
        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, this, ticks, ticks)
                .getTaskId();
        plugin.getLogger().info("Party cache refresher started (every " + sec + "s).");
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    @Override
    public void run() {
        try {
            plugin.getPartyManager().reloadFromStorage();
        } catch (Throwable t) {
            plugin.getLogger().warning("Cache refresh failed: " + t.getMessage());
        }
    }
}
