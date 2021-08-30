package ru.cororo.authserver.protocol.packet.handler

import com.google.gson.Gson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import ru.cororo.authserver.AuthServer
import ru.cororo.authserver.player.GameProfile
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundGameJoinPacket
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundLoginDisconnectPacket
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundLoginSuccessPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginEncryptionResponsePacket
import ru.cororo.authserver.protocol.utils.decryptByteToSecretKey
import ru.cororo.authserver.protocol.utils.decryptUsingKey
import ru.cororo.authserver.protocol.utils.digestData
import ru.cororo.authserver.protocol.utils.verifyTokens
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.velocity.logger
import java.math.BigInteger
import java.net.URLEncoder
import java.util.*

object LoginEncryption : PacketHandler<ServerboundLoginEncryptionResponsePacket> {
    override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java

    override suspend fun handle(session: MinecraftSession, packet: ServerboundLoginEncryptionResponsePacket) {
        logger.info("Verifying $session login...")
        val encodedSecret = packet.secret
        val encodedToken = packet.verifyToken
        val secret = decryptByteToSecretKey(AuthServer.keys.second, encodedSecret)
        val verifyToken = decryptVerifyToken(encodedToken)
        if (!Arrays.equals(verifyToken, verifyTokens[session])) {
            session.sendPacket(
                ClientboundLoginDisconnectPacket(
                    """{"text":"Wrong login!"}"""
                )
            )
            return
        }

        val digestedData = digestData("", AuthServer.keys.first, secret)
        if (digestedData == null) {
            session.sendPacket(
                ClientboundLoginDisconnectPacket(
                    """{"text":"Wrong login!"}"""
                )
            )
            return
        }

        val serverId = BigInteger(digestedData).toString(16)
        val username = URLEncoder.encode(session.username, Charsets.UTF_8)

        val client = AuthServer.sessionClient
        val response: String = client.get("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=$username&serverId=$serverId")
        val gameProfile = Gson().fromJson(response, GameProfile::class.java)
        val uuid = UUID.fromString(gameProfile.id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(), "$1-$2-$3-$4-$5"))
        logger.info("Received game profile from response: $gameProfile")
        session.sendPacket(
            ClientboundLoginSuccessPacket(
                uuid, username
            )
        )
        session.protocol.state = MinecraftProtocol.ProtocolState.GAME
        session.sendPacket(
            ClientboundGameJoinPacket(
                1,
                false,
                1,
                1,
                1,
                arrayOf("minecraft:world"),
                "{}",
                "{}",
                "minecraft:world",
                1,
                20,
                8,
                debugInfo = false,
                respawnScreen = false,
                debug = false,
                flat = false
            )
        )

        logger.info("Player with name ${session.username} and UUID $uuid has joined from ${session.address}")
    }

    fun decryptVerifyToken(token: ByteArray) = decryptUsingKey(AuthServer.keys.second, token)
}