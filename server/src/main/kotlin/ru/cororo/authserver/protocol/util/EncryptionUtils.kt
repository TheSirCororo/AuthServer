package ru.cororo.authserver.protocol.util

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.util.io.pem.PemObject
import ru.cororo.authserver.session.MinecraftSession
import java.io.ByteArrayInputStream
import java.io.UnsupportedEncodingException
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

private fun ByteArray.toDerObject(): ASN1Primitive {
    val inStream = ByteArrayInputStream(this)
    val asnInputStream = ASN1InputStream(inStream)

    return asnInputStream.readObject()
}

private fun Key.getPemObject(): PemObject {
    val type = if (this is PublicKey) {
        "PUBLIC KEY"
    } else {
        "PRIVATE KEY"
    }
    return PemObject(type, encoded)
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