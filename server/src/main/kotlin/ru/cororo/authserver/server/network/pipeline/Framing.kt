package ru.cororo.authserver.server.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.MessageToByteEncoder
import ru.cororo.authserver.protocol.buffer.varIntSize
import ru.cororo.authserver.protocol.buffer.writeVarInt

/** Largest frame the vanilla protocol allows (3-byte VarInt length). */
const val MAX_FRAME_SIZE = 2097151

/** Splits the stream into frames prefixed with a VarInt length. */
class FrameDecoder : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        input.markReaderIndex()
        var length = 0
        for (index in 0 until 3) {
            if (!input.isReadable) {
                input.resetReaderIndex()
                return
            }
            val byte = input.readUnsignedByte().toInt()
            length = length or ((byte and 0x7F) shl (index * 7))
            if (byte and 0x80 == 0) {
                if (length !in 1..MAX_FRAME_SIZE) throw CorruptedFrameException("Invalid frame length $length")
                if (input.readableBytes() < length) {
                    input.resetReaderIndex()
                    return
                }
                out.add(input.readRetainedSlice(length))
                return
            }
        }
        throw CorruptedFrameException("Frame length is longer than three bytes")
    }
}

class FrameEncoder : MessageToByteEncoder<ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, message: ByteBuf, out: ByteBuf) {
        val length = message.readableBytes()
        if (length > MAX_FRAME_SIZE) throw CorruptedFrameException("Frame of $length bytes is too large")
        out.ensureWritable(varIntSize(length) + length)
        out.writeVarInt(length)
        out.writeBytes(message)
    }
}
