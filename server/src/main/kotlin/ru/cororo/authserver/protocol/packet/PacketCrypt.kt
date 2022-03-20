package ru.cororo.authserver.protocol.packet

import io.ktor.utils.io.core.*
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.ShortBufferException
import javax.crypto.spec.IvParameterSpec

class PacketCrypt(sharedSecret: SecretKey) {
    private val encodeBuf = PacketCryptBuffer(Cipher.ENCRYPT_MODE, sharedSecret)
    private val decodeBuf = PacketCryptBuffer(Cipher.DECRYPT_MODE, sharedSecret)

    fun encode(msg: ByteReadPacket): ByteArray {
        return encodeBuf.crypt(msg)
    }

    fun decode(msg: ByteReadPacket): ByteArray {
        return decodeBuf.crypt(msg)
    }
}

class PacketCryptBuffer internal constructor(
    private val mode: Int,
    private val sharedSecret: SecretKey
) {
    private val cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
        init(mode, sharedSecret, IvParameterSpec(sharedSecret.encoded))
    }

    fun crypt(msg: ByteReadPacket): ByteArray {
        val outBuffer = ByteArrayOutputStream()

        try {
            outBuffer.writeBytes(cipher.update(msg.readBytes()))
        } catch (e: ShortBufferException) {
            throw AssertionError("Encryption buffer was too short", e)
        }

        return outBuffer.toByteArray()
    }
}