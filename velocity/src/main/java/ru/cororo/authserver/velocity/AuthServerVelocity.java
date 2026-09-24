package ru.cororo.authserver.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import ru.cororo.authserver.velocity.api.AuthServerApi;
import ru.cororo.authserver.velocity.internal.AuthListener;
import ru.cororo.authserver.velocity.internal.PluginConfig;

import java.io.IOException;
import java.nio.file.Path;

@Plugin(
        id = "authserver",
        name = "AuthServer",
        version = "0.2.0",
        description = "Routes players through the AuthServer limbo and reports how they authenticated",
        authors = {"cororo"})
public final class AuthServerVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public AuthServerVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        PluginConfig config;
        try {
            config = PluginConfig.load(dataDirectory);
        } catch (IOException | RuntimeException exception) {
            logger.error("AuthServer is disabled: {}", exception.getMessage());
            return;
        }
        var listener = new AuthListener(proxy, logger, config);
        proxy.getChannelRegistrar().register(AuthListener.CHANNEL);
        listener.register(this);
        AuthServerApi.Holder.set(listener);
        logger.info("Routing players through '{}'", config.authServer());
    }
}
