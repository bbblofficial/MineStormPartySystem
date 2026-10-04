package com.minestorm.party.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory party state + async persistence (SQLite or MySQL).
 * Everything here is meant to be called from the server thread.
 *
 * Methods without "remote" apply the change locally AND broadcast it to the network.
 * Methods starting with "remote" apply a change received from another server (no re-broadcast).
 */
public class PartyManager {

    private final MineStormParty plugin;
    private final Database db;

    private final Map<UUID, Party> parties = new LinkedHashMap<UUID, Party>();        // partyId -> party
    private final Map<UUID, UUID> playerParty = new HashMap<UUID, UUID>();            // player  -> partyId
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<UUID, Map<UUID, Invite>>();
    private final Map<String, Long> sentInvites = new HashMap<String, Long>();        // partyId:name -> expires

    public PartyManager(MineStormParty plugin) {
        this.plugin = plugin;
        this.db = new Database(plugin);
    }

    public Database.Type getDatabaseType() { return db.getType(); }

    // ------------------------------------------------------------------
    // lookups
    // ------------------------------------------------------------------
    public Party getParty(UUID player) {
        UUID id = playerParty.get(player);
        return id == null ? null : parties.get(id);
    }

    public Party get(UUID partyId) { return parties.get(partyId); }

    public Party findByLeaderName(String name) {
        for (Party p : parties.values()) if (p.getLeaderName().equalsIgnoreCase(name)) return p;
        return null;
    }

    public List<Party> all() { return new ArrayList<Party>(parties.values()); }

    // ------------------------------------------------------------------
    // local mutations
    // ------------------------------------------------------------------
    public Party create(Player leader) {
        Party p = new Party(UUID.randomUUID(), leader.getUniqueId(), leader.getName(), System.currentTimeMillis());
        register(p);
        persistParty(p);
        persistMember(p.getId(), leader.getUniqueId(), leader.getName());
        plugin.getProxyBridge().sendCreate(p);
        return p;
    }

    public void addMember(Party party, UUID u, String name) {
        applyAdd(party, u, name);
        plugin.getProxyBridge().sendAdd(party, u, name);
    }

    public void removeMember(Party party, UUID u, String reason, String actor) {
        String name = party.getMemberName(u);
        applyRemove(party, u);
        plugin.getProxyBridge().sendRemove(party, u, name, reason, actor);
    }

    public void setLeader(Party party, UUID u, String name) {
        String oldName = party.getLeaderName();
        party.setLeader(u, name);
        persistParty(party);
        plugin.getProxyBridge().sendLeader(party, oldName);
    }

    public void setColor(Party party, char c) {
        party.setColor(c);
        persistParty(party);
        plugin.getProxyBridge().sendColor(party);
    }

    public void disband(Party party, String actor) {
        UUID id = party.getId();
        applyDisband(party);
        plugin.getProxyBridge().sendDisband(id, actor);
    }

    public void syncName(Player p) {
        Party party = getParty(p.getUniqueId());
        if (party == null) return;
        if (!p.getName().equals(party.getMemberName(p.getUniqueId()))) {
            party.setMemberName(p.getUniqueId(), p.getName());
            persistMember(party.getId(), p.getUniqueId(), p.getName());
            if (party.isLeader(p.getUniqueId())) persistParty(party);
        }
    }

    // ------------------------------------------------------------------
    // remote mutations
    // ------------------------------------------------------------------
    public void remoteCreate(UUID id, UUID leader, String leaderName, char color, long created) {
        Party existing = parties.get(id);
        if (existing != null) {
            existing.setColor(color);
            if (!existing.isLeader(leader)) existing.setLeader(leader, leaderName);
            playerParty.put(leader, id);
            persistParty(existing);
            persistMember(id, leader, leaderName);
            return;
        }
        detach(leader);
        Party p = new Party(id, leader, leaderName, created);
        p.setColor(color);
        register(p);
        persistParty(p);
        persistMember(id, leader, leaderName);
    }

