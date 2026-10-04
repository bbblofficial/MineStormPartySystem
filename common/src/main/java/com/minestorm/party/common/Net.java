package com.minestorm.party.common;

/** Shared channel + protocol constants for cross-server messaging. */
public final class Net {
    private Net() {}

    public static final String CHANNEL = "minestormparty:main";

    // message types
    public static final String INVITE       = "INVITE";
    public static final String INVITE_DENY  = "INVITE_DENY";
    public static final String INVITE_ACCEPT= "INVITE_ACCEPT";
    public static final String CHAT         = "CHAT";
    public static final String SYNC_ADD     = "SYNC_ADD";
    public static final String SYNC_REMOVE  = "SYNC_REMOVE";
    public static final String SYNC_DISBAND = "SYNC_DISBAND";

    public static final String SEP = "\u0001"; // unlikely in names
}
