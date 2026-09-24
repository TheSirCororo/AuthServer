package ru.cororo.authserver.bridge;

import java.util.Objects;

/**
 * Account operations the proxy plugin performs through the auth server HTTP API. Every request carries
 * {@link PlayerStatus#SECRET_HEADER}; bodies are JSON.
 *
 * <pre>
 * POST /api/v1/players/{name}/password   {@link PasswordChange} -> {@link PasswordChangeResponse}
 * POST /api/v1/players/{name}/logout     (no body)              -> 204
 * POST /api/v1/players/{name}/command    {@link AccountCommand}   -> {@link AccountCommandResponse}
 * </pre>
 */
public final class AccountApi {
    public static final String PASSWORD = "/password";
    public static final String LOGOUT = "/logout";
    public static final String COMMAND = "/command";

    /** Account commands the proxy may run for an authenticated player: {@code /email} and {@code /2fa}. */
    public static final java.util.Set<String> COMMANDS = java.util.Set.of("email", "2fa");

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

    /**
     * Runs one of {@link #COMMANDS} for an authenticated player, as if typed on the auth server.
     *
     * @param locale language tag of the player's client, for the answer
     */
    public record AccountCommand(String command, java.util.List<String> arguments, String locale) {
        public AccountCommand {
            Objects.requireNonNull(command, "command");
            arguments = java.util.List.copyOf(arguments);
            Objects.requireNonNull(locale, "locale");
        }
    }

    /** @param messages the replies to show the player, as JSON text components */
    public record AccountCommandResponse(java.util.List<String> messages) {
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
