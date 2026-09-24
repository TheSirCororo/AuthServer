package ru.cororo.authserver.velocity.api.event;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ru.cororo.authserver.bridge.AuthMethod;
import ru.cororo.authserver.bridge.AuthMode;

import java.util.Objects;
import java.util.Optional;

/**
 * A player authenticated. {@link #getTarget()} is where the player will be sent: the server the auth server asked
 * for, or the one picked by the routing rules. Set another target to override it, or {@code null} to leave the
 * player where they are.
 */
public final class AuthServerLoginEvent {
    private final Player player;
    private final AuthMode mode;
    private final AuthMethod method;
    private RegisteredServer target;

    public AuthServerLoginEvent(Player player, AuthMode mode, AuthMethod method, RegisteredServer target) {
        this.player = Objects.requireNonNull(player);
        this.mode = Objects.requireNonNull(mode);
        this.method = Objects.requireNonNull(method);
        this.target = target;
    }

    public Player getPlayer() {
        return player;
    }

    public AuthMode getMode() {
        return mode;
    }

    public AuthMethod getMethod() {
        return method;
    }

    public Optional<RegisteredServer> getTarget() {
        return Optional.ofNullable(target);
    }

    public void setTarget(RegisteredServer target) {
        this.target = target;
    }
}
