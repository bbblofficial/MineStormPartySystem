package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;

/**
 * Keeps this server's party cache in sync with the shared MySQL database.
 *
 * MSP-FIXER v2: the old version replaced the cache from an async thread
 * (data race, and it overwrote fresh local changes with a stale snapshot -
 * parties vanished / came back). Now the timer runs on the main thread, the DB
 * is read asynchronously and the result is merged on the main thread.
 *
 * Two timers (MySQL only):
 *  - database.refresh-interval-seconds       (default 5) party / member refresh
 *  - database.invite-poll-interval-seconds   (default 2) cross-server invite delivery
 */
public class CacheRefresher implements Runnable {

    private final MineStormParty plugin;
    private int partyTask = -1;
    private int inviteTask = -1;

    public CacheRefresher(MineStormParty plugin) { this.plugin = plugin; }

    public void start() {
        final PartyManager pm = plugin.getPartyManager();
        if (!pm.isShared()) return;

        int sec = plugin.getConfig().getInt("database.refresh-interval-seconds", 5);
        if (sec > 0) {
            long ticks = sec * 20L;
            partyTask = Bukkit.getScheduler().runTaskTimer(plugin, this, ticks, ticks).getTaskId();
            plugin.getLogger().info("Party cache refresher started (every " + sec + "s).");
        }

        int isec = plugin.getConfig().getInt("database.invite-poll-interval-seconds", 2);
        if (isec > 0) {
            long ticks = isec * 20L;
            inviteTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { pm.pollInvitesAsync(); }
            }, 20L, ticks).getTaskId();
            plugin.getLogger().info("Invite poller started (every " + isec + "s).");
        }
    }

    public void stop() {
        if (partyTask != -1) Bukkit.getScheduler().cancelTask(partyTask);
        if (inviteTask != -1) Bukkit.getScheduler().cancelTask(inviteTask);
        partyTask = -1;
        inviteTask = -1;
    }

    @Override
    public void run() {
        try {
            plugin.getPartyManager().refreshAsync();
        } catch (Throwable t) {
            plugin.getLogger().warning("Cache refresh failed: " + t.getMessage());
        }
    }
}
