package ru.cororo.authserver.probe

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import ru.cororo.authserver.protocol.MinecraftPackets
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeVarInt
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import java.util.zip.Inflater

/** A raw frame body: packet ID and the bytes after it. */
class Frame(val state: ProtocolState, val id: Int, val body: ByteArray)

/** Blocking, unencrypted client connection speaking one protocol version. */
class ProbeConnection(host: String, port: Int, val version: ProtocolVersion) : AutoCloseable {
    private val socket = Socket(host, port).apply { soTimeout = 30_000 }
    private var input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private var output: OutputStream = socket.getOutputStream()
    private var compressionThreshold = -1

    var state = ProtocolState.HANDSHAKE

    fun enableCompression(threshold: Int) {
        compressionThreshold = threshold
    }

    /** AES/CFB8 with the shared secret as key and IV, as after a vanilla encryption response. */
    fun enableEncryption(secret: SecretKey) {
        fun cipher(mode: Int) = Cipher.getInstance("AES/CFB8/NoPadding").apply { init(mode, secret, IvParameterSpec(secret.encoded)) }
        input = DataInputStream(BufferedInputStream(CipherInputStream(socket.getInputStream(), cipher(Cipher.DECRYPT_MODE))))
        output = CipherOutputStream(socket.getOutputStream(), cipher(Cipher.ENCRYPT_MODE))
    }

    fun send(packet: Packet) {
        val table = MinecraftPackets.registry.table(version, state, PacketDirection.SERVERBOUND)
        val (id, codec) = requireNotNull(table.lookup(packet)) { "${packet.javaClass.simpleName} does not exist in $version $state" }
        val body = Unpooled.buffer()
        try {
            body.writeVarInt(id)
            codec.encode(body, packet, version)
            writeFrame(body)
        } finally {
            body.release()
        }
    }

    /** Whether a frame can be read without waiting for the network. */
    fun hasPendingData(): Boolean = input.available() > 0 || socket.getInputStream().available() > 0

    fun read(): Frame {
        val length = readVarInt()
        val frame = ByteArray(length).also(input::readFully)
        var data = Unpooled.wrappedBuffer(frame)
        if (compressionThreshold >= 0) {
            val uncompressedLength = data.readVarInt()
            if (uncompressedLength > 0) {
                val inflater = Inflater()
                val compressed = ByteArray(data.readableBytes()).also(data::readBytes)
                inflater.setInput(compressed)
                val result = ByteArray(uncompressedLength)
                check(inflater.inflate(result) == uncompressedLength) { "Truncated compressed frame" }
                inflater.end()
                data = Unpooled.wrappedBuffer(result)
            }
        }
        val id = data.readVarInt()
        return Frame(state, id, ByteArray(data.readableBytes()).also(data::readBytes))
    }

    private fun writeFrame(packet: ByteBuf) {
        val payload = Unpooled.buffer()
        try {
            if (compressionThreshold >= 0) {
                if (packet.readableBytes() >= compressionThreshold) {
                    val raw = ByteArray(packet.readableBytes()).also(packet::readBytes)
                    val deflater = Deflater().apply { setInput(raw); finish() }
                    val compressed = ByteArray(raw.size + 64)
                    val size = deflater.deflate(compressed)
                    deflater.end()
                    payload.writeVarInt(raw.size)
                    payload.writeBytes(compressed, 0, size)
                } else {
                    payload.writeVarInt(0)
                    payload.writeBytes(packet)
                }
            } else {
                payload.writeBytes(packet)
            }
            val frame = Unpooled.buffer()
            frame.writeVarInt(payload.readableBytes())
            frame.writeBytes(payload)
            output.write(ByteArray(frame.readableBytes()).also(frame::readBytes))
            output.flush()
            frame.release()
        } finally {
            payload.release()
        }
    }

    private fun readVarInt(): Int {
        var result = 0
        for (index in 0 until 5) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Connection closed")
            result = result or ((byte and 0x7F) shl (7 * index))
            if (byte and 0x80 == 0) return result
        }
        error("VarInt too long")
    }

    override fun close() = socket.close()
}
