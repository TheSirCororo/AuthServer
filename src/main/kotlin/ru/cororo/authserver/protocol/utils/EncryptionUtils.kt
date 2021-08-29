package ru.cororo.authserver.protocol.utils

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.DERApplicationSpecific
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.jcajce.provider.asymmetric.rsa.CipherSpi
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import ru.cororo.authserver.session.MinecraftSession
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringWriter
import java.security.*


val verifyTokens = mutableMapOf<MinecraftSession, String>()
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

fun generateVerifyToken(session: MinecraftSession): String {
    val token = generate4CharsRandomString()
    verifyTokens[session] = token
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