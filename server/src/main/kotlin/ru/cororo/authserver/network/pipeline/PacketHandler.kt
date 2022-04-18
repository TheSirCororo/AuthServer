package ru.cororo.authserver.network.pipeline

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.session.MinecraftSession

class PacketHandler(val session: MinecraftSession) : ChannelDuplexHandler() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        runBlocking {
            if (msg is Packet) {
                session.protocol.inboundPipeline.execute(session, msg)
            }
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        runBlocking {
            if (msg is Packet) {
                session.protocol.outboundPipeline.execute(session, msg)
            }
        }
        ctx.write(msg)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        AuthServerImpl.onDisconnect(session, cause, ctx.channel())
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        AuthServerImpl.onDisconnect(session, null, ctx.channel())
    }
}