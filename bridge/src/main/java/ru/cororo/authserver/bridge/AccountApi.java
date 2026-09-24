package ru.cororo.authserver.bridge;

import java.util.Objects;

/**
 * Account operations the proxy plugin performs through the auth server HTTP API. Every request carries
 * {@link PlayerStatus#SECRET_HEADER}; bodies are JSON.
 *
 * <pre>
 * POST /api/v1/players/{name}/password   {@link PasswordChange} -> {@link PasswordChangeResponse}
 * POST /api/v1/players/{name}/logout     (no body)              -> 204
 * </pre>
 */
public final class AccountApi {
    public static final String PASSWORD = "/password";
    public static final String LOGOUT = "/logout";

    private AccountApi() {
    }

    public record PasswordChange(String oldPassword, String newPassword) {
        public PasswordChange {
            Objects.requireNonNull(oldPassword, "oldPassword");
            Objects.requireNonNull(newPassword, "newPassword");
        }
    }

    /**
     * @param min minimum password length, for {@link PasswordChangeResult#TOO_SHORT}
     * @param max maximum password length, for {@link PasswordChangeResult#TOO_LONG}
     */
    public record PasswordChangeResponse(PasswordChangeResult result, int min, int max) {
    }

    public enum PasswordChangeResult {
        CHANGED,
        NOT_REGISTERED,
        /** Licensed-only account: there is no password to change. */
        NO_PASSWORD,
        WRONG_PASSWORD,
        TOO_SHORT,
        TOO_LONG,
        UNSAFE,
        SAME_AS_NAME
    }
}
