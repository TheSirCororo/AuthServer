package ru.cororo.authserver.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import ru.cororo.authserver.util.readVarInt
import ru.cororo.authserver.util.writeVarInt

class FrameHandler : ByteToMessageCodec<ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        out.writeVarInt(msg.readableBytes())
        out.writeBytes(msg)
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val size = msg.readVarInt()
        val buf = ctx.alloc().buffer(size)
        msg.readBytes(buf, size)
        out.add(buf)
    }
}