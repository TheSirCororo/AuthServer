package ru.cororo.authserver.bridge;

/** Why the auth server gave up on a player. */
public enum FailureReason {
    WRONG_PASSWORD,
    TIMEOUT,
    /** Mojang did not confirm a licensed login. */
    PREMIUM_VERIFICATION_FAILED,
    /** The name is invalid, reserved or differs only in case from a registered one. */
    INVALID_NAME,
    /** Registration was refused, e.g. too many accounts from one address. */
    REGISTRATION_DENIED,
    /** A plugin rejected the player. */
    DENIED_BY_PLUGIN,
    OTHER
}
