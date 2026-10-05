package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * In-memory party state + async persistence (SQLite or MySQL).
 * Every mutation must happen on the server thread.
 *
 * Methods without "remote" apply the change locally AND broadcast it to the network.
 * Methods starting with "remote" apply a change received from another server (no re-broadcast).
 *
 * MSP-FIXER v2 - what changed and why:
 *  1. Shared MySQL: remote* handlers no longer write to the database. The server that made the
 *     change already wrote it; N servers re-writing (possibly older) data caused reverts.
 *  2. The periodic refresh MERGES the database snapshot into the cache on the main thread and
 *     never overwrites entities that were touched locally a moment ago (no more vanishing /
 *     re-appearing parties and members).
 *  3. Pending invites are stored in msp_invites, so they cross servers even without the proxy.
 *  4. Deletes are conditional (uuid AND party_id) so a late delete can't remove a newer membership.
 *  5. /mspa save in shared mode only flushes the write queue instead of pushing a stale cache.
 *  6. Restoring a party from an invite snapshot is only used for per-server (SQLite) storage.
 */
public class PartyManager {

    /** Entities touched less than this long before a snapshot read started are not overwritten. */
    private static final long GRACE_NANOS = 3000L * 1000000L;
    private static final long KEEP_NANOS = 60000L * 1000000L;

    private final MineStormParty plugin;
    private final Database db;

    // readable from any thread (PlaceholderAPI), mutated on the main thread only
    private final Map<UUID, Party> parties = new ConcurrentHashMap<UUID, Party>();     // partyId -> party
    private final Map<UUID, UUID> playerParty = new ConcurrentHashMap<UUID, UUID>();   // player  -> partyId

    // main thread only
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<UUID, Map<UUID, Invite>>();
    private final Map<String, Long> sentInvites = new HashMap<String, Long>();      // partyId:name -> expires
    private final Map<String, Long> resolvedInvites = new HashMap<String, Long>();  // partyId:name -> until
    private final Map<UUID, Long> touchedParties = new HashMap<UUID, Long>();       // id -> nanoTime
    private final Map<UUID, Long> touchedPlayers = new HashMap<UUID, Long>();

    private volatile long lastSyncNanos = System.nanoTime() - 60000000000L;
    private volatile long backoffUntilNanos = System.nanoTime() - 1L;
    private volatile long lastWarnMillis = 0L;

    public PartyManager(MineStormParty plugin) {
        this.plugin = plugin;
        this.db = new Database(plugin);
    }

    public Database.Type getDatabaseType() { return db.getType(); }

    /** true when all servers share one database (MySQL). */
    public boolean isShared() { return db.getType() == Database.Type.MYSQL; }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------
    public Party getParty(UUID player) {
        if (player == null) return null;
        UUID id = playerParty.get(player);
        return id == null ? null : parties.get(id);
    }

    public Party get(UUID partyId) { return partyId == null ? null : parties.get(partyId); }

