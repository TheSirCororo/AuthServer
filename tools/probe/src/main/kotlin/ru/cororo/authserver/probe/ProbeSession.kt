package ru.cororo.authserver.probe

import io.netty.buffer.Unpooled
import ru.cororo.authserver.protocol.MinecraftPackets
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_11
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_13
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_9
import ru.cororo.authserver.protocol.packet.*
import ru.cororo.authserver.protocol.packet.play.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Logs into a vanilla server as an offline player, behaves like a minimal client and records every clientbound frame.
 * The player should be an operator so the probe can trigger titles, action bars and boss bars.
 */
class ProbeSession(
    private val version: ProtocolVersion,
    private val host: String,
    private val port: Int,
    private val output: Path,
    private val playSeconds: Long,
    private val username: String = USERNAME,
    /** Commands to run once in the world; the default set makes vanilla send titles, boss bars and chat. */
    private val customCommands: List<String>? = null,
) {
    private val frames = mutableListOf<Frame>()

    fun run() {
        Files.createDirectories(output)
        ProbeConnection(host, port, version).use { connection ->
            connection.send(HandshakePacket(version.protocol, host, port, HandshakePacket.Intent.LOGIN))
            connection.state = ProtocolState.LOGIN
            connection.send(LoginStartPacket(username, offlineUuid(username)))
            login(connection)
            if (connection.state == ProtocolState.CONFIGURATION) configure(connection)
            play(connection)
        }
        write()
    }

    private fun login(connection: ProbeConnection) {
        while (true) {
            val frame = record(connection.read())
            when (val packet = decode(connection, frame)) {
                is SetCompressionPacket -> connection.enableCompression(packet.threshold)
                is LoginPluginRequestPacket -> connection.send(LoginPluginResponsePacket(packet.messageId, null))
                is LoginDisconnectPacket -> error("Disconnected during login: ${packet.reason}")
                is EncryptionRequestPacket -> error("Server is in online mode")
                is LoginSuccessPacket -> {
                    if (version >= MINECRAFT_1_20_2) {
                        connection.send(LoginAcknowledgedPacket)
                        connection.state = ProtocolState.CONFIGURATION
                    } else {
                        connection.state = ProtocolState.PLAY
                    }
                    return
                }
                else -> Unit
            }
        }
    }

    private fun configure(connection: ProbeConnection) {
        while (true) {
            val frame = record(connection.read())
            when (val packet = decode(connection, frame)) {
                is ClientboundKnownPacksPacket -> connection.send(ServerboundKnownPacksPacket(emptyList()))
                is ClientboundKeepAlivePacket -> connection.send(ServerboundKeepAlivePacket(packet.id))
                is PingPacket -> connection.send(PongPacket(packet.id))
                is DisconnectPacket -> error("Disconnected during configuration: ${packet.reason}")
                is FinishConfigurationPacket -> {
                    connection.send(FinishConfigurationAckPacket)
                    connection.state = ProtocolState.PLAY
                    return
                }
                else -> Unit
            }
        }
    }

    private fun play(connection: ProbeConnection) {
        val deadline = System.nanoTime() + playSeconds * 1_000_000_000
        val commands = ArrayDeque(customCommands ?: commands())
        var nextCommand = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            sendDueCommand(connection, commands, nextCommand)?.let { nextCommand = it }
            // Poll instead of blocking so scheduled commands go out even while the server is quiet.
            if (!connection.hasPendingData()) {
                Thread.sleep(20)
                continue
            }
            val frame = record(connection.read())
            when (val packet = decode(connection, frame)) {
                is ClientboundKeepAlivePacket -> connection.send(ServerboundKeepAlivePacket(packet.id))
                is PlayerPositionPacket -> {
                    if (version >= MINECRAFT_1_9) {
                        connection.send(TeleportConfirmPacket(packet.teleportId, packet.x, packet.y, packet.z, packet.yaw, packet.pitch))
                    }
                    connection.send(MovePositionRotationPacket(packet.x, packet.y, packet.z, packet.yaw, packet.pitch, false))
                }
                is DisconnectPacket -> error("Disconnected during play: ${plain(packet.reason)}")
                is SystemChatPacket -> println("[chat] ${plain(packet.message)}")
                else -> Unit
            }
        }
    }

    /** Sends the next command when due; `wait <seconds>` entries only delay the following command. */
    private fun sendDueCommand(connection: ProbeConnection, commands: ArrayDeque<String>, due: Long): Long? {
        if (commands.isEmpty() || System.nanoTime() < due) return null
        val command = commands.removeFirst()
        if (command.startsWith("wait ")) return System.nanoTime() + (command.removePrefix("wait ").toDouble() * 1e9).toLong()
        println("[command] /$command")
        connection.send(if (version >= MINECRAFT_1_19) ChatCommandPacket(command) else ChatPacket("/$command"))
        return System.nanoTime() + 300_000_000L
    }

    /** Commands that make the server send packets it would not send on a plain join. */
    private fun commands(): List<String> = buildList {
        add("say probe-chat")
        add("""title $USERNAME title {"text":"probe-title"}""")
        add("""title $USERNAME subtitle {"text":"probe-subtitle"}""")
        add("title $USERNAME times 1 2 3")
        if (version >= MINECRAFT_1_11) add("""title $USERNAME actionbar {"text":"probe-actionbar"}""")
        if (version >= MINECRAFT_1_13) {
            add("""bossbar add probe:bar {"text":"probe-bossbar"}""")
            add("bossbar set probe:bar players $USERNAME")
        }
        add("title $USERNAME clear")
        add(giveNamedCompass())
    }

    /** A compass with a name and lore, so vanilla sends a non-trivial item in a set-slot packet. */
    private fun giveNamedCompass(): String = when {
        version < ProtocolVersion.MINECRAFT_1_13 -> """give $USERNAME minecraft:compass 1 0 {display:{Name:"Probe Item",Lore:["Probe Lore"]}}"""
        // Single-quoted SNBT strings exist only since 1.14.
        version < ProtocolVersion.MINECRAFT_1_14 ->
            """give $USERNAME minecraft:compass{display:{Name:"{\"text\":\"Probe Item\"}",Lore:["Probe Lore"]}} 1"""
        version < ProtocolVersion.MINECRAFT_1_20_5 ->
            """give $USERNAME minecraft:compass{display:{Name:'{"text":"Probe Item"}',Lore:['{"text":"Probe Lore"}']}} 1"""
        version < ProtocolVersion.MINECRAFT_1_21_5 ->
            """give $USERNAME minecraft:compass[custom_name='{"text":"Probe Item"}',lore=['{"text":"Probe Lore"}']] 1"""
        else -> """give $USERNAME minecraft:compass[custom_name="Probe Item",lore=["Probe Lore"]] 1"""
    }

    private fun plain(component: net.kyori.adventure.text.Component) =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)

    private fun record(frame: Frame): Frame = frame.also(frames::add)

    private fun decode(connection: ProbeConnection, frame: Frame): Any? {
        val table = MinecraftPackets.registry.table(version, connection.state, PacketDirection.CLIENTBOUND)
        val codec = table.codec(frame.id) ?: return null
        val buffer = Unpooled.wrappedBuffer(frame.body)
        return runCatching { codec.decode(buffer, version) }.getOrNull()
    }

    private fun write() {
        val index = StringBuilder()
        frames.forEachIndexed { number, frame ->
            val name = "%04d_%s_%02x.bin".format(number, frame.state.key, frame.id)
            Files.write(output.resolve(name), frame.body)
            index.append(name).append('\n')
        }
        Files.writeString(output.resolve("index.txt"), index)
        println("Recorded ${frames.size} frames for $version into $output")
    }

    companion object {
        const val USERNAME = "Probe"

        fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())
    }
}
