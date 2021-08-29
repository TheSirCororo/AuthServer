package ru.cororo.authserver.protocol

import io.ktor.network.selector.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import ru.cororo.authserver.protocol.packet.handler.HandshakePacketHandler
import ru.cororo.authserver.protocol.packet.handler.PacketHandler
import ru.cororo.authserver.protocol.packet.handler.StatusPingHandler
import ru.cororo.authserver.protocol.packet.handler.StatusRequestHandler
import ru.cororo.authserver.protocol.utils.readVarInt
import ru.cororo.authserver.protocol.utils.writeVarInt
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.velocity.logger

val readingConnectionCoroutine = CoroutineName("ReadingConnection")
val writingConnectionCoroutine = CoroutineName("WritingConnection")

/**
 * Used https://github.com/KarbonPowered/Karbon/blob/rewrite-v2/src/commonMain/kotlin/com/karbonpowered/vanilla/network/ServerPipeline.kt to make this function
 */
fun CoroutineScope.startReadingConnection(
    session: MinecraftSession,
    inputPipeline: Pipeline<Any, MinecraftSession>
): Job {
    return launch(readingConnectionCoroutine) {
        while (true) {
            try {
                val input = session.connection.input
                val length = input.readVarInt()
                val rawPacket = input.readPacket(length)
                println("$length ${input.readVarInt()}")
                val packetId = input.readVarInt()
                val packetCodec = session.protocol.getCodec<Any>(packetId, MinecraftProtocol.Bound.SERVER)
                val packet = packetCodec.read(rawPacket)
                inputPipeline.execute(session, packet)
            } catch (ex: IllegalArgumentException) {
                logger.error(ex.message)
            } catch (ex: ClosedReceiveChannelException) {
                return@launch
            } catch (ex: Exception) {
                logger.error("Exception when reading packet ${ex.localizedMessage}")
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
            outputPipeline.execute(session, packet)
            val codec = session.protocol.getCodec(packet, MinecraftProtocol.Bound.CLIENT)
            val packetId = session.protocol.getPacketId(codec, MinecraftProtocol.Bound.CLIENT)
            output.writePacket {
                val encodedPacket = buildPacket {
                    writeVarInt(packetId)
                    codec.write(this, packet)
                }
                writeVarInt(encodedPacket.remaining.toInt())
                writePacket(encodedPacket)
            }
            output.flush()
        }
    }
}