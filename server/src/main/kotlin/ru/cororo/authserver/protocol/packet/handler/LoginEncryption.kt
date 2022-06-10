package ru.cororo.authserver.protocol.packet.handler

import com.google.gson.Gson
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.logger
import ru.cororo.authserver.player.GameProfile
import ru.cororo.authserver.player.MinecraftPlayer
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.login.ClientboundLoginDisconnectPacket
import ru.cororo.authserver.protocol.packet.clientbound.login.ClientboundLoginSuccessPacket
import ru.cororo.authserver.protocol.packet.serverbound.login.ServerboundLoginEncryptionResponsePacket
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.util.*
import ru.cororo.authserver.world.Position
import java.math.BigInteger
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*

val MOJANG_SESSION_CHECK_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined"

object LoginEncryption : PacketListener<ServerboundLoginEncryptionResponsePacket> {
    override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java

    val DIMENSION = nbtCompound {
        put("piglin_safe", 0.toByte())
        put("natural", 1.toByte())
        put("ambient_light", 0.0f)
        put("fixed_time", 6000L)
        put("infiniburn", "#minecraft:infiniburn_overworld")
        put("respawn_anchor_works", 0.toByte())
        put("has_skylight", 1.toByte())
        put("bed_works", 0.toByte())
        put("effects", "minecraft:overworld")
        put("has_raids", 0.toByte())
        put("min_y", -64)
        put("height", 384)
        put("logical_height", 384)
        put("coordinate_scale", 1.0)
        put("ultrawarm", 0.toByte())
        put("has_ceiling", 0.toByte())
    }

    val BIOME = nbtCompound {
        put("type", "minecraft:worldgen/biome")
        putNbtList("value") {
            add(nbtCompound("element") {
                put("precipitation", "none")
                put("temperature", 0.5f)
                put("downfall", 0.5f)
                put("category", "none")
                putNbtCompound("effects") {
                    put("sky_color", 8103167)
                    put("water_fog_color", 329011)
                    put("fog_color", 12638463)
                    put("water_color", 4159204)
                    putNbtCompound("mood_sound") {
                        put("block_search_extent", 8)
                        put("offset", 2.0)
                        put("sound", "minecraft:ambient.cave")
                        put("tick_delay", 6000)
                    }
                }
                put("id", 0)
                put("name", "minecraft:void")
            })
        }
    }

    val DIMENSION_CODEC = nbtCompound {
        putNbtCompound("minecraft:dimension_type") {
            put("type", "minecraft:dimension_type")
            putNbtList("value") {
                addNbtCompound {
                    put("element", DIMENSION)
                    put("id", 0)
                    put("name", "minecraft:overworld")
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
            val secret = decryptBytesToSecretKey(AuthServerImpl.keys.second, encodedSecret)
            val verifyToken = decryptBytesToVerifyToken(AuthServerImpl.keys.second, encodedToken)
            if (!Arrays.equals(verifyToken, verifyTokens[protocolable])) {
                protocolable.sendPacket(
                    ClientboundLoginDisconnectPacket(
                        Component.text("Failed authorization")
                    )
                )
                return@launch
            }

            verifyTokens.remove(protocolable)

            val hash: String = try {
                val digest = MessageDigest.getInstance("SHA-1")
                digest.update("".toByteArray(Charsets.UTF_8))
                digest.update(secret.encoded)
                digest.update(AuthServerImpl.keys.first.encoded)

                // BigInteger takes care of sign and leading zeroes
                BigInteger(digest.digest()).toString(16)
            } catch (ex: NoSuchAlgorithmException) {
                logger.error("Algorithm SHA-1 not found!")
                protocolable.sendPacket(
                    ClientboundLoginDisconnectPacket(
                        Component.text("Internal server error!")
                    )
                )
                return@launch
            }

            val username = URLEncoder.encode(protocolable.username, Charsets.UTF_8)

            val client = AuthServerImpl.sessionClient
            val response: String =
                client.get("$MOJANG_SESSION_CHECK_URL?username=$username&serverId=$hash").body()
            val gameProfile = Gson().fromJson(response, GameProfile::class.java)

            protocolable.sendPacket(
                ClientboundLoginSuccessPacket(
                    gameProfile.uniqueId,
                    username
                )
            )

            protocolable.protocol.state = MinecraftProtocol.ProtocolState.GAME
            protocolable.playerProfile = gameProfile
            protocolable.isActive = true
            protocolable.isPlayer = true
            protocolable.secret = secret

            val player = MinecraftPlayer(protocolable, Position(null, 0.0, 0.0, 0.0))
            AuthServerImpl.players.add(player)
            player.joinPlayer()
        }
    }
}