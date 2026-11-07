package com.stringpage.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Reads only bytes freshly returned by our pinned Gp7Exporter. Its Kotlin CRC
 * implementation uses signed shifts, producing invalid CRC fields. Decode the
 * known local headers and repackage with java.util.zip to compute standard CRCs.
 * This is deliberately not a user-file ZIP reader or a corruption fallback. */
internal object GeneratedGpZip {
    fun members(generated: ByteArray, consume: (String, InputStream) -> Unit) {
        fun u16(at: Int): Int {
            require(at >= 0 && at <= generated.size - 2)
            return (generated[at].toInt() and 255) or ((generated[at + 1].toInt() and 255) shl 8)
        }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        var position = 0; var total = 0L; var entries = 0
        while (u32(position) == 0x04034b50L) {
            require(++entries <= 64 && u16(position + 6) == 2048 && u16(position + 8) == 8) { "Unexpected generated GP ZIP header" }
            val compressed = u32(position + 18); val expanded = u32(position + 22)
            val nameLength = u16(position + 26); val extra = u16(position + 28)
            val start = position.toLong() + 30 + nameLength + extra
            total += expanded
            require(expanded <= 128L * 1024 * 1024 && total <= 256L * 1024 * 1024 &&
                start + compressed <= generated.size && nameLength in 1..4096)
            val name = String(generated, position + 30, nameLength, Charsets.UTF_8)
            val inflater = Inflater(true)
            try {
                val source = InflaterInputStream(ByteArrayInputStream(generated, start.toInt(), compressed.toInt()), inflater)
                // Consumer must exhaust the member; both the stream and inflated
                // length are checked before advancing to the next generated entry.
                consume(name, source)
                require(source.read() == -1 && inflater.finished() && inflater.bytesWritten == expanded &&
                    inflater.bytesRead == compressed) { "Invalid generated GP ZIP data" }
            } finally { inflater.end() }
            position = (start + compressed).toInt()
        }
        require(entries > 0 && u32(position) == 0x02014b50L) { "Missing generated GP ZIP directory" }
    }
}