    public void remoteAdd(Party party, UUID u, String name) {
        if (!party.isMember(u)) detach(u);
        applyAdd(party, u, name);
    }

    public void remoteRemove(Party party, UUID u) { applyRemove(party, u); }

    public void remoteLeader(Party party, UUID u, String name) {
        party.setLeader(u, name);
        playerParty.put(u, party.getId());
        persistParty(party);
        persistMember(party.getId(), u, name);
    }

    public void remoteColor(Party party, char c) {
        party.setColor(c);
        persistParty(party);
    }

    public void remoteDisband(Party party) { applyDisband(party); }

    public Party restoreFromInvite(Invite inv) {
        Party existing = parties.get(inv.partyId);
        if (existing != null) return existing;

        Party p = new Party(inv.partyId, inv.leaderUuid, inv.leaderName, System.currentTimeMillis());
        p.setColor(inv.color);
        Map<UUID, String> snapshot = Party.parseMembers(inv.members);
        for (Map.Entry<UUID, String> e : snapshot.entrySet()) {
            detach(e.getKey());
            p.addMember(e.getKey(), e.getValue());
        }
        detach(inv.leaderUuid);
        register(p);
        persistParty(p);
        for (UUID u : p.getMembers()) persistMember(p.getId(), u, p.getMemberName(u));
        return p;
    }

    // ------------------------------------------------------------------
    // internal apply helpers
    // ------------------------------------------------------------------
    private void register(Party p) {
        parties.put(p.getId(), p);
        for (UUID u : p.getMembers()) playerParty.put(u, p.getId());
    }

    private void applyAdd(Party party, UUID u, String name) {
        party.addMember(u, name);
        playerParty.put(u, party.getId());
        persistMember(party.getId(), u, name);
    }

    private void applyRemove(Party party, UUID u) {
        if (party.isLeader(u)) return;
        party.removeMember(u);
        playerParty.remove(u);
        plugin.getChatToggled().remove(u);
        deleteMember(u);
    }

    private void applyDisband(Party party) {
        for (UUID u : party.getMembers()) {
            playerParty.remove(u);
            plugin.getChatToggled().remove(u);
        }
        parties.remove(party.getId());
        for (Map<UUID, Invite> m : invites.values()) m.remove(party.getId());
        deleteParty(party.getId());
    }

    private void detach(UUID u) {
        Party old = getParty(u);
        if (old == null) return;
        if (old.isLeader(u)) applyDisband(old);
        else applyRemove(old, u);
    }

