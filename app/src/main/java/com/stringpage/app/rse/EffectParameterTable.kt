package com.stringpage.app.rse

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure parameter conversion, independently implemented from the observed native API.
 * Numeric effect IDs are retained verbatim. They are not yet mapped to GP preset IDs.
 * This does not process audio or model an effect/amp.
 */
class EffectParameterTable private constructor(private val entries: Map<Pair<Int, Int>, FloatArray>) {
    val entryCount get() = entries.size
    val pointCount get() = entries.values.sumOf { it.size / 2 }

    fun inputValue(effect: Int, parameter: Int, value: Float) = lookup(effect, parameter, value, 1)
    fun outputValue(effect: Int, parameter: Int, value: Float) = lookup(effect, parameter, value, 0)

    private fun lookup(effect: Int, parameter: Int, value: Float, column: Int): Float {
        val points = entries[effect to parameter] ?: return value
        var start = 0; var count = points.size / 2
        while (count > 0) {
            val step = count / 2
            val middle = start + step
            // Native lower_bound: no interpolation or clamping beyond the last row.
            if (points[middle * 2 + column] < value) { start = middle + 1; count -= step + 1 }
            else count = step
        }
        return if (start < points.size / 2) points[start * 2 + 1 - column] else value
    }

    companion object {
        /** StringPage's bounded LE storage, derived numeric facts only, no native machine code. */
        fun read(input: InputStream): EffectParameterTable {
            val source = DataInputStream(input)
            val magic = ByteArray(8); source.readFully(magic)
            require(magic.contentEquals("SPFXMAP1".toByteArray(Charsets.US_ASCII)))
            fun int() = Integer.reverseBytes(source.readInt())
            val count = int(); require(count in 0..4096)
            val entries = LinkedHashMap<Pair<Int, Int>, FloatArray>()
            var total = 0
            repeat(count) {
                val key = int() to int(); val size = int()
                require(size in 0..65536 && size <= 262144 - total && !entries.containsKey(key))
                total += size
                val bytes = ByteArray(size * 8); source.readFully(bytes)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val points = FloatArray(size * 2) { buffer.float }
                require(points.none { it.isNaN() })
                for (row in 1 until size) for (column in 0..1) require(points[(row - 1) * 2 + column] <= points[row * 2 + column])
                entries[key] = points
            }
            require(source.read() == -1) { "Unexpected parameter table data" }
            return EffectParameterTable(entries)
        }
    }
}
