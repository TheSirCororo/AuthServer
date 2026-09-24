package ru.cororo.authserver.velocity.internal;

import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Plugin settings from {@code plugins/authserver/config.toml}.
 *
 * @param authServer      name of the auth server in velocity.toml
 * @param apiUrl          base URL of the auth server HTTP API
 * @param secret          shared secret: auth server {@code proxy.secret}
 * @param premiumBypass   licensed players skip the auth server and go straight to the online group
 * @param licensedTransfer transfer 1.20.5+ players who chose a licensed login back to the proxy instead of asking
 *                        them to reconnect
 * @param apiFailure      what to do when the API cannot be reached
 * @param strategy        how a server is picked from a group
 * @param onlineServers   targets for licensed players
 * @param offlineServers  targets for offline players
 */
public record PluginConfig(
        String authServer,
        String apiUrl,
        String secret,
        boolean premiumBypass,
        boolean licensedTransfer,
        ApiFailure apiFailure,
        RoutingStrategy strategy,
        List<String> onlineServers,
        List<String> offlineServers) {

    public enum ApiFailure {
        /** Treat the player as offline: they must log in with a password. */
        OFFLINE,
        /** Refuse the connection. */
        DENY
    }

    public PluginConfig {
        Objects.requireNonNull(authServer, "auth-server");
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("secret must match the auth server's proxy.secret (at least 16 characters)");
        }
        onlineServers = List.copyOf(onlineServers);
        offlineServers = List.copyOf(offlineServers);
    }

    public static PluginConfig load(Path directory) throws IOException {
        Path file = directory.resolve("config.toml");
        if (Files.notExists(file)) {
            Files.createDirectories(directory);
            try (InputStream defaults = PluginConfig.class.getResourceAsStream("/config.toml")) {
                Files.copy(Objects.requireNonNull(defaults, "bundled config.toml"), file);
            }
        }
        Toml toml = new Toml().read(file.toFile());
        return new PluginConfig(
                toml.getString("auth-server", "auth"),
                toml.getString("api-url", "http://127.0.0.1:8765"),
                toml.getString("secret", ""),
                toml.getBoolean("premium-bypass", false),
                toml.getBoolean("licensed-transfer", false),
                ApiFailure.valueOf(toml.getString("api-failure", "OFFLINE").toUpperCase(Locale.ROOT)),
                RoutingStrategy.valueOf(toml.getString("routing.strategy", "RANDOM").toUpperCase(Locale.ROOT)),
                toml.getList("routing.online", List.of()),
                toml.getList("routing.offline", List.of()));
    }
}
