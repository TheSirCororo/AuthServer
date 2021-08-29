package ru.cororo.authserver.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import kotlinx.coroutines.launch
import org.slf4j.Logger
import ru.cororo.authserver.AuthServer
import java.nio.file.Path

val proxy get() = VelocityPlugin.proxy
val path get() = VelocityPlugin.path
val logger get() = AuthServer.logger

@Plugin(
    id = "authserver",
    name = "AuthServer",
    version = "1.0.0"
)
class VelocityPlugin
@Inject constructor(
    @DataDirectory val path: Path,
    val proxy: ProxyServer,
    val logger: Logger
) {
    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        logger.info("AuthServer plugin enabling...")
        AuthServer.launch {
            AuthServer.start()
        }
    }

    companion object {
        lateinit var proxy: ProxyServer
            private set
        lateinit var logger: Logger
            private set
        lateinit var path: Path
            private set
    }
}