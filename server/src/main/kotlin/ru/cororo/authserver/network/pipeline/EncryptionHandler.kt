package ru.cororo.authserver.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.protocol.packet.PacketCrypt
import ru.cororo.authserver.session.MinecraftSession

class EncryptionHandler(val session: MinecraftSession) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (session.secret != null) {
            val crypt = PacketCrypt(session.secret!!)
            crypt.encode(Unpooled.copiedBuffer(msg), out)
        } else {
            out.add(Unpooled.copiedBuffer(msg))
        }
    }

    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        if (session.secret != null) {
            val crypt = PacketCrypt(session.secret!!)
            crypt.decode(Unpooled.copiedBuffer(`in`), out)
        } else {
            out.add(Unpooled.copiedBuffer(`in`))
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        AuthServerImpl.onDisconnect(session, cause, ctx.channel())
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        AuthServerImpl.onDisconnect(session, null, ctx.channel())
    }
}