package ru.cororo.authserver.storage.password

import kotlin.test.Test
import kotlin.test.assertEquals

class WhirlpoolTest {
    @Test
    fun `s-box matches the specification`() {
        assertEquals("1823c6e887b8014f36a6d2f5796f9152", Whirlpool.sboxPrefix(16))
    }

    @Test
    fun `reference test vectors`() {
        assertEquals(
            "19fa61d75522a4669b44e39c1d2e1726c530232130d407f89afee0964997f7a73e83be698b288febcf88e3e03c4f0757ea8964e59b63d93708b138cc42a66eb3",
            Whirlpool.hex(""),
        )
        assertEquals(
            "b97de512e91e3828b40d2b0fdce9ceb3c4a71f9bea8d88e75c4fa854df36725fd2b52eb6544edcacd6f8beddfea403cb55ae31f03ad62a5ef54e42ee82c3fb35",
            Whirlpool.hex("The quick brown fox jumps over the lazy dog"),
        )
    }
}
