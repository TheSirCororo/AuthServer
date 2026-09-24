package ru.cororo.authserver.gamedata

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal object Resources {
    private const val ROOT = "/ru/cororo/authserver/gamedata/"

    fun open(path: String): InputStream? = Resources::class.java.getResourceAsStream(ROOT + path)

    fun require(path: String): InputStream = checkNotNull(open(path)) { "Missing game data resource $path" }

    fun gzipBytesOrNull(path: String): ByteArray? = open(path)?.let { stream -> GZIPInputStream(stream).use { it.readBytes() } }

    fun gzipLines(path: String): List<String> =
        GZIPInputStream(require(path)).bufferedReader().useLines { lines -> lines.filter(String::isNotEmpty).toList() }

    /** Big-endian int32 array, as written by `tools/vanilla/gamedata.py`. */
    fun gzipInts(path: String): IntArray = DataInputStream(GZIPInputStream(require(path)).buffered()).use { input ->
        val bytes = input.readAllBytes()
        IntArray(bytes.size / 4) { index ->
            val offset = index * 4
            (bytes[offset].toInt() and 0xFF shl 24) or (bytes[offset + 1].toInt() and 0xFF shl 16) or
                (bytes[offset + 2].toInt() and 0xFF shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        }
    }

    /** Identical per-version tables are stored once; `<kind>.ref` names the protocol that holds the data. */
    fun versionedInts(protocol: Int, kind: String): IntArray {
        var directory = protocol.toString()
        while (true) {
            val reference = open("$directory/$kind.ref") ?: break
            directory = reference.bufferedReader().use { it.readText().trim() }
        }
        return gzipInts("$directory/$kind.bin.gz")
    }
}
