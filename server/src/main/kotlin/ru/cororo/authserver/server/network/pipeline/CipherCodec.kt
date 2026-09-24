package ru.cororo.authserver.server.network.pipeline

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/** AES/CFB8 stream encryption keyed by the login shared secret, one continuous cipher per direction. */
class CipherCodec(secret: SecretKey) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
    private val encrypt = cipher(Cipher.ENCRYPT_MODE, secret)
    private val decrypt = cipher(Cipher.DECRYPT_MODE, secret)

    override fun encode(ctx: ChannelHandlerContext, message: ByteBuf, out: MutableList<Any>) = crypt(ctx, encrypt, message, out)

    override fun decode(ctx: ChannelHandlerContext, message: ByteBuf, out: MutableList<Any>) = crypt(ctx, decrypt, message, out)

    private fun crypt(ctx: ChannelHandlerContext, cipher: Cipher, message: ByteBuf, out: MutableList<Any>) {
        val input = ByteArray(message.readableBytes()).also(message::readBytes)
        if (input.isNotEmpty()) out.add(ctx.alloc().buffer(input.size).writeBytes(cipher.update(input)))
    }

    private fun cipher(mode: Int, secret: SecretKey) = Cipher.getInstance("AES/CFB8/NoPadding").apply {
        init(mode, secret, IvParameterSpec(secret.encoded))
    }
}
