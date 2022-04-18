package ru.cororo.authserver.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.logger
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.util.readVarInt
import ru.cororo.authserver.util.writeVarInt

class PacketCodec(val session: MinecraftSession) : MessageToMessageCodec<ByteBuf, Packet>() {
    override fun encode(ctx: ChannelHandlerContext, msg: Packet, out: MutableList<Any>) {
        val codec = session.protocol.getCodec(msg)
        val packetId = session.protocol.getPacketId(codec, PacketBound.CLIENT)

        val headerBuffer = ctx.alloc().buffer(8)
        headerBuffer.writeVarInt(packetId)

        val packetBuffer = ctx.alloc().buffer()
        codec.write(packetBuffer, msg)

        out.add(Unpooled.wrappedBuffer(headerBuffer, packetBuffer))
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        println(msg.readableBytes())
        val packetId = msg.readVarInt()
        println("[decode] received packet with id $packetId")
        val packetCodec = session.protocol.getCodec<Packet>(packetId, PacketBound.SERVER)
        val packet = packetCodec.read(msg)
        if (msg.readableBytes() > 0) {
            logger.warn("[$session] Packet $packet was not read fully (${msg.readableBytes()} remaining)")
        }
        out.add(packet)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        AuthServerImpl.onDisconnect(session, cause, ctx.channel())
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        AuthServerImpl.onDisconnect(session, null, ctx.channel())
    }
}