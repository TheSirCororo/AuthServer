package ru.cororo.authserver.velocity.internal;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.slf4j.Logger;
import ru.cororo.authserver.bridge.AccountApi.PasswordChangeResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-wide {@code /changepassword}, {@code /logout}, {@code /2fa} and {@code /email}. They are hidden on the auth
 * server itself, where Velocity then forwards the command to the auth server's own implementation.
 */
final class AccountCommands {
    private static final long CONFIRM_MILLIS = 30_000;
    private static final int MAX_FAILURES = 3;
    private static final long COOLDOWN_MILLIS = 60_000;

    private final ProxyServer proxy;
    private final Logger logger;
    private final AuthListener sessions;
    private final AuthServerClient client;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Failures> failures = new ConcurrentHashMap<>();

    private record Pending(String oldPassword, String newPassword, long expiresAt) {
    }

    private record Failures(int count, long blockedUntil) {
    }

    AccountCommands(ProxyServer proxy, Logger logger, AuthListener sessions, AuthServerClient client) {
        this.proxy = proxy;
        this.logger = logger;
        this.sessions = sessions;
        this.client = client;
    }

    void register(Object plugin) {
        var commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("changepassword").aliases("changepass", "cp").plugin(plugin).build(), new ChangePassword());
        commands.register(commands.metaBuilder("logout").plugin(plugin).build(), new Logout());
        commands.register(commands.metaBuilder("2fa").aliases("twofactor").plugin(plugin).build(), new Remote("2fa"));
        commands.register(commands.metaBuilder("email").aliases("mail").plugin(plugin).build(), new Remote("email"));
    }

    void forget(UUID player) {
        pending.remove(player);
        failures.remove(player);
    }

    /** Available to authenticated players everywhere except on the auth server. */
    private boolean available(SimpleCommand.Invocation invocation) {
        return invocation.source() instanceof Player player && sessions.isAuthenticated(player) && !sessions.isOnAuthServer(player);
    }

    private final class ChangePassword implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return available(invocation);
        }

        @Override
        public void execute(Invocation invocation) {
            Player player = (Player) invocation.source();
            String[] arguments = invocation.arguments();
            if (arguments.length == 1 && arguments[0].equalsIgnoreCase("confirm")) {
                confirm(player);
            } else if (arguments.length == 2) {
                pending.put(player.getUniqueId(), new Pending(arguments[0], arguments[1], System.currentTimeMillis() + CONFIRM_MILLIS));
                say(player, "change-confirm");
            } else {
                say(player, "change-usage");
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return invocation.arguments().length <= 1 ? List.of("confirm") : List.of();
        }

        private void confirm(Player player) {
            Pending request = pending.remove(player.getUniqueId());
            if (request == null || request.expiresAt() < System.currentTimeMillis()) {
                say(player, "change-nothing-pending");
                return;
            }
            Failures failed = failures.get(player.getUniqueId());
            long wait = failed == null ? 0 : failed.blockedUntil() - System.currentTimeMillis();
            if (wait > 0) {
                say(player, "change-cooldown", "seconds", Long.toString(wait / 1000 + 1));
                return;
            }
            client.changePassword(player.getUsername(), request.oldPassword(), request.newPassword()).whenComplete((response, error) -> {
                if (error != null) {
                    logger.warn("Could not change the password of {}: {}", player.getUsername(), error.getMessage());
                    say(player, "unavailable");
                    return;
                }
                PasswordChangeResult result = response.result();
                if (result == PasswordChangeResult.WRONG_PASSWORD) {
                    failures.merge(player.getUniqueId(), new Failures(1, 0), (old, ignored) -> old.count() + 1 >= MAX_FAILURES
                            ? new Failures(0, System.currentTimeMillis() + COOLDOWN_MILLIS)
                            : new Failures(old.count() + 1, 0));
                } else if (result == PasswordChangeResult.CHANGED) {
                    failures.remove(player.getUniqueId());
                }
                switch (result) {
                    case CHANGED -> say(player, "change-success");
                    case WRONG_PASSWORD -> say(player, "change-wrong-password");
                    case NOT_REGISTERED -> say(player, "change-not-registered");
                    case NO_PASSWORD -> say(player, "change-no-password");
                    case TOO_SHORT -> say(player, "change-too-short", "min", Integer.toString(response.min()));
                    case TOO_LONG -> say(player, "change-too-long", "max", Integer.toString(response.max()));
                    case UNSAFE -> say(player, "change-unsafe");
                    case SAME_AS_NAME -> say(player, "change-same-as-name");
                }
            });
        }
    }

    private final class Logout implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return available(invocation);
        }

        @Override
        public void execute(Invocation invocation) {
            Player player = (Player) invocation.source();
            client.logout(player.getUsername()).whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.warn("Could not end the session of {}: {}", player.getUsername(), error.getMessage());
                    say(player, "unavailable");
                    return;
                }
                say(player, "logout-success");
                sessions.logout(player);
            });
        }
    }

    /** Runs the auth server's own implementation through the HTTP API and shows its answers. */
    private final class Remote implements SimpleCommand {
        private final String command;

        Remote(String command) {
            this.command = command;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return available(invocation);
        }

        @Override
        public void execute(Invocation invocation) {
            Player player = (Player) invocation.source();
            String locale = player.getEffectiveLocale() == null ? "en" : player.getEffectiveLocale().toLanguageTag();
            client.accountCommand(player.getUsername(), command, List.of(invocation.arguments()), locale).whenComplete((response, error) -> {
                if (error != null) {
                    logger.warn("Could not run /{} for {}: {}", command, player.getUsername(), error.getMessage());
                    say(player, "unavailable");
                    return;
                }
                response.messages().forEach(json -> player.sendMessage(GsonComponentSerializer.gson().deserialize(json)));
            });
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length > 1) return List.of();
            List<String> options = command.equals("2fa") ? List.of("totp", "email", "confirm", "off") : List.of("set", "confirm", "remove");
            String typed = invocation.arguments().length == 0 ? "" : invocation.arguments()[0];
            return options.stream().filter(option -> option.startsWith(typed.toLowerCase())).toList();
        }
    }

    private static void say(Player player, String key, String... placeholders) {
        player.sendMessage(Messages.render(player.getEffectiveLocale(), key, placeholders));
    }
}
