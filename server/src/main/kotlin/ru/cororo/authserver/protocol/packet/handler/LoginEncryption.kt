package ru.cororo.authserver.protocol.packet.handler

import com.google.gson.Gson
import io.ktor.client.request.*
import kotlinx.coroutines.launch
import net.benwoodworth.knbt.*
import net.kyori.adventure.text.Component
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.logger
import ru.cororo.authserver.player.GameProfile
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.game.ClientboundGameDisconnectPacket
import ru.cororo.authserver.protocol.packet.clientbound.game.ClientboundGameJoinPacket
import ru.cororo.authserver.protocol.packet.clientbound.login.ClientboundLoginDisconnectPacket
import ru.cororo.authserver.protocol.packet.clientbound.login.ClientboundLoginSuccessPacket
import ru.cororo.authserver.protocol.packet.serverbound.login.ServerboundLoginEncryptionResponsePacket
import ru.cororo.authserver.protocol.utils.*
import ru.cororo.authserver.session.MinecraftSession
import java.math.BigInteger
import java.net.URLEncoder
import java.util.*

object LoginEncryption : PacketListener<ServerboundLoginEncryptionResponsePacket> {
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

    override fun handle(packet: ServerboundLoginEncryptionResponsePacket, protocolable: Protocolable) {
        check(protocolable is MinecraftSession) { "Serverbound packet cannot was sent from server" }

        AuthServerImpl.launch {
            val encodedSecret = packet.secret
            val encodedToken = packet.verifyToken
            val secret = decryptByteToSecretKey(AuthServerImpl.keys.second, encodedSecret)
            val verifyToken = decryptVerifyToken(encodedToken)
            if (!Arrays.equals(verifyToken, verifyTokens[protocolable])) {
                protocolable.sendPacket(
                    ClientboundLoginDisconnectPacket(
                        Component.text("Failed authorization")
                    )
                )
                return@launch
            }

            val digestedData = digestData("", AuthServerImpl.keys.first, secret)
            if (digestedData == null) {
                protocolable.sendPacket(
                    ClientboundLoginDisconnectPacket(
                        Component.text("Failed authorization")
                    )
                )
                return@launch
            }

            val serverId = BigInteger(digestedData).toString(16)
            val username = URLEncoder.encode(protocolable.username, Charsets.UTF_8)

            val client = AuthServerImpl.sessionClient
            val response: String =
                client.get("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=$username&serverId=$serverId")
            val gameProfile = Gson().fromJson(response, GameProfile::class.java)
            val uuid = UUID.fromString(
                gameProfile.id.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(),
                    "$1-$2-$3-$4-$5"
                )
            )
            protocolable.sendPacket(
                ClientboundLoginSuccessPacket(
                    uuid,
                    username
                )
            )
            protocolable.secret = secret

            protocolable.protocol.state = MinecraftProtocol.ProtocolState.GAME
            protocolable.sendPacket(
                ClientboundGameDisconnectPacket(
                    Component.text("Test")
                )
            )
            protocolable.sendPacket(
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
//        protocolable.sendPacket(
//            ClientboundGamePluginMessagePacket(
//                "minecraft:brand",
//                byteArrayOf()
//            )
//        )

            logger.info("Player with name ${protocolable.username} and UUID $uuid has joined from ${protocolable.address}")
        }
    }

    fun decryptVerifyToken(token: ByteArray) = decryptUsingKey(AuthServerImpl.keys.second, token)
}