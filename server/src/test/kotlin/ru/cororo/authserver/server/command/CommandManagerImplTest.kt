package ru.cororo.authserver.server.command

import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.packet.play.CommandNode
import ru.cororo.authserver.protocol.packet.play.StringArgumentMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandManagerImplTest {
    private val commands = CommandManagerImpl { _, _, _ -> Component.empty() }

    @Test
    fun `usage strings become nested word arguments`() {
        val first = assertIs<CommandNode.StringArgument>(commands.arguments("<password> <password>").single())
        assertEquals("password", first.name)
        assertEquals(StringArgumentMode.SINGLE_WORD, first.mode)
        assertEquals("password", assertIs<CommandNode.StringArgument>(first.children.single()).name)
    }

    @Test
    fun `a trailing ellipsis takes the rest of the line`() {
        // Clients check commands against the tree: "/code 123 456" needs a greedy last argument.
        val code = assertIs<CommandNode.StringArgument>(commands.arguments("<code...>").single())
        assertEquals("code", code.name)
        assertEquals(StringArgumentMode.GREEDY_PHRASE, code.mode)
        val action = assertIs<CommandNode.StringArgument>(commands.arguments("[totp|email|confirm|off] [code...]").single())
        assertEquals(StringArgumentMode.SINGLE_WORD, action.mode)
        assertEquals(StringArgumentMode.GREEDY_PHRASE, assertIs<CommandNode.StringArgument>(action.children.single()).mode)
    }
}
