package ru.cororo.authserver.util

import ru.cororo.authserver.session.MinecraftSession
import java.security.*
import javax.crypto.*
import javax.crypto.spec.SecretKeySpec


val verifyTokens = mutableMapOf<MinecraftSession, ByteArray>()

fun generateRSAKeyPair(): Pair<PublicKey, PrivateKey> {
    val keyGen = KeyPairGenerator.getInstance("RSA")
    keyGen.initialize(1024)
    val keypair = keyGen.genKeyPair()
    val privateKey = keypair.private
    val publicKey = keypair.public
    return publicKey to privateKey
}

fun decryptBytesToSecretKey(privateKey: PrivateKey, bytes: ByteArray): SecretKey {
    val cipher = Cipher.getInstance("RSA")
    cipher.init(Cipher.DECRYPT_MODE, privateKey)
    return SecretKeySpec(cipher.doFinal(bytes), "AES")
}

fun decryptBytesToVerifyToken(privateKey: PrivateKey, bytes: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("RSA")
    cipher.init(Cipher.DECRYPT_MODE, privateKey)
    return cipher.doFinal(bytes)
}

fun generateVerifyToken(session: MinecraftSession): String {
    val token = generate4CharsRandomString()
    verifyTokens[session] = token.toByteArray()
    return token
}

fun generate4CharsRandomString(): String {
    val chars = 'A'..'z'
    val stringBuilder = StringBuilder()
    repeat(4) {
        stringBuilder.append(chars.random())
    }

    return stringBuilder.toString()
}

fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)