package ru.cororo.authserver.velocity.internal;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.cororo.authserver.bridge.AuthMethod;
import ru.cororo.authserver.bridge.AuthMode;
import ru.cororo.authserver.bridge.BridgeCodec;
import ru.cororo.authserver.bridge.BridgeMessage;
import ru.cororo.authserver.velocity.api.AuthServerApi;
import ru.cororo.authserver.velocity.api.event.AuthServerFailEvent;
import ru.cororo.authserver.velocity.api.event.AuthServerLoginEvent;
import ru.cororo.authserver.velocity.api.event.AuthServerPreLoginEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps unauthenticated players on the auth server and routes them once it reports a successful login.
 * Security invariants: only signed messages from the auth server's backend connection authenticate a player,
 * unauthenticated players cannot connect anywhere else, and a kick from the auth server never falls back to
 * another server.
 */
public final class AuthListener implements AuthServerApi {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(BridgeCodec.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;
    private final BridgeCodec bridge;
    private final AuthServerClient client;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AuthListener(ProxyServer proxy, Logger logger, PluginConfig config) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.bridge = new BridgeCodec(config.secret());
        this.client = new AuthServerClient(config.apiUrl(), config.secret());
    }

    /** Registers the listener and the proxy-wide account commands. */
    public void register(Object plugin) {
        proxy.getEventManager().register(plugin, this);
        var commands = new AccountCommands(proxy, logger, this, client);
        commands.register(plugin);
        proxy.getEventManager().register(plugin, DisconnectEvent.class, event -> commands.forget(event.getPlayer().getUniqueId()));
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) return null;
        String username = event.getUsername();
        return EventTask.resumeWhenComplete(client.status(username).handle((answer, error) -> {
            boolean registered = false;
            AuthMode mode = AuthMode.OFFLINE;
            if (error != null) {
                logger.warn("Auth server API unavailable for {}: {}", username, error.getMessage());
                if (config.apiFailure() == PluginConfig.ApiFailure.DENY) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Authentication is unavailable, try again later")));
                    return null;
                }
            } else {
                registered = answer.registered();
                mode = answer.onlineMode() ? AuthMode.ONLINE : AuthMode.OFFLINE;
            }
            var decided = proxy.getEventManager()
                    .fire(new AuthServerPreLoginEvent(username, event.getConnection(), registered, mode)).join();
            event.setResult(decided.getMode() == AuthMode.ONLINE
                    ? PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                    : PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            return null;
        }));
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        if (config.premiumBypass() && player.isOnlineMode()) {
            sessions.put(player.getUniqueId(), new Session(AuthMode.ONLINE, AuthMethod.PREMIUM));
            fireLogin(player, AuthMode.ONLINE, AuthMethod.PREMIUM, route(AuthMode.ONLINE).orElse(null), true)
                    .ifPresentOrElse(event::setInitialServer, () -> authServer().ifPresent(event::setInitialServer));
            return;
        }
        authServer().ifPresentOrElse(event::setInitialServer,
                () -> logger.error("Auth server '{}' is not registered in velocity.toml", config.authServer()));
    }

    /** Unauthenticated players may only connect to the auth server. */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId()) || isAuthServer(event.getOriginalServer())) return;
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId()) || !isAuthServer(event.getServer())) return;
        Component reason = event.getServerKickReason().orElse(Component.text("Disconnected"));
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        // Never forward the bridge channel, whichever side sent it.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection) || !isAuthServer(connection.getServer())) return;
        Player player = connection.getPlayer();
        BridgeMessage message;
        try {
            message = bridge.decode(event.getData(), System.currentTimeMillis());
        } catch (BridgeCodec.InvalidMessageException exception) {
            logger.warn("Rejected bridge message for {}: {}", player.getUsername(), exception.getMessage());
            return;
        }
        if (!message.playerId().equals(player.getUniqueId())) {
            logger.warn("Bridge message for {} arrived on {}'s connection", message.username(), player.getUsername());
            return;
        }
        switch (message) {
            case BridgeMessage.Authenticated authenticated -> {
                sessions.put(player.getUniqueId(), new Session(authenticated.mode(), authenticated.method()));
                RegisteredServer target = authenticated.target().flatMap(proxy::getServer)
                        .or(() -> route(authenticated.mode())).orElse(null);
                fireLogin(player, authenticated.mode(), authenticated.method(), target, false);
            }
            case BridgeMessage.Failed failed ->
                    proxy.getEventManager().fireAndForget(new AuthServerFailEvent(player, failed.reason(), failed.detail()));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Fires the login event; when [initial] the result is returned for the initial-server event, otherwise the
     * player is connected to it.
     */
    private Optional<RegisteredServer> fireLogin(Player player, AuthMode mode, AuthMethod method, RegisteredServer target, boolean initial) {
        var event = new AuthServerLoginEvent(player, mode, method, target);
        if (initial) {
            return proxy.getEventManager().fire(event).join().getTarget();
        }
        proxy.getEventManager().fire(event).thenAccept(result -> result.getTarget()
                .ifPresent(server -> player.createConnectionRequest(server).fireAndForget()));
        return Optional.empty();
    }

    private Optional<RegisteredServer> route(AuthMode mode) {
        List<String> group = mode == AuthMode.ONLINE ? config.onlineServers() : config.offlineServers();
        if (group.isEmpty()) group = proxy.getConfiguration().getAttemptConnectionOrder();
        List<RegisteredServer> servers = group.stream()
                .map(proxy::getServer).flatMap(Optional::stream)
                .filter(server -> !isAuthServer(server))
                .toList();
        return config.strategy().choose(servers);
    }

    boolean isOnAuthServer(Player player) {
        return player.getCurrentServer().map(connection -> isAuthServer(connection.getServer())).orElse(false);
    }

    /** Ends the proxy session and sends the player back to the auth server to log in again. */
    void logout(Player player) {
        sessions.remove(player.getUniqueId());
        Optional<RegisteredServer> auth = authServer();
        if (auth.isEmpty()) {
            player.disconnect(Component.text("Logged out"));
            return;
        }
        player.createConnectionRequest(auth.get()).connect().whenComplete((result, error) -> {
            // An unauthenticated player must not stay on a game server.
            if (error != null || !result.isSuccessful()) player.disconnect(Component.text("Logged out"));
        });
    }

    private Optional<RegisteredServer> authServer() {
        return proxy.getServer(config.authServer());
    }

    private boolean isAuthServer(RegisteredServer server) {
        return server.getServerInfo().getName().equalsIgnoreCase(config.authServer());
    }

    @Override
    public boolean isAuthenticated(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    @Override
    public Optional<Session> session(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }
}
