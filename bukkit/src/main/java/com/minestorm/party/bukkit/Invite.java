package com.minestorm.party.bukkit;

import java.util.UUID;

/** A pending invite, stored on the server where the invited player is online. */
public class Invite {

    public final UUID partyId;
    public final UUID leaderUuid;
    public final String leaderName;
    public final long expires;
    public final char color;
    public final String members; // snapshot (csv) so the target's server can restore the party

    public Invite(UUID partyId, UUID leaderUuid, String leaderName, long expires, char color, String members) {
        this.partyId = partyId;
        this.leaderUuid = leaderUuid;
        this.leaderName = leaderName;
        this.expires = expires;
        this.color = color;
        this.members = members;
    }

    public boolean isExpired(long now) { return now > expires; }
}
