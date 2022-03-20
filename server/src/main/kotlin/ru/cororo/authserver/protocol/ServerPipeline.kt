package ru.cororo.authserver.protocol

import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import ru.cororo.authserver.logger
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCrypt
import ru.cororo.authserver.protocol.util.*
import ru.cororo.authserver.session.MinecraftSession
import java.io.ByteArrayOutputStream

val readingConnectionCoroutine = CoroutineName("ReadingConnection")
val writingConnectionCoroutine = CoroutineName("WritingConnection")

fun CoroutineScope.startReadingConnection(
    session: MinecraftSession,
    inputPipeline: Pipeline<Any, MinecraftSession>
): Job {
    return launch(readingConnectionCoroutine) {
        session.protocol.state = MinecraftProtocol.ProtocolState.HANDSHAKE
        while (true) {
            try {
                val input = session.connection.input
                if (session.secret == null) {
                    val packetSize = input.readVarInt()
                    val rawPacket = input.readPacket(packetSize)
                    val packetId = rawPacket.readVarInt()
                    println("received packet with id $packetId")
                    val packetCodec = session.protocol.getCodec<Packet>(packetId, PacketBound.SERVER)
                    val packet = packetCodec.read(rawPacket)
                    inputPipeline.execute(session, packet)
                    if (rawPacket.remaining > 0) {
                        logger.warn("[$session] Packet didn't read fully - it has ${rawPacket.remaining} more")
                    }
                } else {
                    val crypt = PacketCrypt(session.secret!!)
                    val bytes = crypt.decode(input.readPacket(input.availableForRead))
                    val transformedInput = ByteChannel()
                    transformedInput.writeFully(bytes)
                    val packetSize = transformedInput.readVarInt()
                    val rawPacket = transformedInput.readPacket(packetSize)
                    val packetId = rawPacket.readVarInt()
                    println("received packet with id $packetId")
                    val packetCodec = session.protocol.getCodec<Packet>(packetId, PacketBound.SERVER)
                    val packet = packetCodec.read(rawPacket)
                    inputPipeline.execute(session, packet)
                    if (rawPacket.remaining > 0) {
                        logger.warn("[$session] Packet didn't read fully - it has ${rawPacket.remaining} more")
                    }
                }

//                if (allBytes.isEmpty) {
//                    if (!input.isClosedForRead) {
//                        delay(50L)
//                        continue
//                    } else {
//                        return@launch
//                    }
//                }
//
//                val bytes =
//                    if (session.secret != null) {
//
//                    } else {
//                        allBytes.readBytes()
//                    }
//                val data = ByteReadPacket(bytes)
//                val packetSize = data.readVarInt()

//                if (packetSize > data.remaining) {
//                    logger.error("Packet size ($packetSize) > available data size (${data.remaining})")
//                }
//                val rawPacket = ByteReadPacket(data.readBytes(packetSize))

            } catch (ex: IllegalArgumentException) {
                logger.error(ex.message)
            } catch (ex: ClosedReceiveChannelException) {
                logger.debug(
                    "closed receive channel${
                        if (ex.message != null && ex.message != "EOF while 1 bytes expected") ": " + ex.message
                        else ""
                    }"
                )
                return@launch
            } catch (ex: Exception) {
                logger.error("Exception when reading or handling packet: $ex")
                return@launch
            }
        }
    }
}

fun CoroutineScope.startWritingConnection(
    session: MinecraftSession,
    outputPipeline: Pipeline<Any, MinecraftSession>
): Job {
    return launch(writingConnectionCoroutine) {
        while (true) {
            val output = session.connection.output
            val packet = session.sendChannel.receive()
            if (packet !is Packet) continue
            outputPipeline.execute(session, packet)
            val codec = session.protocol.getCodec(packet, PacketBound.CLIENT)
            val packetId = session.protocol.getPacketId(codec, PacketBound.CLIENT)
            println("sending packet with id $packetId")
            if (session.protocol.getCodec<Packet>(packetId, PacketBound.CLIENT) != codec) {
                logger.error("Wrong packet sending!")
                break
            }
            val bytePacket = buildPacket {
                val encodedPacket = buildPacket {
                    writeVarInt(packetId)
                    codec.write(this, packet)
                }
                writeVarInt(encodedPacket.remaining.toInt())
                writePacket(encodedPacket)
            }
            try {
                if (session.secret != null) {
                    val crypt = PacketCrypt(session.secret!!)
                    val encoded = crypt.encode(bytePacket)
                    output.writePacket(ByteReadPacket(encoded))
                } else {
                    output.writePacket(bytePacket)
                }

                output.flush()
            } catch (ex: Exception) {
                logger.error("Got exception when write: $ex")
                if (!output.isClosedForWrite) {
                    output.close()
                }
            }
        }
    }
}