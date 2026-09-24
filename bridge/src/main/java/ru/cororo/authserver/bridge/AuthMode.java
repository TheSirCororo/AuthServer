package ru.cororo.authserver.bridge;

/** Whether a player's identity comes from Mojang or from an auth server password. */
public enum AuthMode {
    /** Licensed account verified by Mojang's session server. */
    ONLINE,
    /** Offline ("cracked") account protected by a password. */
    OFFLINE
}
