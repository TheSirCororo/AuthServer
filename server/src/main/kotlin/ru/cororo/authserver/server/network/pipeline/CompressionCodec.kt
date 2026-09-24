package ru.cororo.authserver.server.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.MessageToMessageCodec
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeVarInt
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Largest decompressed packet the vanilla client accepts. */
private const val MAX_UNCOMPRESSED_SIZE = 8 * 1024 * 1024

/**
 * zlib compression enabled by `Set Compression`: every frame starts with the uncompressed length,
 * or 0 when the packet was below the threshold and is stored as is.
 */
class CompressionCodec(private val threshold: Int) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
    private val deflater = Deflater()
    private val inflater = Inflater()

    override fun encode(ctx: ChannelHandlerContext, message: ByteBuf, out: MutableList<Any>) {
        val length = message.readableBytes()
        val frame = ctx.alloc().buffer(length + 5)
        if (length < threshold) {
            frame.writeVarInt(0)
            frame.writeBytes(message)
        } else {
            frame.writeVarInt(length)
            val input = ByteArray(length).also(message::readBytes)
            deflater.setInput(input)
            deflater.finish()
            val chunk = ByteArray(8192)
            while (!deflater.finished()) {
                val written = deflater.deflate(chunk)
                frame.writeBytes(chunk, 0, written)
            }
            deflater.reset()
        }
        out.add(frame)
    }

    override fun decode(ctx: ChannelHandlerContext, message: ByteBuf, out: MutableList<Any>) {
        val length = message.readVarInt()
        if (length == 0) {
            out.add(message.retain())
            return
        }
        if (length < threshold) throw DecoderException("Compressed packet of $length bytes is below the threshold")
        if (length > MAX_UNCOMPRESSED_SIZE) throw DecoderException("Packet of $length bytes is too large")
        inflater.setInput(ByteArray(message.readableBytes()).also(message::readBytes))
        val result = ByteArray(length)
        val inflated = inflater.inflate(result)
        inflater.reset()
        if (inflated != length) throw DecoderException("Declared $length bytes but inflated $inflated")
        out.add(ctx.alloc().buffer(length).writeBytes(result))
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        deflater.end()
        inflater.end()
    }
}
