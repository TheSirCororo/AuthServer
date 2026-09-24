package ru.cororo.authserver.server.network

import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.timeout.ReadTimeoutException
import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.DisconnectPacket
import ru.cororo.authserver.protocol.packet.LoginDisconnectPacket
import ru.cororo.authserver.server.network.pipeline.CipherCodec
import ru.cororo.authserver.server.network.pipeline.CompressionCodec
import java.io.IOException
import java.net.InetSocketAddress
import javax.crypto.SecretKey

/** Handles packets of one connection state. All calls happen on the connection's event loop. */
interface PacketHandler {
    fun handle(packet: Packet)

    fun disconnected() = Unit
}

/**
 * One client connection. [version], [state] and [handler] are confined to the channel's event loop:
 * the packet codec reads them while encoding, so a state change takes effect exactly between two packets.
 */
class Connection(val channel: Channel) : SimpleChannelInboundHandler<Packet>() {
    @Volatile
    var version: ProtocolVersion = ProtocolVersion.LATEST

    @Volatile
    var state: ProtocolState = ProtocolState.HANDSHAKE

    lateinit var handler: PacketHandler

    /** Client address; replaced by the forwarded one behind a proxy. */
    @Volatile
    var address: InetSocketAddress = channel.remoteAddress() as InetSocketAddress

    @Volatile
    var closing = false
        private set

    val isActive: Boolean get() = channel.isActive && !closing

    fun inEventLoop(): Boolean = channel.eventLoop().inEventLoop()

    fun execute(action: () -> Unit) {
        if (inEventLoop()) action() else channel.eventLoop().execute(action)
    }

    fun send(packet: ClientboundPacket) {
        if (channel.isActive) channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
    }

    /** Switches state on the event loop, after every packet sent so far has been encoded. */
    fun switchState(state: ProtocolState, handler: PacketHandler) = execute {
        this.state = state
        this.handler = handler
    }

    fun enableEncryption(secret: SecretKey) = execute {
        channel.pipeline().addFirst(CIPHER, CipherCodec(secret))
    }

    fun enableCompression(threshold: Int) = execute {
        channel.pipeline().addAfter(FRAME_ENCODER, COMPRESSION, CompressionCodec(threshold))
    }

    /** Sends a disconnect message suitable for the current state and closes the connection. */
    fun disconnect(reason: Component) = execute {
        if (closing) return@execute
        closing = true
        val packet = when (state) {
            ProtocolState.LOGIN -> LoginDisconnectPacket(reason)
            ProtocolState.CONFIGURATION, ProtocolState.PLAY -> DisconnectPacket(reason)
            else -> null
        }
        if (packet == null || !channel.isActive) channel.close()
        else channel.writeAndFlush(packet).addListener(ChannelFutureListener.CLOSE)
    }

    fun close() = execute {
        closing = true
        channel.close()
    }

    override fun channelRead0(ctx: ChannelHandlerContext, packet: Packet) {
        if (!closing) handler.handle(packet)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        closing = true
        if (this::handler.isInitialized) handler.disconnected()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when (cause) {
            is ReadTimeoutException -> logger.debug("{} timed out", address)
            is IOException -> logger.debug("{}: {}", address, cause.message)
            else -> logger.debug("Closing {} after a protocol error", address, cause)
        }
        ctx.close()
    }

    companion object {
        const val CIPHER = "cipher"
        const val FRAME_DECODER = "frame-decoder"
        const val FRAME_ENCODER = "frame-encoder"
        const val COMPRESSION = "compression"
        const val PACKET_CODEC = "packet-codec"
        const val HANDLER = "handler"

        private val logger = LoggerFactory.getLogger(Connection::class.java)
    }
}
