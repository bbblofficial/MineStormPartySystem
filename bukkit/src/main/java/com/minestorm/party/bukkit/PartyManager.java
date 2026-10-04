package com.minestorm.party.bukkit;

import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PartyManager {

    private final MineStormParty plugin;
    private final Database db;
    private final Map<UUID, Party> byLeader = new LinkedHashMap<UUID, Party>();
    private final Map<UUID, UUID> playerParty = new HashMap<UUID, UUID>();

    public PartyManager(MineStormParty plugin) {
        this.plugin = plugin;
        this.db = new Database(plugin);
    }

    public Party getParty(UUID player) {
        UUID leader = playerParty.get(player);
        return leader == null ? null : byLeader.get(leader);
    }

    public Party getByLeader(UUID leader) { return byLeader.get(leader); }
    public Collection<Party> all() { return byLeader.values(); }

    public Party createParty(Player leader) {
        Party p = new Party(leader.getUniqueId(), leader.getName(), System.currentTimeMillis());
        byLeader.put(leader.getUniqueId(), p);
        playerParty.put(leader.getUniqueId(), leader.getUniqueId());
        return p;
    }

    public void addMember(Party party, UUID u, String name) {
        party.addMember(u, name);
        playerParty.put(u, party.getLeader());
    }

    public void removeMember(Party party, UUID u) {
        party.removeMember(u);
        playerParty.remove(u);
    }

    public void disband(Party party) {
        for (UUID u : party.getMembers()) playerParty.remove(u);
        byLeader.remove(party.getLeader());
    }

    public void updateLeader(Party old, Player newLeader) {
        // Simple approach: recreate with new leader.
        UUID oldLeader = old.getLeader();
        String oldName = old.getLeaderName();
        char color = old.getColor();
        long created = old.getCreated();

        Party p = new Party(newLeader.getUniqueId(), newLeader.getName(), created);
        p.setColor(color);
        for (UUID u : old.getMembers()) {
            if (u.equals(oldLeader)) continue;
            p.addMember(u, old.getMemberName(u));
        }
        p.addMember(oldLeader, oldName); // old leader now a member
        byLeader.remove(oldLeader);
        byLeader.put(newLeader.getUniqueId(), p);
        for (UUID u : p.getMembers()) playerParty.put(u, newLeader.getUniqueId());
    }

    // ----- invites (in-memory + persisted) -----
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<UUID, Map<UUID, Long>>();

    public void addInvite(UUID leader, UUID target, String targetName, long ttlMs) {
        Map<UUID, Long> m = invites.get(leader);
        if (m == null) { m = new HashMap<UUID, Long>(); invites.put(leader, m); }
        m.put(target, System.currentTimeMillis() + ttlMs);
    }

    public boolean hasInvite(UUID leader, UUID target) {
        Map<UUID, Long> m = invites.get(leader);
        if (m == null) return false;
        Long exp = m.get(target);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { m.remove(target); return false; }
        return true;
    }

    public void clearInvite(UUID leader, UUID target) {
        Map<UUID, Long> m = invites.get(leader);
        if (m != null) m.remove(target);
    }

    public void clearInvitesTo(UUID target) {
        for (Map<UUID, Long> m : invites.values()) m.remove(target);
    }

    // ----- DB -----
    public void load() {
        byLeader.clear();
        playerParty.clear();
        try {
            Statement st = db.connection().createStatement();
            ResultSet rs = st.executeQuery("SELECT leader_uuid, leader_name, color, created FROM parties");
            while (rs.next()) {
                UUID leader = UUID.fromString(rs.getString("leader_uuid"));
                String lname = rs.getString("leader_name");
                char col = rs.getString("color").charAt(0);
                long created = rs.getLong("created");
                Party p = new Party(leader, lname, created);
                p.setColor(col);
                byLeader.put(leader, p);
            }
            rs.close();

            rs = st.executeQuery("SELECT leader_uuid, uuid, name FROM party_members");
            while (rs.next()) {
                UUID leader = UUID.fromString(rs.getString("leader_uuid"));
                Party p = byLeader.get(leader);
                if (p == null) continue;
                UUID u = UUID.fromString(rs.getString("uuid"));
                String name = rs.getString("name");
                p.addMember(u, name);
                playerParty.put(u, leader);
            }
            rs.close();
            st.close();
        } catch (Exception ex) {
            plugin.getLogger().severe("Load failed: " + ex.getMessage());
        }
        plugin.getLogger().info("Loaded " + byLeader.size() + " party(ies) from SQLite.");
    }

    public void save() {
        try {
            db.connection().setAutoCommit(false);
            Statement st = db.connection().createStatement();
            st.executeUpdate("DELETE FROM party_members");
            st.executeUpdate("DELETE FROM parties");
            st.close();

            for (Party p : byLeader.values()) {
                PreparedStatement ps = db.prep("INSERT INTO parties(leader_uuid, leader_name, color, created) VALUES(?,?,?,?)");
                ps.setString(1, p.getLeader().toString());
                ps.setString(2, p.getLeaderName());
                ps.setString(3, String.valueOf(p.getColor()));
                ps.setLong(4, p.getCreated());
                ps.executeUpdate();
                ps.close();

                PreparedStatement mp = db.prep("INSERT INTO party_members(leader_uuid, uuid, name, joined) VALUES(?,?,?,?)");
                for (UUID u : p.getMembers()) {
                    mp.setString(1, p.getLeader().toString());
                    mp.setString(2, u.toString());
                    mp.setString(3, p.getMemberName(u));
                    mp.setLong(4, System.currentTimeMillis());
                    mp.addBatch();
                }
                mp.executeBatch();
                mp.close();
            }
            db.connection().commit();
            db.connection().setAutoCommit(true);
        } catch (Exception ex) {
            plugin.getLogger().severe("Save failed: " + ex.getMessage());
        }
    }
}
