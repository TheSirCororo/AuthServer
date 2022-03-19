package ru.cororo.authserver.protocol.packet.handler

import com.google.gson.Gson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.encodeToByteArray
import net.benwoodworth.knbt.*
import ru.cororo.authserver.AuthServer
import ru.cororo.authserver.player.GameProfile
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.clientbound.*
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginEncryptionResponsePacket
import ru.cororo.authserver.protocol.utils.*
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.velocity.logger
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.URLEncoder
import java.util.*
import kotlin.text.toByteArray

object LoginEncryption : PacketHandler<ServerboundLoginEncryptionResponsePacket> {
    override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java
    val DIMENSION = buildNbtCompound("") {
        put("piglin_safe", 0x00.toByte())
        put("natural", 0x00.toByte())
        put("ambient_light", 0.5f)
        put("fixed_time", 6000L)
        put("infiniburn", "")
        put("respawn_anchor_works", 0x00.toByte())
        put("has_skylight", 0x00.toByte())
        put("bed_works", 0x00.toByte())
        put("effects", "minecraft:overworld")
        put("has_raids", 0x00.toByte())
        put("min_y", 1)
        put("height", 256)
        put("logical_height", 256)
        put("coordinate_scale", 1)
        put("ultrawarm", 0x00.toByte())
        put("has_ceiling", 0x00.toByte())
    }

    val BIOME = buildNbtCompound {
        put("type", "minecraft:worldgen/biome")
        putNbtList("value") {
            addNbtCompound {
                put("name", "minecraft:void")
                put("id", 0)
                putNbtCompound("element") {
                    put("precipitation", "none")
                    put("depth", 0.0f)
                    put("temperature", 0.0f)
                    put("scale", 0.0f)
                    put("downfall", 0.0f)
                    put("category", "none")
                    putNbtCompound("effects") {
                        put("sky_color", 8364543)
                        put("water_fog_color", 8364543)
                        put("fog_color", 8364543)
                        put("water_color", 8364543)
                    }
                }
            }
        }
    }

    val DIMENSION_CODEC = buildNbtCompound("") {
        putNbtCompound("minecraft:dimension_type") {
            put("type", "minecraft:dimension_type")
            putNbtList("value") {
                addNbtCompound {
                    put("name", "minecraft:overworld")
                    put("id", 0)
                    put("element", DIMENSION)
                }
            }
        }
        put("minecraft:worldgen/biome", BIOME)
    }

    override suspend fun handle(session: MinecraftSession, packet: ServerboundLoginEncryptionResponsePacket) {
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
        val response: String =
            client.get("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=$username&serverId=$serverId")
        val gameProfile = Gson().fromJson(response, GameProfile::class.java)
        val uuid = UUID.fromString(
            gameProfile.id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(),
                "$1-$2-$3-$4-$5"
            )
        )
        session.sendPacket(
            ClientboundLoginSuccessPacket(
                uuid,
                username
            )
        )
        session.protocol.state = MinecraftProtocol.ProtocolState.GAME
        session.sendPacket(
            ClientboundGameJoinPacket(
                (0..100).random(),
                false,
                1,
                -1,
                arrayOf("minecraft:world"),
                DIMENSION_CODEC,
                DIMENSION,
                "minecraft:world",
                BigInteger(112312.toString().toByteArray().sha256().copyOfRange(0, 7)).longValueExact(),
                20,
                8,
                debugInfo = false,
                respawnScreen = false,
                debug = false,
                flat = false
            )
        )
//        session.sendPacket(
//            ClientboundGamePluginMessagePacket(
//                "minecraft:brand",
//                byteArrayOf()
//            )
//        )

        logger.info("Player with name ${session.username} and UUID $uuid has joined from ${session.address}")
    }

    fun decryptVerifyToken(token: ByteArray) = decryptUsingKey(AuthServer.keys.second, token)
}