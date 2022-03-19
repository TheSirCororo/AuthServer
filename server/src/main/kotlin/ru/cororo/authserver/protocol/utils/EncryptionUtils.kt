package ru.cororo.authserver.protocol.utils

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERApplicationSpecific
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.jetbrains.annotations.Nullable
import ru.cororo.authserver.session.MinecraftSession
import java.io.ByteArrayInputStream
import java.io.StringWriter
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

fun Key.toPEMString(): String {
    val writer = StringWriter()
    val pemWriter = PemWriter(writer)
    pemWriter.writeObject(getPemObject())
    pemWriter.flush()
    pemWriter.close()
    return writer.toString()
}

fun ByteArray.derToString(): String {
    val derObject = toDerObject()
    return String((derObject as DERApplicationSpecific).encoded)
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

fun decryptByteToSecretKey(privateKey: PrivateKey, bytes: ByteArray): SecretKey {
    return SecretKeySpec(decryptUsingKey(privateKey, bytes), "AES")
}

fun decryptUsingKey(key: Key, bytes: ByteArray): ByteArray {
    return cipherData(2, key, bytes)
}

private fun cipherData(mode: Int, key: Key, data: ByteArray): ByteArray {
    return setupCipher(mode, key.algorithm, key)!!.doFinal(data)
}

private fun setupCipher(mode: Int, transformation: String, key: Key): Cipher? {
    val cipher4 = Cipher.getInstance(transformation)
    cipher4.init(mode, key)
    return cipher4
}

fun digestData(data: String, publicKey: PublicKey, secretKey: SecretKey): ByteArray? {
    return try {
        digestData("SHA-1", data.toByteArray(charset("ISO_8859_1")), secretKey.encoded, publicKey.encoded)
    } catch (e: UnsupportedEncodingException) {
        null
    }
}

private fun digestData(algorithm: String, vararg data: ByteArray): ByteArray? {
    return try {
        val digest = MessageDigest.getInstance(algorithm)
        for (bytes in data) {
            digest.update(bytes)
        }
        digest.digest()
    } catch (e: NoSuchAlgorithmException) {
        null
    }
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