package ru.cororo.authserver.bridge;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A message the auth server sends to the proxy through the player's connection on {@link BridgeCodec#CHANNEL}.
 * Messages are signed, so a client cannot forge them even though they travel over its connection.
 */
public sealed interface BridgeMessage permits BridgeMessage.Authenticated, BridgeMessage.Failed {
    UUID playerId();

    String username();

    /** Milliseconds since the epoch when the auth server created the message. */
    long timestamp();

    /**
     * The player proved their identity.
     *
     * @param mode   whether the account is licensed (Mojang-authenticated) or an offline account
     * @param method how the player authenticated this time
     * @param target server the auth server wants the player routed to; empty lets the proxy decide
     */
    record Authenticated(UUID playerId, String username, long timestamp, AuthMode mode, AuthMethod method,
                         Optional<String> target) implements BridgeMessage {
        public Authenticated {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(target, "target");
        }
    }

    /** Authentication failed and the auth server is about to disconnect the player. */
    record Failed(UUID playerId, String username, long timestamp, FailureReason reason, String detail)
            implements BridgeMessage {
        public Failed {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
