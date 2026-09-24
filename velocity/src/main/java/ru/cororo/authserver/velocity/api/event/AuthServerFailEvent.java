package ru.cororo.authserver.velocity.api.event;

import com.velocitypowered.api.proxy.Player;
import ru.cororo.authserver.bridge.FailureReason;

import java.util.Objects;

/** The auth server gave up on a player (wrong passwords, timeout, ...); it is disconnecting them. */
public final class AuthServerFailEvent {
    private final Player player;
    private final FailureReason reason;
    private final String detail;

    public AuthServerFailEvent(Player player, FailureReason reason, String detail) {
        this.player = Objects.requireNonNull(player);
        this.reason = Objects.requireNonNull(reason);
        this.detail = Objects.requireNonNull(detail);
    }

    public Player getPlayer() {
        return player;
    }

    public FailureReason getReason() {
        return reason;
    }

    /** Message the auth server showed the player, as plain text. */
    public String getDetail() {
        return detail;
    }
}
