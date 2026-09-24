package ru.cororo.authserver.velocity.api;

import com.velocitypowered.api.proxy.Player;
import ru.cororo.authserver.bridge.AuthMethod;
import ru.cororo.authserver.bridge.AuthMode;

import java.util.Objects;
import java.util.Optional;

/**
 * Authentication state of players on the proxy, for other Velocity plugins. Add
 * {@code @Dependency(id = "authserver")} to your plugin and use {@link #get()}.
 */
public interface AuthServerApi {
    /** Whether the auth server (or the licensed bypass) authenticated the player in this session. */
    boolean isAuthenticated(Player player);

    /** How the player authenticated, once authenticated. */
    Optional<Session> session(Player player);

    /**
     * @param mode   licensed or offline account
     * @param method how the player proved it this time
     */
    record Session(AuthMode mode, AuthMethod method) {
        public Session {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(method, "method");
        }
    }

    static AuthServerApi get() {
        return Objects.requireNonNull(Holder.instance, "AuthServer plugin is not loaded");
    }

    /** Set by the plugin on startup. */
    final class Holder {
        private static volatile AuthServerApi instance;

        private Holder() {
        }

        public static void set(AuthServerApi api) {
            instance = api;
        }
    }
}
