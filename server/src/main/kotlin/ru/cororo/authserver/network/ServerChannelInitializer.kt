package ru.cororo.authserver.network

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.network.pipeline.EncryptionHandler
import ru.cororo.authserver.network.pipeline.FrameHandler
import ru.cororo.authserver.network.pipeline.PacketCodec
import ru.cororo.authserver.network.pipeline.PacketHandler
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.Packet
import kotlin.coroutines.CoroutineContext

class ServerChannelInitializer : ChannelInitializer<SocketChannel>(), CoroutineScope {
    override val coroutineContext: CoroutineContext = CoroutineName("Sending-packets") + Dispatchers.IO

    override fun initChannel(ch: SocketChannel) {
        val session = AuthServerImpl.createSession(ch)
        session.protocol.state = MinecraftProtocol.ProtocolState.HANDSHAKE
        println("created session $session")
        AuthServerImpl.sessions.add(session)
        ch.pipeline().addLast("encryption", EncryptionHandler(session))
            .addLast("framing", FrameHandler())
            .addLast("packet-codec", PacketCodec(session))
            .addLast("packet-handler", PacketHandler(session))
        launch {
            while (ch.isActive) {
                println("receiving packets")
                val packet = session.sendChannel.receive()
                println("[initializer] sending packet $packet")
                if (packet is Packet) {
                    ch.pipeline().writeAndFlush(packet)
                }
            }
        }
    }
}