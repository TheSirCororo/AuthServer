package ru.cororo.authserver.bridge;

/** How a player authenticated. */
public enum AuthMethod {
    /** Entered the password of an existing account. */
    LOGIN,
    /** Created a new offline account. */
    REGISTER,
    /** Resumed a recent session from the same address without typing a password. */
    SESSION,
    /** Licensed player verified by Mojang. */
    PREMIUM,
    /** A plugin authenticated the player. */
    FORCED
}
