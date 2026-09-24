package ru.cororo.authserver.velocity.api.event;

import com.velocitypowered.api.proxy.InboundConnection;
import ru.cororo.authserver.bridge.AuthMode;

import java.util.Objects;

/**
 * Fired during Velocity's pre-login with the mode the auth server recommends. Change {@link #setMode} to force a
 * name to authenticate through Mojang ({@link AuthMode#ONLINE}) or with a password ({@link AuthMode#OFFLINE}).
 */
public final class AuthServerPreLoginEvent {
    private final String username;
    private final InboundConnection connection;
    private final boolean registered;
    private AuthMode mode;

    public AuthServerPreLoginEvent(String username, InboundConnection connection, boolean registered, AuthMode mode) {
        this.username = Objects.requireNonNull(username);
        this.connection = Objects.requireNonNull(connection);
        this.registered = registered;
        this.mode = Objects.requireNonNull(mode);
    }

    public String getUsername() {
        return username;
    }

    public InboundConnection getConnection() {
        return connection;
    }

    /** Whether the auth server has an account with this name. */
    public boolean isRegistered() {
        return registered;
    }

    public AuthMode getMode() {
        return mode;
    }

    public void setMode(AuthMode mode) {
        this.mode = Objects.requireNonNull(mode);
    }
}
