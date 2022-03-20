package ru.cororo.authserver.player

import ru.cororo.authserver.AuthServerImpl.address
import ru.cororo.authserver.logger
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersions
import ru.cororo.authserver.protocol.packet.clientbound.game.*
import ru.cororo.authserver.protocol.packet.handler.LoginEncryption
import ru.cororo.authserver.protocol.util.sha256
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.session.Session
import ru.cororo.authserver.world.Difficulty
import ru.cororo.authserver.world.Position
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

data class MinecraftPlayer(private val _session: Session, private val _position: Position) : Player {
    override val username: String = _session.username ?: ""

    override val gameProfile: GameProfile =
        _session.playerProfile ?: GameProfile(UUID(0, 0).toString(), "", mutableListOf())

    override val session: Session? get() = if (_session.isActive) _session else null

    override val uniqueId: UUID = _session.uniqueId

    override var position: Position = _position
        get() = field.copy()

    override val remoteAddress get() = _session.address

    init {
        (_session as MinecraftSession)._player = this
    }

    fun joinPlayer() {
        if (session != null) {
            session!!.sendPacket(
                if (session!!.protocolVersion.raw >= ProtocolVersions.v1_18.raw) {
                    ClientboundGameJoinPacket(
                        (0..100).random(),
                        false,
                        1,
                        -1,
                        arrayOf("minecraft:world"),
                        LoginEncryption.DIMENSION_CODEC,
                        LoginEncryption.DIMENSION,
                        "minecraft:world",
                        ByteBuffer.wrap(
                            ByteBuffer.allocate(8).putLong(1212311L).array().sha256().toList().subList(0, 9)
                                .toByteArray()
                        ).long,
                        20,
                        8,
                        debugInfo = false,
                        respawnScreen = false,
                        debug = false,
                        flat = false,
                        8
                    )
                } else {
                    ClientboundGameJoinPacket117(
                        (0..100).random(),
                        false,
                        1,
                        -1,
                        arrayOf("minecraft:world"),
                        LoginEncryption.DIMENSION_CODEC,
                        LoginEncryption.DIMENSION,
                        "minecraft:world",
                        ByteBuffer.wrap(
                            ByteBuffer.allocate(8).putLong(1212311L).array().sha256().toList().subList(0, 9)
                                .toByteArray()
                        ).long,
                        20,
                        8,
                        debugInfo = false,
                        respawnScreen = false,
                        debug = false,
                        flat = false
                    )
                }
            )

//            session!!.sendPacket(
//                ClientboundGamePluginMessagePacket(
//                    "minecraft:brand",
//                    "AuthServer".toByteArray()
//                )
//            )
//
//            session!!.sendPacket(
//                ClientboundGamePlayerAbilitiesPacket(
//                    0x08,
//                    1.0f,
//                    0.1f
//                )
//            )

            logger.info("Player with name $username and UUID $uniqueId has joined from $remoteAddress")
        }
    }

    fun quitPlayer() {

    }

    override fun toString(): String {
        return "MinecraftPlayer(name=$username, uniqueId=$uniqueId, address=$remoteAddress)"
    }
}