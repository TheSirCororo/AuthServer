package ru.cororo.authserver

import kotlinx.coroutines.CoroutineScope
import org.slf4j.Logger
import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.session.Session
import java.net.InetSocketAddress

/**
 * Auth server instance
 */
interface AuthServer : CoroutineScope, Protocolable {
    /**
     * Logger that used by server
     */
    val logger: Logger

    /**
     * Auth server local address
     */
    val address: InetSocketAddress

    /**
     * Player sessions set
     */
    val sessions: Set<Session>
}