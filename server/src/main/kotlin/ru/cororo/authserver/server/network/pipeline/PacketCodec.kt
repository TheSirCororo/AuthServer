package ru.cororo.authserver.server.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.EncoderException
import org.slf4j.LoggerFactory
import ru.cororo.authserver.protocol.MinecraftPackets
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.server.network.Connection

/**
 * Converts frames to packet models using the connection's current version and state.
 * Serverbound packets the auth server does not model are dropped in the configuration and play states;
 * clientbound packets that do not exist in the client's version (a boss bar for 1.8, say) are skipped, and
 * their write completes successfully without touching the wire.
 */
class PacketCodec(private val connection: Connection) : ChannelDuplexHandler() {
    override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
        if (message !is Packet) {
            ctx.write(message, promise)
            return
        }
        val table = MinecraftPackets.registry.table(connection.version, connection.state, PacketDirection.CLIENTBOUND)
        val (id, codec) = table.lookup(message) ?: run {
            logger.debug("{} does not exist in {} {}", message.javaClass.simpleName, connection.version, connection.state)
            promise.trySuccess()
            return
        }
        val buffer = ctx.alloc().buffer()
        try {
            buffer.writeVarInt(id)
            codec.encode(buffer, message, connection.version)
        } catch (exception: Exception) {
            buffer.release()
            throw EncoderException("Could not encode ${message.javaClass.simpleName} for ${connection.version}", exception)
        }
        ctx.write(buffer, promise)
    }

    override fun channelRead(ctx: ChannelHandlerContext, message: Any) {
        if (message !is ByteBuf) {
            ctx.fireChannelRead(message)
            return
        }
        try {
            decode(message)?.let(ctx::fireChannelRead)
        } finally {
            message.release()
        }
    }

    private fun decode(frame: ByteBuf): Packet? {
        val state = connection.state
        val id = frame.readVarInt()
        val codec = MinecraftPackets.registry.table(connection.version, state, PacketDirection.SERVERBOUND).codec(id)
        if (codec == null) {
            if (state == ProtocolState.PLAY || state == ProtocolState.CONFIGURATION) return null
            throw DecoderException("Unexpected packet 0x%02x in %s".format(id, state))
        }
        val packet = codec.decode(frame, connection.version)
        if (frame.isReadable) throw DecoderException("${frame.readableBytes()} trailing bytes after ${packet.javaClass.simpleName}")
        return packet
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PacketCodec::class.java)
    }
}
