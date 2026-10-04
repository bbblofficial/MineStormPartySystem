package com.minestorm.party.bukkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Party {

    private final UUID leader;
    private String leaderName;
    private char color = 'b';
    private final long created;
    private final Map<UUID, String> members = new LinkedHashMap<UUID, String>();

    public Party(UUID leader, String leaderName, long created) {
        this.leader = leader;
        this.leaderName = leaderName;
        this.created = created;
        this.members.put(leader, leaderName);
    }

    public UUID getLeader() { return leader; }
    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String n) { this.leaderName = n; }
    public boolean isLeader(UUID u) { return leader.equals(u); }
    public long getCreated() { return created; }
    public char getColor() { return color; }
    public void setColor(char c) { this.color = Character.toLowerCase(c); }

    public Set<UUID> getMembers() { return Collections.unmodifiableSet(members.keySet()); }
    public int size() { return members.size(); }
    public boolean isMember(UUID u) { return members.containsKey(u); }

    public void addMember(UUID u, String name) { members.put(u, name); }
    public void removeMember(UUID u) { members.remove(u); }

    public String getMemberName(UUID u) {
        String n = members.get(u);
        return n == null ? u.toString().substring(0, 8) : n;
    }
    public void setMemberName(UUID u, String n) { members.put(u, n); }

    public UUID findMember(String name) {
        for (Map.Entry<UUID, String> e : members.entrySet())
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    /** Not really transferable since party is identified by leader; kept for admin use. */
    public void transferLeader(UUID newLeader, String newName) {
        // Party becomes identified by newLeader in the manager.
        members.put(newLeader, newName);
    }
}
