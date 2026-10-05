package com.minestorm.party.bukkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A party is identified by its own random id; the leader is just an attribute.
 *
 * MSP-FIXER v2: methods are synchronized because parties are read from other
 * threads too (PlaceholderAPI / async plugins) while the main thread mutates them.
 */
public class Party {

    private final UUID id;
    private UUID leader;
    private String leaderName;
    private char color = 'b';
    private final long created;
    private final Map<UUID, String> members = new LinkedHashMap<UUID, String>();

    public Party(UUID id, UUID leader, String leaderName, long created) {
        this.id = id;
        this.leader = leader;
        this.leaderName = leaderName;
        this.created = created;
        this.members.put(leader, leaderName);
    }

    public UUID getId() { return id; }
    public synchronized UUID getLeader() { return leader; }
    public synchronized String getLeaderName() { return leaderName; }
    public synchronized boolean isLeader(UUID u) { return leader.equals(u); }
    public long getCreated() { return created; }
    public synchronized char getColor() { return color; }
    public synchronized void setColor(char c) { this.color = Character.toLowerCase(c); }

    public synchronized void setLeader(UUID u, String name) {
        this.leader = u;
        this.leaderName = name;
        this.members.put(u, name);
    }

    /** Snapshot copy - safe to iterate while the party is modified. */
    public synchronized Set<UUID> getMembers() {
        return Collections.unmodifiableSet(new LinkedHashSet<UUID>(members.keySet()));
    }

    public synchronized int size() { return members.size(); }
    public synchronized boolean isMember(UUID u) { return members.containsKey(u); }

    public synchronized void addMember(UUID u, String name) { members.put(u, name); }

    public synchronized void removeMember(UUID u) {
        if (!u.equals(leader)) members.remove(u);
    }

    /** Removes a member even if it is the leader (used when storage says the leader belongs elsewhere). */
    public synchronized void forceRemove(UUID u) { members.remove(u); }

    public synchronized String getMemberName(UUID u) {
        String n = members.get(u);
        return n == null ? u.toString().substring(0, 8) : n;
    }

    public synchronized void setMemberName(UUID u, String n) {
        if (!members.containsKey(u)) return;
        members.put(u, n);
        if (u.equals(leader)) leaderName = n;
    }

    public synchronized UUID findMember(String name) {
        for (Map.Entry<UUID, String> e : members.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Copies leader / color / members from another snapshot of the same party. */
    public void syncFrom(Party other) {
        UUID l;
        String ln;
        char c;
        Map<UUID, String> copy;
        synchronized (other) {
            l = other.leader;
            ln = other.leaderName;
            c = other.color;
            copy = new LinkedHashMap<UUID, String>(other.members);
        }
        synchronized (this) {
            this.leader = l;
            this.leaderName = ln;
            this.color = c;
            this.members.clear();
            this.members.putAll(copy);
        }
    }

    /** uuid:name,uuid:name,... (minecraft names never contain ':' or ','). */
    public synchronized String membersCsv() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, String> e : members.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<UUID, String> parseMembers(String csv) {
        Map<UUID, String> out = new LinkedHashMap<UUID, String>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            int i = part.indexOf(':');
            if (i <= 0) continue;
            try { out.put(UUID.fromString(part.substring(0, i)), part.substring(i + 1)); }
            catch (IllegalArgumentException ignored) { }
        }
        return out;
    }
}
