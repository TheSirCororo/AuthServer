package ru.cororo.authserver

import java.security.KeyPairGenerator
import kotlin.test.Test

class ApplicationTest {
    @Test
    fun generateKeyPair() {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(1024)
        val keypair = keyGen.genKeyPair()
        val privateKey = keypair.private
        val publicKey = keypair.public
        println(String(publicKey.encoded) to String(privateKey.encoded))
    }
}