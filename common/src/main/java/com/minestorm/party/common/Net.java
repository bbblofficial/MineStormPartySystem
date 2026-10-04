package com.minestorm.party.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared channel + wire protocol for backend <-> proxy messaging.
 *
 * Frame = UTF-8 bytes of  TYPE SEP arg1 SEP arg2 ...   (SEP = char 0x01).
 * No length prefix, no DataOutputStream: both sides use encode()/decode() only.
 *
 * Layouts (index 0 is always the type):
 *   P_CREATE   partyId, leaderUuid, leaderName, color, created
 *   P_ADD      partyId, uuid, name
 *   P_REMOVE   partyId, uuid, name, reason(leave|kick|admin), actor
 *   P_LEADER   partyId, newLeaderUuid, newLeaderName, oldLeaderName
 *   P_COLOR    partyId, colorChar
 *   P_DISBAND  partyId, actorName
 *   CHAT       partyId, senderName, message
 *   NOTICE     partyId, key(join|quit), name, exceptUuid
 *   INVITE_REQ partyId, leaderUuid, leaderName, targetName, seconds, color, membersCsv
 *   INVITE     partyId, leaderUuid, leaderName, targetUuid, targetName, seconds, color, membersCsv
 *   INVITE_DENY / INVITE_FAIL / INVITE_BUSY   leaderUuid, otherName   (routed to leader)
 */
public final class Net {
    private Net() {}

    public static final String CHANNEL = "minestormparty:main";

    public static final char SEP_CHAR = (char) 1;
    private static final String SEP = String.valueOf(SEP_CHAR);
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEP));

    // broadcast to every other backend
    public static final String P_CREATE  = "P_CREATE";
    public static final String P_ADD     = "P_ADD";
    public static final String P_REMOVE  = "P_REMOVE";
    public static final String P_LEADER  = "P_LEADER";
    public static final String P_COLOR   = "P_COLOR";
    public static final String P_DISBAND = "P_DISBAND";
    public static final String CHAT      = "CHAT";
    public static final String NOTICE    = "NOTICE";

    // invites
    public static final String INVITE_REQ  = "INVITE_REQ";
    public static final String INVITE      = "INVITE";
    public static final String INVITE_DENY = "INVITE_DENY";
    public static final String INVITE_FAIL = "INVITE_FAIL";
    public static final String INVITE_BUSY = "INVITE_BUSY";

    private static final Set<String> BROADCAST = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(P_CREATE, P_ADD, P_REMOVE, P_LEADER, P_COLOR, P_DISBAND, CHAT, NOTICE)));

    /** Messages whose element [1] is the UUID of the player they must be delivered to. */
    private static final Set<String> PLAYER_ROUTED = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(INVITE_DENY, INVITE_FAIL, INVITE_BUSY)));

    public static boolean isBroadcast(String type) { return BROADCAST.contains(type); }
    public static boolean isPlayerRouted(String type) { return PLAYER_ROUTED.contains(type); }

    public static byte[] encode(String type, String... args) {
        StringBuilder sb = new StringBuilder(type);
        if (args != null) {
            for (String a : args) {
                sb.append(SEP_CHAR).append(a == null ? "" : a.replace(SEP, ""));
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String[] decode(byte[] data) {
        return SPLIT.split(new String(data, StandardCharsets.UTF_8), -1);
    }

    /** Converts an INVITE_REQ (from a backend) into the INVITE delivered to the target's backend. */
    public static byte[] toInvite(String[] req, String targetUuid, String targetName) {
        return encode(INVITE, req[1], req[2], req[3], targetUuid, targetName, req[5], req[6], req[7]);
    }
}
