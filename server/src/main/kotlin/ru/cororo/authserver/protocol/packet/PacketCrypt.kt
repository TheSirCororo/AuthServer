package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.ShortBufferException
import javax.crypto.spec.IvParameterSpec

class PacketCrypt(sharedSecret: SecretKey) {
    private val encodeBuf = PacketCryptBuffer(Cipher.ENCRYPT_MODE, sharedSecret)
    private val decodeBuf = PacketCryptBuffer(Cipher.DECRYPT_MODE, sharedSecret)

    fun encode(msg: ByteBuf, out: MutableList<Any>) {
        encodeBuf.crypt(msg, out)
    }

    fun decode(msg: ByteBuf, out: MutableList<Any>) {
        decodeBuf.crypt(msg, out)
    }
}

class PacketCryptBuffer internal constructor(
    private val mode: Int,
    private val sharedSecret: SecretKey
) {
    private val cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
        init(mode, sharedSecret, IvParameterSpec(sharedSecret.encoded))
    }

    fun crypt(msg: ByteBuf, out: MutableList<Any>) {
        val outBuffer = Unpooled.buffer()

        try {
            outBuffer.writeBytes(cipher.update(msg.array()))
        } catch (e: ShortBufferException) {
            throw AssertionError("Encryption buffer was too short", e)
        }

        out.add(outBuffer)
    }
}