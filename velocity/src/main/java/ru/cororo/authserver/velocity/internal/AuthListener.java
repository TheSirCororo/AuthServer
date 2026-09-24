package ru.cororo.authserver.velocity.internal;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionProvider;
import com.velocitypowered.api.permission.Tristate;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps unauthenticated players on the auth server and routes them once it reports a successful login.
 * Security invariants: only signed messages from the auth server's backend connection authenticate a player,
 * unauthenticated players cannot connect anywhere else, have no proxy permissions and cannot run proxy commands
 * (their commands go to the auth server), and a kick from the auth server never falls back to another server.
 * Until the password is checked, an offline player is only a name - anyone can connect with it.
 */
public final class AuthListener implements AuthServerApi {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(BridgeCodec.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;
    private final BridgeCodec bridge;
    private final AuthServerClient client;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean gatesInstalled = new AtomicBoolean();
    private Object plugin;

    /** A node no permission plugin grants on purpose; see {@link #onPostLogin}. */
    private static final String GATE_CHECK_PERMISSION = "authserver.internal.unauthenticated-check";

    public AuthListener(ProxyServer proxy, Logger logger, PluginConfig config) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.bridge = new BridgeCodec(config.secret());
        this.client = new AuthServerClient(config.apiUrl(), config.secret());
    }

    /** Registers the listener and the proxy-wide account commands. */
    public void register(Object plugin) {
        this.plugin = plugin;
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

    /**
     * Installs the permission and command gates once the proxy is up, i.e. after every plugin registered its own
     * listeners: handlers of equal priority run in registration order, so with the lowest priority these run last and
     * see the final result, whichever permission plugin is installed.
     */
    @Subscribe
    public void onListenerBound(ListenerBoundEvent event) {
        if (!gatesInstalled.compareAndSet(false, true)) return;
        proxy.getEventManager().register(plugin, PermissionsSetupEvent.class, Short.MIN_VALUE, this::gatePermissions);
        proxy.getEventManager().register(plugin, CommandExecuteEvent.class, Short.MIN_VALUE, this::gateCommand);
    }

    /**
     * Wraps whatever permission provider was set up (LuckPerms or any other plugin) so that its permissions apply only
     * after the player authenticated; before that every check is denied.
     */
    private void gatePermissions(PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player)) return;
        PermissionProvider provider = event.getProvider();
        event.setProvider(subject -> {
            PermissionFunction granted = provider.createFunction(subject);
            if (!(subject instanceof Player player)) return granted;
            return permission -> isAuthenticated(player) ? granted.getPermissionValue(permission) : Tristate.FALSE;
        });
    }

    /**
     * Commands of unauthenticated players are not run by the proxy at all, whatever permissions a command checks;
     * they are forwarded to the auth server, which handles /login and /register.
     */
    private void gateCommand(CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player && !isAuthenticated(player)) {
            event.setResult(CommandExecuteEvent.CommandResult.forwardToServer());
        }
    }

    /**
     * Fails closed if the permission gate is not in effect (a plugin replaced the provider after it): nobody is
     * authenticated yet at this point, so every permission must be denied.
     */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player) || player.getPermissionValue(GATE_CHECK_PERMISSION) == Tristate.FALSE) return;
        logger.error("Another plugin replaced the permission provider after AuthServer, so {} would keep their "
                + "permissions before logging in. Refusing players until that plugin is fixed or removed.", player.getUsername());
        player.disconnect(Component.text("Authentication is unavailable, try again later"));
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
            case BridgeMessage.LicensedLogin licensed -> reconnectForLicensedLogin(player, licensed.detail());
        }
    }

    /**
     * Online mode is decided when a connection starts, so a player who chose a licensed login has to connect again:
     * 1.20.5+ clients are transferred back to the address they used (velocity.toml needs accepts-transfers = true),
     * others are asked to reconnect.
     */
    private void reconnectForLicensedLogin(Player player, String message) {
        var address = player.getVirtualHost();
        if (config.licensedTransfer() && address.isPresent()
                && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_5)) {
            logger.info("Transferring {} to {} for a licensed login", player.getUsername(), address.get());
            player.transferToHost(address.get());
        } else {
            player.disconnect(Component.text(message));
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