    // ------------------------------------------------------------------
    // invites (unchanged)
    // ------------------------------------------------------------------
    public void addInvite(UUID target, Invite inv) {
        Map<UUID, Invite> m = invites.get(target);
        if (m == null) { m = new HashMap<UUID, Invite>(); invites.put(target, m); }
        m.put(inv.partyId, inv);
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

    public void removeInvite(UUID target, UUID partyId) {
        Map<UUID, Invite> m = invites.get(target);
        if (m != null) m.remove(partyId);
    }

    public void clearInvites(UUID target) { invites.remove(target); }

    public boolean hasSent(UUID partyId, String targetName) {
        Long exp = sentInvites.get(partyId + ":" + targetName.toLowerCase());
        return exp != null && exp > System.currentTimeMillis();
    }

    public void markSent(UUID partyId, String targetName, long ttlMs) {
        sentInvites.put(partyId + ":" + targetName.toLowerCase(), System.currentTimeMillis() + ttlMs);
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
                    if (target != null) plugin.getMessages().send(target, "invite-expired", "leader", inv.leaderName);
                }
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        Iterator<Long> si = sentInvites.values().iterator();
        while (si.hasNext()) if (si.next() < now) si.remove();
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------
    public void load() {
        parties.clear();
        playerParty.clear();
        try {
            db.init();
            loadFromStorage();
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not load parties: " + ex.getMessage());
        }
        plugin.getLogger().info("Loaded " + parties.size() + " party(ies) from storage ("
                + db.getType() + ").");
    }

    /** Re-reads everything from storage without touching the running cache
     *  until the read completes. Safe to call from a background thread. */
    public synchronized void reloadFromStorage() {
        try {
            final Map<UUID, Party> fresh = new LinkedHashMap<UUID, Party>();
            final Map<UUID, UUID> freshPlayer = new HashMap<UUID, UUID>();

            db.query(new Database.Work<Void>() {
                @Override public Void run(java.sql.Connection c) throws Exception {
                    try (Statement st = c.createStatement()) {
                        try (ResultSet rs = st.executeQuery(
                                "SELECT party_id, leader_uuid, leader_name, color, created FROM msp_parties")) {
                            while (rs.next()) {
                                Party p = new Party(UUID.fromString(rs.getString(1)),
                                        UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(5));
                                String col = rs.getString(4);
                                if (col != null && !col.isEmpty()) p.setColor(col.charAt(0));
                                fresh.put(p.getId(), p);
                            }
                        }
                        try (ResultSet rs = st.executeQuery("SELECT uuid, party_id, name FROM msp_members")) {
                            while (rs.next()) {
                                Party p = fresh.get(UUID.fromString(rs.getString(2)));
                                if (p == null) continue;
                                p.addMember(UUID.fromString(rs.getString(1)), rs.getString(3));
                            }
                        }
                    }
                    return null;
                }
            });

            for (Party p : fresh.values()) {
                for (UUID u : p.getMembers()) freshPlayer.put(u, p.getId());
            }

            parties.clear();
            parties.putAll(fresh);
            playerParty.clear();
            playerParty.putAll(freshPlayer);

            // refresh chat-toggle set for players who left their party externally
            Iterator<UUID> it = plugin.getChatToggled().iterator();
            while (it.hasNext()) {
                if (!playerParty.containsKey(it.next())) it.remove();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Cache refresh failed: " + ex.getMessage());
        }
    }

    /** Loads into the current (possibly empty) cache. Used at startup. */
    private void loadFromStorage() throws Exception {
        db.query(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                try (Statement st = c.createStatement()) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT party_id, leader_uuid, leader_name, color, created FROM msp_parties")) {
                        while (rs.next()) {
                            Party p = new Party(UUID.fromString(rs.getString(1)),
                                    UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(5));
                            String col = rs.getString(4);
                            if (col != null && !col.isEmpty()) p.setColor(col.charAt(0));
                            parties.put(p.getId(), p);
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT uuid, party_id, name FROM msp_members")) {
                        while (rs.next()) {
                            Party p = parties.get(UUID.fromString(rs.getString(2)));
                            if (p == null) continue;
                            p.addMember(UUID.fromString(rs.getString(1)), rs.getString(3));
                        }
                    }
                }
                return null;
            }
        });
        for (Party p : parties.values()) {
            for (UUID u : p.getMembers()) playerParty.put(u, p.getId());
        }
    }

    public void saveAll() {
        for (Party p : parties.values()) {
            persistParty(p);
            for (UUID u : p.getMembers()) persistMember(p.getId(), u, p.getMemberName(u));
        }
    }

    public void shutdown() { db.close(); }

    private void persistParty(Party p) {
        final String id = p.getId().toString();
        final String leader = p.getLeader().toString();
        final String leaderName = p.getLeaderName();
        final String color = String.valueOf(p.getColor());
        final long created = p.getCreated();
        final boolean mysql = db.getType() == Database.Type.MYSQL;
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
        final boolean mysql = db.getType() == Database.Type.MYSQL;
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

    private void deleteMember(UUID uuid) {
        final String u = uuid.toString();
        db.execute(new Database.Work<Void>() {
            @Override public Void run(java.sql.Connection c) throws Exception {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_members WHERE uuid = ?")) {
                    ps.setString(1, u);
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
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msp_parties WHERE party_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }
}
