package ru.cororo.authserver.bridge;

/**
 * Answer of the auth server HTTP API {@code GET /api/v1/players/{name}}, used by the proxy to decide at pre-login
 * whether a name must authenticate with Mojang.
 *
 * @param registered an account with this name (ignoring case) exists
 * @param premium    the account is bound to a licensed Mojang account and logs in through Mojang
 * @param username   the registered spelling of the name, or {@code null} when unregistered
 * @param onlineMode whether the proxy should authenticate this name with Mojang, as decided by the auth server's
 *                   premium policy
 */
public record PlayerStatus(boolean registered, boolean premium, String username, boolean onlineMode) {
    /** Path of the endpoint; append the URL-encoded player name. */
    public static final String PATH = "/api/v1/players/";

    /** Request header carrying the shared secret. */
    public static final String SECRET_HEADER = "X-AuthServer-Secret";
}
