package ru.cororo.authserver.protocol.packet.handler

import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.bouncycastle.jcajce.provider.asymmetric.rsa.CipherSpi
import ru.cororo.authserver.AuthServer
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginEncryptionResponsePacket
import ru.cororo.authserver.protocol.utils.derToString
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.velocity.logger
import javax.crypto.Cipher

object LoginEncryption : PacketHandler<ServerboundLoginEncryptionResponsePacket> {
    override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java

    override suspend fun handle(session: MinecraftSession, packet: ServerboundLoginEncryptionResponsePacket) {
        logger.info("Verifying $session login...")
        val encodedSecret = packet.secret
        val encodedVerifyToken = packet.verifyToken
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val key = AuthServer.keys.first
        cipher.init(Cipher.DECRYPT_MODE, key)
        val secret = cipher.doFinal(encodedSecret)
        println(String(secret))

        val client = AuthServer.sessionClient
        val response: HttpResponse = client.get("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${session.username}&serverId={serverId}&ip=ip")
    }
}