package ru.cororo.authserver.protocol.utils

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.security.*

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

private fun Key.getPemObject(): PemObject {
    val type = if (this is PublicKey) {
        "PUBLIC KEY"
    } else {
        "PRIVATE KEY"
    }
    return PemObject(type, encoded)
}

fun generateVerifyToken(): String {
    val chars = 'A'..'z'
    val verifyTokenBuilder = StringBuilder()
    repeat(4) {
        verifyTokenBuilder.append(chars.random())
    }
    return verifyTokenBuilder.toString()
}