    public Party findByLeaderName(String name) {
        for (Party p : parties.values()) if (p.getLeaderName().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** true if a player with this name is a member of any known party. */
    public boolean isNameInParty(String name) {
        for (Party p : parties.values()) if (p.findMember(name) != null) return true;
        return false;
    }

    public List<Party> all() {
        List<Party> out = new ArrayList<Party>(parties.values());
        Collections.sort(out, new Comparator<Party>() {
            @Override public int compare(Party a, Party b) {
                return a.getCreated() < b.getCreated() ? -1 : (a.getCreated() == b.getCreated() ? 0 : 1);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------
    // touch bookkeeping (main thread)
    // ------------------------------------------------------------------
    private void touchParty(UUID id) { if (id != null) touchedParties.put(id, System.nanoTime()); }
    private void touchPlayer(UUID id) { if (id != null) touchedPlayers.put(id, System.nanoTime()); }

    private static boolean recent(Map<UUID, Long> m, UUID id, long protectAfter) {
        Long t = m.get(id);
        return t != null && t - protectAfter >= 0L;
    }

    // ------------------------------------------------------------------
    // local mutations
    // ------------------------------------------------------------------
    public Party create(Player leader) {
        Party p = new Party(UUID.randomUUID(), leader.getUniqueId(), leader.getName(), System.currentTimeMillis());
        register(p);
        touchParty(p.getId());
        touchPlayer(leader.getUniqueId());
        persistParty(p);
        persistMember(p.getId(), leader.getUniqueId(), leader.getName());
        plugin.getProxyBridge().sendCreate(p);
        return p;
    }

    public void addMember(Party party, UUID u, String name) {
        applyAdd(party, u, name, true);
        plugin.getProxyBridge().sendAdd(party, u, name);
    }

    public void removeMember(Party party, UUID u, String reason, String actor) {
        String name = party.getMemberName(u);
        applyRemove(party, u, true);
        plugin.getProxyBridge().sendRemove(party, u, name, reason, actor);
    }

    public void setLeader(Party party, UUID u, String name) {
        String oldName = party.getLeaderName();
        party.setLeader(u, name);
        touchParty(party.getId());
        touchPlayer(u);
        persistParty(party);
        plugin.getProxyBridge().sendLeader(party, oldName);
    }

    public void setColor(Party party, char c) {
        party.setColor(c);
        touchParty(party.getId());
        persistParty(party);
        plugin.getProxyBridge().sendColor(party);
    }

    public void disband(Party party, String actor) {
        UUID id = party.getId();
        applyDisband(party, true);
        plugin.getProxyBridge().sendDisband(id, actor);
    }

    public void syncName(Player p) {
        Party party = getParty(p.getUniqueId());
        if (party == null) return;
        if (!p.getName().equals(party.getMemberName(p.getUniqueId()))) {
            party.setMemberName(p.getUniqueId(), p.getName());
            touchParty(party.getId());
            persistMember(party.getId(), p.getUniqueId(), p.getName());
            if (party.isLeader(p.getUniqueId())) persistParty(party);
        }
    }

    // ------------------------------------------------------------------
    // remote mutations (shared database: memory only, the origin server already wrote it)
    // ------------------------------------------------------------------
    private boolean persistRemote() { return !isShared(); }

    public void remoteCreate(UUID id, UUID leader, String leaderName, char color, long created) {
        boolean w = persistRemote();
        Party existing = parties.get(id);
        if (existing != null) {
            existing.setColor(color);
            if (!existing.isLeader(leader)) existing.setLeader(leader, leaderName);
            playerParty.put(leader, id);
            touchParty(id);
            touchPlayer(leader);
            if (w) {
                persistParty(existing);
                persistMember(id, leader, leaderName);
            }
            return;
        }
        detach(leader, w);
        Party p = new Party(id, leader, leaderName, created);
        p.setColor(color);
        register(p);
        touchParty(id);
        touchPlayer(leader);
        if (w) {
            persistParty(p);
            persistMember(id, leader, leaderName);
        }
    }

    public void remoteAdd(Party party, UUID u, String name) {
        boolean w = persistRemote();
        if (!party.isMember(u)) detach(u, w);
        applyAdd(party, u, name, w);
    }

    public void remoteRemove(Party party, UUID u) { applyRemove(party, u, persistRemote()); }

    public void remoteLeader(Party party, UUID u, String name) {
        party.setLeader(u, name);
        playerParty.put(u, party.getId());
        touchParty(party.getId());
        touchPlayer(u);
        if (persistRemote()) {
            persistParty(party);
            persistMember(party.getId(), u, name);
        }
    }

    public void remoteColor(Party party, char c) {
        party.setColor(c);
        touchParty(party.getId());
        if (persistRemote()) persistParty(party);
    }

    public void remoteDisband(Party party) { applyDisband(party, persistRemote()); }

    /**
     * Per-server (SQLite) storage only: rebuild a party we have never seen from the snapshot that
     * travelled with the invite. With a shared database the party is read from storage instead
     * (a stale snapshot could pull players out of the parties they are in now).
     */
    public Party restoreFromInvite(Invite inv) {
        Party existing = parties.get(inv.partyId);
        if (existing != null) return existing;

        Party p = new Party(inv.partyId, inv.leaderUuid, inv.leaderName, System.currentTimeMillis());
        p.setColor(inv.color);
        Map<UUID, String> snapshot = Party.parseMembers(inv.members);
        for (Map.Entry<UUID, String> e : snapshot.entrySet()) {
            detach(e.getKey(), true);
            p.addMember(e.getKey(), e.getValue());
        }
        detach(inv.leaderUuid, true);
        register(p);
        touchParty(p.getId());
        persistParty(p);
        for (UUID u : p.getMembers()) {
            touchPlayer(u);
            persistMember(p.getId(), u, p.getMemberName(u));
        }
        return p;
    }

    // ------------------------------------------------------------------
    // internal apply helpers
    // ------------------------------------------------------------------
    private void register(Party p) {
        parties.put(p.getId(), p);
        for (UUID u : p.getMembers()) playerParty.put(u, p.getId());
    }

    private void applyAdd(Party party, UUID u, String name, boolean persist) {
        party.addMember(u, name);
        playerParty.put(u, party.getId());
        touchParty(party.getId());
        touchPlayer(u);
        if (persist) persistMember(party.getId(), u, name);
    }

    private void applyRemove(Party party, UUID u, boolean persist) {
        if (party.isLeader(u)) return;
        party.removeMember(u);
        playerParty.remove(u, party.getId());
        plugin.getChatToggled().remove(u);
        touchParty(party.getId());
        touchPlayer(u);
        if (persist) deleteMember(u, party.getId());
    }

    private void applyDisband(Party party, boolean persist) {
        UUID id = party.getId();
        touchParty(id);
        for (UUID u : party.getMembers()) {
            playerParty.remove(u, id);
            plugin.getChatToggled().remove(u);
            touchPlayer(u);
        }
        parties.remove(id);
        for (Map<UUID, Invite> m : invites.values()) m.remove(id);
        if (persist) deleteParty(id);
    }

    private void detach(UUID u, boolean persist) {
        Party old = getParty(u);
        if (old == null) return;
        if (old.isLeader(u)) applyDisband(old, persist);
        else applyRemove(old, u, persist);
    }

    // ------------------------------------------------------------------
    // invites
    // ------------------------------------------------------------------
    private static String inviteKey(UUID partyId, String name) {
        return partyId + ":" + name.toLowerCase();
    }

    private String nameOf(UUID player) {
        Player p = Bukkit.getPlayer(player);
        return p == null ? null : p.getName();
    }

    public void addInvite(UUID target, Invite inv) {
        Map<UUID, Invite> m = invites.get(target);
        if (m == null) { m = new HashMap<UUID, Invite>(); invites.put(target, m); }
        m.put(inv.partyId, inv);
    }

    public boolean hasInvite(UUID target, UUID partyId) {
        Map<UUID, Invite> m = invites.get(target);
        if (m == null) return false;
        Invite inv = m.get(partyId);
        return inv != null && !inv.isExpired(System.currentTimeMillis());
    }

    public Invite findInvite(UUID target, String leaderName) {
        Map<UUID, Invite> m = invites.get(target);
        if (m == null) return null;
        long now = System.currentTimeMillis();
        for (Invite inv : m.values()) {
            if (!inv.isExpired(now) && inv.leaderName.equalsIgnoreCase(leaderName)) return inv;
        }
        return null;
    }

    public List<String> inviteLeaderNames(UUID target) {
        List<String> out = new ArrayList<String>();
        Map<UUID, Invite> m = invites.get(target);
        if (m == null) return out;
        long now = System.currentTimeMillis();
        for (Invite inv : m.values()) if (!inv.isExpired(now)) out.add(inv.leaderName);
        return out;
    }

    /** Removes one invite (deny / party full / party gone) from memory and from storage. */
    public void removeInvite(UUID target, UUID partyId) {
        Map<UUID, Invite> m = invites.get(target);
        if (m != null) m.remove(partyId);
        String name = nameOf(target);
        if (name != null) {
            markResolved(partyId, name);
            deleteInviteRow(partyId, name);
        }
    }

    /** Memory only - used when the player disconnects / switches server (the DB copy follows them). */
    public void clearInvites(UUID target) { invites.remove(target); }

    /** The player joined a party: drop every invite they had, in memory and in storage. */
    public void consumeInvites(UUID target, String name) {
        Map<UUID, Invite> m = invites.remove(target);
        if (m != null) for (UUID partyId : m.keySet()) markResolved(partyId, name);
        if (isShared() && name != null) {
            final String n = name.toLowerCase();
            db.execute(new Database.Work<Void>() {
                @Override public Void run(java.sql.Connection c) throws Exception {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_invites WHERE target_name = ?")) {
                        ps.setString(1, n);
                        ps.executeUpdate();
                    }
                    return null;
                }
            });
        }
    }

    public boolean hasSent(UUID partyId, String targetName) {
        Long exp = sentInvites.get(inviteKey(partyId, targetName));
        return exp != null && exp > System.currentTimeMillis();
    }

    public void markSent(UUID partyId, String targetName, long ttlMs) {
        sentInvites.put(inviteKey(partyId, targetName), System.currentTimeMillis() + ttlMs);
    }

    public void unmarkSent(UUID partyId, String targetName) {
        sentInvites.remove(inviteKey(partyId, targetName));
    }

    /** The invite could not be delivered / was refused: free the leader to try again. */
    public void cancelInvite(UUID leaderUuid, String targetName) {
        Party party = getParty(leaderUuid);
        if (party == null) return;
        unmarkSent(party.getId(), targetName);
        deleteInviteRow(party.getId(), targetName);
    }

    private void markResolved(UUID partyId, String name) {
        resolvedInvites.put(inviteKey(partyId, name), System.currentTimeMillis() + 20000L);
    }

    public boolean isResolved(UUID partyId, String name) {
        Long until = resolvedInvites.get(inviteKey(partyId, name));
        return until != null && until > System.currentTimeMillis();
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Map<UUID, Invite>>> it = invites.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<UUID, Invite>> e = it.next();
            Player target = Bukkit.getPlayer(e.getKey());
            Iterator<Invite> ii = e.getValue().values().iterator();
            while (ii.hasNext()) {
                Invite inv = ii.next();
                if (inv.isExpired(now)) {
                    ii.remove();
                    if (target != null) {
                        markResolved(inv.partyId, target.getName());
                        deleteInviteRow(inv.partyId, target.getName());
                        plugin.getMessages().send(target, "invite-expired", "leader", inv.leaderName);
                    }
                }
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        Iterator<Long> si = sentInvites.values().iterator();
        while (si.hasNext()) if (si.next() < now) si.remove();
        Iterator<Long> ri = resolvedInvites.values().iterator();
        while (ri.hasNext()) if (ri.next() < now) ri.remove();
    }

    // ---- invite storage (shared database only) ----

    /** Stores a pending invite so the target's server can pick it up even without the proxy. */
    public void persistInvite(Party party, String targetName, long ttlMs) {
        if (!isShared()) return;
        final String pid = party.getId().toString();
        final String target = targetName.toLowerCase();
        final String leader = party.getLeader().toString();
        final String leaderName = party.getLeaderName();
        final String color = String.valueOf(party.getColor());
        final long expires = System.currentTimeMillis() + ttlMs;
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                String sql = "INSERT INTO msp_invites(party_id, target_name, leader_uuid, leader_name, color, expires) "
                        + "VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE leader_uuid=VALUES(leader_uuid), "
                        + "leader_name=VALUES(leader_name), color=VALUES(color), expires=VALUES(expires)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, pid);
                    ps.setString(2, target);
                    ps.setString(3, leader);
                    ps.setString(4, leaderName);
                    ps.setString(5, color);
                    ps.setLong(6, expires);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    private void deleteInviteRow(UUID partyId, String targetName) {
        if (!isShared() || targetName == null) return;
        final String pid = partyId.toString();
        final String target = targetName.toLowerCase();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM msp_invites WHERE party_id = ? AND target_name = ?")) {
                    ps.setString(1, pid);
                    ps.setString(2, target);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    /** Looks for invites addressed to players that are online HERE. Call from the main thread. */
    public void pollInvitesAsync() {
        if (!isShared()) return;
        final List<String> names = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName().toLowerCase());
        if (names.isEmpty()) return;
        final long now = System.currentTimeMillis();

        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                final List<String[]> rows = new ArrayList<String[]>();
                StringBuilder sql = new StringBuilder(
                        "SELECT party_id, target_name, leader_uuid, leader_name, color, expires "
                        + "FROM msp_invites WHERE expires > ? AND target_name IN (");
                for (int i = 0; i < names.size(); i++) sql.append(i == 0 ? "?" : ",?");
                sql.append(")");
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    ps.setLong(1, now);
                    for (int i = 0; i < names.size(); i++) ps.setString(i + 2, names.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), String.valueOf(rs.getLong(6))});
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_invites WHERE expires < ?")) {
                    ps.setLong(1, now - 10000L);
                    ps.executeUpdate();
                }
                if (!rows.isEmpty()) {
                    try {
                        Bukkit.getScheduler().runTask(plugin, new Runnable() {
                            @Override public void run() { deliverInvites(rows); }
                        });
                    } catch (Throwable ignored) { /* plugin disabling */ }
                }
                return null;
            }
        });
    }

    /** Main thread. */
    private void deliverInvites(List<String[]> rows) {
        for (String[] r : rows) {
            try {
                UUID partyId = UUID.fromString(r[0]);
                Player target = Bukkit.getPlayerExact(r[1]);
                if (target == null) continue;
                UUID targetId = target.getUniqueId();
                if (isResolved(partyId, target.getName())) continue;
                if (hasInvite(targetId, partyId)) continue;
                if (getParty(targetId) != null) {            // already in a party: this invite is moot
                    markResolved(partyId, target.getName());
                    deleteInviteRow(partyId, target.getName());
                    continue;
                }
                Invite inv = new Invite(partyId, UUID.fromString(r[2]), r[3],
                        Long.parseLong(r[5]), r[4].isEmpty() ? 'b' : r[4].charAt(0), "");
                addInvite(targetId, inv);
                plugin.notifyInvite(target, inv);
            } catch (Throwable t) {
                plugin.getLogger().warning("Bad invite row: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // loading / syncing
    // ------------------------------------------------------------------
    private static final class Snapshot {
        final Map<UUID, Party> parties = new LinkedHashMap<UUID, Party>();
    }

    private final Database.Work<Snapshot> readWork = new Database.Work<Snapshot>() {
        @Override public Snapshot run(java.sql.Connection c) throws Exception { return readSnapshot(c); }
    };

    public void load() {
        parties.clear();
        playerParty.clear();
        try {
            db.init();
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not connect to the " + db.getType() + " database: " + ex.getMessage()
                    + " - the plugin keeps retrying in the background.");
        }
        try {
            Snapshot s = db.query(readWork, 15000L);
            applySnapshot(s, System.nanoTime());
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not load parties: " + ex.getMessage());
        }
        plugin.getLogger().info("Loaded " + parties.size() + " party(ies) from storage ("
                + db.getType() + ").");
    }

    /** Reads everything in ONE consistent transaction. Runs on the DB thread. */
    private Snapshot readSnapshot(java.sql.Connection c) throws Exception {
        Snapshot s = new Snapshot();
        Map<UUID, UUID> memberRows = new HashMap<UUID, UUID>();

        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT party_id, leader_uuid, leader_name, color, created FROM msp_parties")) {
                while (rs.next()) {
                    try {
                        Party p = new Party(UUID.fromString(rs.getString(1)),
                                UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(5));
                        String col = rs.getString(4);
                        if (col != null && !col.isEmpty()) p.setColor(col.charAt(0));
                        s.parties.put(p.getId(), p);
                    } catch (IllegalArgumentException bad) {
                        plugin.getLogger().warning("Ignoring corrupt party row: " + bad.getMessage());
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT uuid, party_id, name FROM msp_members")) {
                while (rs.next()) {
                    try {
                        UUID u = UUID.fromString(rs.getString(1));
                        UUID pid = UUID.fromString(rs.getString(2));
                        Party p = s.parties.get(pid);
                        if (p == null) continue;                       // orphan row
                        memberRows.put(u, pid);
                        p.addMember(u, rs.getString(3));
                    } catch (IllegalArgumentException bad) {
                        plugin.getLogger().warning("Ignoring corrupt member row: " + bad.getMessage());
                    }
                }
            }
            c.commit();
        } finally {
            try { c.setAutoCommit(oldAuto); } catch (Throwable ignored) { }
        }

        // msp_members is the authority for membership: a leader whose row points to another
        // party belongs to that other party (otherwise the player would be in two parties).
        for (Party p : s.parties.values()) {
            UUID rowParty = memberRows.get(p.getLeader());
            if (rowParty != null && !rowParty.equals(p.getId())) p.forceRemove(p.getLeader());
        }
        return s;
    }

    /** Merges a storage snapshot into the cache. MAIN THREAD ONLY. */
    private void applySnapshot(Snapshot snap, long readStartNanos) {
        long protectAfter = readStartNanos - GRACE_NANOS;

        // forget bookkeeping that is too old to matter
        long cutoff = readStartNanos - KEEP_NANOS;
        Iterator<Long> ti = touchedParties.values().iterator();
        while (ti.hasNext()) if (ti.next() - cutoff < 0L) ti.remove();
        ti = touchedPlayers.values().iterator();
        while (ti.hasNext()) if (ti.next() - cutoff < 0L) ti.remove();

        // 1) parties that no longer exist in storage
        for (Party local : new ArrayList<Party>(parties.values())) {
            UUID id = local.getId();
            if (snap.parties.containsKey(id)) continue;
            if (recent(touchedParties, id, protectAfter)) continue;
            parties.remove(id);
            for (Map<UUID, Invite> m : invites.values()) m.remove(id);
        }

        // 2) parties from storage (insert / update) unless they were touched locally just now
        for (Party fresh : snap.parties.values()) {
            UUID id = fresh.getId();
            if (recent(touchedParties, id, protectAfter)) continue;
            Party local = parties.get(id);
            if (local == null) parties.put(id, fresh);
            else local.syncFrom(fresh);
        }

        // 3) rebuild player -> party from the party member lists
        Map<UUID, UUID> rebuilt = new HashMap<UUID, UUID>();
        for (Party p : parties.values()) {
            for (UUID u : p.getMembers()) {
                UUID prev = rebuilt.get(u);
                if (prev == null) {
                    rebuilt.put(u, p.getId());
                } else if (p.getId().equals(playerParty.get(u))) {
                    rebuilt.put(u, p.getId());                 // listed twice: keep what we had
                }
            }
        }
        // players touched locally keep their local mapping while it is still consistent
        for (Map.Entry<UUID, UUID> e : playerParty.entrySet()) {
            UUID u = e.getKey();
            if (!recent(touchedPlayers, u, protectAfter)) continue;
            Party lp = parties.get(e.getValue());
            if (lp != null && lp.isMember(u)) rebuilt.put(u, e.getValue());
        }
        Iterator<UUID> pi = playerParty.keySet().iterator();
        while (pi.hasNext()) if (!rebuilt.containsKey(pi.next())) pi.remove();
        playerParty.putAll(rebuilt);

        // chat toggles of players that are no longer in a party
        List<UUID> toggled;
        synchronized (plugin.getChatToggled()) { toggled = new ArrayList<UUID>(plugin.getChatToggled()); }
        for (UUID u : toggled) if (!playerParty.containsKey(u)) plugin.getChatToggled().remove(u);
    }

    private void warnThrottled(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMillis > 30000L) {
            lastWarnMillis = now;
            plugin.getLogger().warning(msg);
        }
    }

    /** Non-blocking refresh (timer). Reads on the DB thread, merges on the main thread. */
    public void refreshAsync() {
        if (!isShared()) return;
        final long start = System.nanoTime();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) {
                final Snapshot s;
                try {
                    s = readSnapshot(c);
                } catch (Exception ex) {
                    warnThrottled("Cache refresh failed: " + ex.getMessage());
                    return null;
                }
                try {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override public void run() {
                            applySnapshot(s, start);
                            lastSyncNanos = System.nanoTime();
                        }
                    });
                } catch (Throwable ignored) { /* plugin disabling */ }
                return null;
            }
        });
    }

    /**
     * Blocking refresh for the main thread, used right before decisions that depend on the
     * truth (create / invite / accept). Throttled; gives up quickly if the database is slow.
     */
    public void syncNow() {
        if (!isShared()) return;
        long now = System.nanoTime();
        if (now - backoffUntilNanos < 0L) return;
        if (now - lastSyncNanos < 500000000L) return;
        try {
            Snapshot s = db.query(readWork, 1500L);
            applySnapshot(s, now);
            lastSyncNanos = System.nanoTime();
        } catch (Throwable t) {
            backoffUntilNanos = System.nanoTime() + 10000000000L;
            warnThrottled("Database is slow / unreachable, using cached parties: " + t);
        }
    }

    /** For AsyncPlayerPreLoginEvent: refresh before the player's join event so the cache is current. */
    public void syncFromAsyncThread() {
        if (!isShared()) return;
        long now = System.nanoTime();
        if (now - backoffUntilNanos < 0L) return;
        if (now - lastSyncNanos < 1000000000L) return;
        try {
            final Snapshot s = db.query(readWork, 2000L);
            final long start = now;
            Future<Void> f = Bukkit.getScheduler().callSyncMethod(plugin, new Callable<Void>() {
                @Override public Void call() {
                    applySnapshot(s, start);
                    lastSyncNanos = System.nanoTime();
                    return null;
                }
            });
            f.get(2, TimeUnit.SECONDS);
        } catch (Throwable t) {
            backoffUntilNanos = System.nanoTime() + 10000000000L;
        }
    }

    /** Kept for compatibility - use refreshAsync(). */
    public void reloadFromStorage() { refreshAsync(); }

    public void saveAll() {
        if (isShared()) {
            // storage is authoritative: pushing this server's (possibly stale) cache would
            // overwrite newer changes and resurrect deleted parties - just wait for the queue.
            db.flush(10000L);
            return;
        }
        for (Party p : parties.values()) {
            persistParty(p);
            for (UUID u : p.getMembers()) persistMember(p.getId(), u, p.getMemberName(u));
        }
    }

    public void shutdown() { db.close(); }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------
    private void persistParty(Party p) {
        final String id = p.getId().toString();
        final String leader = p.getLeader().toString();
        final String leaderName = p.getLeaderName();
        final String color = String.valueOf(p.getColor());
        final long created = p.getCreated();
        final boolean mysql = isShared();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                String sql = mysql
                    ? "INSERT INTO msp_parties(party_id, leader_uuid, leader_name, color, created) VALUES(?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE leader_uuid=VALUES(leader_uuid), leader_name=VALUES(leader_name), "
                    + "color=VALUES(color)"
                    : "INSERT OR REPLACE INTO msp_parties(party_id, leader_uuid, leader_name, color, created) VALUES(?,?,?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, id);
                    ps.setString(2, leader);
                    ps.setString(3, leaderName);
                    ps.setString(4, color);
                    ps.setLong(5, created);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    private void persistMember(UUID partyId, UUID uuid, String name) {
        final String pid = partyId.toString();
        final String u = uuid.toString();
        final String n = name;
        final boolean mysql = isShared();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                String sql = mysql
                    ? "INSERT INTO msp_members(uuid, party_id, name) VALUES(?,?,?) "
                    + "ON DUPLICATE KEY UPDATE party_id=VALUES(party_id), name=VALUES(name)"
                    : "INSERT OR REPLACE INTO msp_members(uuid, party_id, name) VALUES(?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, u);
                    ps.setString(2, pid);
                    ps.setString(3, n);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    /** Conditional on the party: a late delete can never remove a newer membership elsewhere. */
    private void deleteMember(UUID uuid, UUID partyId) {
        final String u = uuid.toString();
        final String pid = partyId.toString();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM msp_members WHERE uuid = ? AND party_id = ?")) {
                    ps.setString(1, u);
                    ps.setString(2, pid);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    private void deleteParty(UUID partyId) {
        final String id = partyId.toString();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_members WHERE party_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_invites WHERE party_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_parties WHERE party_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }
}
