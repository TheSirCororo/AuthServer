package ru.cororo.authserver.world.encode

/**
 * Packs fixed-width values into longs. Before 1.16 values span long boundaries ("spanning"); since then each long
 * holds `64 / bits` values and the remaining high bits stay unused.
 */
internal object BitPacking {
    fun longCount(count: Int, bits: Int, spanning: Boolean): Int =
        if (spanning) (count * bits + 63) / 64 else (count + 64 / bits - 1) / (64 / bits)

    fun pack(values: IntArray, bits: Int, spanning: Boolean): LongArray {
        val data = LongArray(longCount(values.size, bits, spanning))
        val mask = (1L shl bits) - 1
        if (spanning) {
            values.forEachIndexed { index, value ->
                val bit = index * bits
                val word = bit ushr 6
                val offset = bit and 63
                data[word] = data[word] or ((value.toLong() and mask) shl offset)
                if (offset + bits > 64) data[word + 1] = data[word + 1] or ((value.toLong() and mask) ushr (64 - offset))
            }
        } else {
            val perLong = 64 / bits
            values.forEachIndexed { index, value ->
                val word = index / perLong
                data[word] = data[word] or ((value.toLong() and mask) shl ((index % perLong) * bits))
            }
        }
        return data
    }

    fun unpack(data: LongArray, bits: Int, count: Int, spanning: Boolean): IntArray {
        val mask = (1L shl bits) - 1
        return if (spanning) {
            IntArray(count) { index ->
                val bit = index * bits
                val word = bit ushr 6
                val offset = bit and 63
                var value = data[word] ushr offset
                if (offset + bits > 64) value = value or (data[word + 1] shl (64 - offset))
                (value and mask).toInt()
            }
        } else {
            val perLong = 64 / bits
            IntArray(count) { index -> ((data[index / perLong] ushr ((index % perLong) * bits)) and mask).toInt() }
        }
    }

    /** Bits needed to store values `0 until size`. */
    fun bitsFor(size: Int): Int = if (size <= 1) 0 else 32 - Integer.numberOfLeadingZeros(size - 1)
}
