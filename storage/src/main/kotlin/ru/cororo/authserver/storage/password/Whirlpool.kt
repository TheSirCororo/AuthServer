package ru.cororo.authserver.storage.password

/**
 * Whirlpool (ISO/IEC 10118-3, final version) for verifying legacy xAuth/BungeeAuth hashes.
 * It is not in the JDK, and pulling in a crypto provider for one legacy format would outweigh this file.
 */
internal object Whirlpool {
    private const val ROUNDS = 10
    private val sbox = IntArray(256)
    private val roundConstants = Array(ROUNDS) { LongArray(8) }

    init {
        // The S-box is built from the mini-boxes E, E^-1 and R as specified.
        val e = intArrayOf(0x1, 0xB, 0x9, 0xC, 0xD, 0x6, 0xF, 0x3, 0xE, 0x8, 0x7, 0x4, 0xA, 0x2, 0x5, 0x0)
        val r = intArrayOf(0x7, 0xC, 0xB, 0xD, 0xE, 0x4, 0x9, 0xF, 0x6, 0x3, 0x8, 0xA, 0x2, 0x5, 0x1, 0x0)
        val inverse = IntArray(16).also { inverse -> e.forEachIndexed { index, value -> inverse[value] = index } }
        for (x in 0 until 256) {
            val high = e[x shr 4]
            val low = inverse[x and 0xF]
            val mixed = r[high xor low]
            sbox[x] = (e[high xor mixed] shl 4) or inverse[low xor mixed]
        }
        for (round in 0 until ROUNDS) {
            for (column in 0 until 8) roundConstants[round][column] = sbox[8 * round + column].toLong()
        }
    }

    fun hex(text: String): String = digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun digest(message: ByteArray): ByteArray {
        // Padding: a 1 bit, zeros, then the 256-bit big-endian bit length, to a multiple of 512 bits.
        val paddedLength = ((message.size + 1 + 32 + 63) / 64) * 64
        val padded = message.copyOf(paddedLength)
        padded[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (index in 0 until 8) padded[paddedLength - 1 - index] = (bits ushr (8 * index)).toByte()

        var hash = Array(8) { LongArray(8) }
        for (offset in padded.indices step 64) {
            val block = Array(8) { row -> LongArray(8) { column -> (padded[offset + 8 * row + column].toInt() and 0xFF).toLong() } }
            val encrypted = cipher(hash, block)
            hash = Array(8) { row -> LongArray(8) { column -> encrypted[row][column] xor hash[row][column] xor block[row][column] } }
        }
        return ByteArray(64) { index -> hash[index / 8][index % 8].toByte() }
    }

    /** The W block cipher: state [block] under key [key], Miyaguchi-Preneel style. */
    private fun cipher(key: Array<LongArray>, block: Array<LongArray>): Array<LongArray> {
        var roundKey = key
        var state = Array(8) { row -> LongArray(8) { column -> block[row][column] xor key[row][column] } }
        for (round in 0 until ROUNDS) {
            val constant = Array(8) { row -> LongArray(8) { column -> if (row == 0) roundConstants[round][column] else 0 } }
            roundKey = addKey(mix(shift(substitute(roundKey))), constant)
            state = addKey(mix(shift(substitute(state))), roundKey)
        }
        return state
    }

    private fun substitute(state: Array<LongArray>) = Array(8) { row -> LongArray(8) { column -> sbox[state[row][column].toInt()].toLong() } }

    /** Column j is rotated down by j positions. */
    private fun shift(state: Array<LongArray>) = Array(8) { row -> LongArray(8) { column -> state[(row - column + 8) % 8][column] } }

    /** Multiplication by the circulant matrix cir(1, 1, 4, 1, 8, 5, 2, 9) over GF(2^8) mod x^8+x^4+x^3+x^2+1. */
    private fun mix(state: Array<LongArray>): Array<LongArray> {
        val circulant = intArrayOf(1, 1, 4, 1, 8, 5, 2, 9)
        return Array(8) { row ->
            LongArray(8) { column ->
                var sum = 0
                for (k in 0 until 8) sum = sum xor multiply(state[row][k].toInt(), circulant[(column - k + 8) % 8])
                sum.toLong()
            }
        }
    }

    private fun addKey(state: Array<LongArray>, key: Array<LongArray>) =
        Array(8) { row -> LongArray(8) { column -> state[row][column] xor key[row][column] } }

    private fun multiply(a: Int, b: Int): Int {
        var result = 0
        var x = a
        var y = b
        while (y != 0) {
            if (y and 1 != 0) result = result xor x
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
            y = y shr 1
        }
        return result
    }

    internal fun sboxPrefix(count: Int) = (0 until count).joinToString("") { "%02x".format(sbox[it]) }
}
