package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.EncodeOnlyCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.buffer.writeIdentifier
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeVarInt

/** Brigadier string argument flavours. */
enum class StringArgumentMode { SINGLE_WORD, QUOTABLE_PHRASE, GREEDY_PHRASE }

/** A command tree node: literals and string arguments cover every auth command. */
sealed interface CommandNode {
    val children: List<CommandNode>
    val executable: Boolean

    data class Literal(
        val name: String,
        override val children: List<CommandNode> = emptyList(),
        override val executable: Boolean = false,
    ) : CommandNode

    data class StringArgument(
        val name: String,
        val mode: StringArgumentMode = StringArgumentMode.SINGLE_WORD,
        override val children: List<CommandNode> = emptyList(),
        override val executable: Boolean = false,
    ) : CommandNode
}

/**
 * `Declare Commands` (1.13+). The client uses it for syntax highlighting and completion.
 *
 * @property stringParserId network ID of `brigadier:string` in the `command_argument_type` registry (1.19+)
 */
data class CommandsPacket(val commands: List<CommandNode>, val stringParserId: Int) : ClientboundPacket {
    companion object Codec : EncodeOnlyCodec<CommandsPacket> {
        private const val TYPE_ROOT = 0
        private const val TYPE_LITERAL = 1
        private const val TYPE_ARGUMENT = 2
        private const val FLAG_EXECUTABLE = 0x04

        override fun encode(buffer: ByteBuf, packet: CommandsPacket, version: ProtocolVersion) {
            // Breadth-first flattening; the root (null) is index 0.
            val nodes = mutableListOf<CommandNode?>(null)
            val children = mutableListOf<MutableList<Int>>(mutableListOf())
            val queue = ArrayDeque(listOf(0 to packet.commands))
            while (queue.isNotEmpty()) {
                val (parent, parentChildren) = queue.removeFirst()
                for (child in parentChildren) {
                    nodes += child
                    children += mutableListOf<Int>()
                    children[parent] += nodes.lastIndex
                    queue += nodes.lastIndex to child.children
                }
            }

            buffer.writeVarInt(nodes.size)
            nodes.forEachIndexed { index, node ->
                val type = when (node) {
                    null -> TYPE_ROOT
                    is CommandNode.Literal -> TYPE_LITERAL
                    is CommandNode.StringArgument -> TYPE_ARGUMENT
                }
                buffer.writeByte(type or if (node?.executable == true) FLAG_EXECUTABLE else 0)
                buffer.writeVarInt(children[index].size)
                children[index].forEach(buffer::writeVarInt)
                when (node) {
                    null -> Unit
                    is CommandNode.Literal -> buffer.writeString(node.name)
                    is CommandNode.StringArgument -> {
                        buffer.writeString(node.name)
                        if (version >= MINECRAFT_1_19) buffer.writeVarInt(packet.stringParserId)
                        else buffer.writeIdentifier("brigadier:string")
                        buffer.writeVarInt(node.mode.ordinal)
                    }
                }
            }
            buffer.writeVarInt(0) // root index
        }
    }
}
