package ru.cororo.authserver.session

import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.Protocolable
import java.net.InetSocketAddress

/**
 * Player session
 */
interface Session : Protocolable {
    /**
     * Player remote address
     */
    val address: InetSocketAddress

    /**
     * Player protocol version
     */
    val protocolVersion: ProtocolVersion
}