package com.stringpage.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.EffectParameterTable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream

@RunWith(AndroidJUnit4::class)
class RseEffectParameterTest {
    @Test fun allObservedParameterConversionsMatchNativeFloatBits() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val table = instrument.targetContext.assets.open("rse-effect-parameters.bin").use { EffectParameterTable.read(it) }
        assertEquals(318, table.entryCount); assertEquals(29303, table.pointCount)
        DataInputStream(instrument.context.assets.open("rse-effect-oracle.bin")).use { source ->
            val magic = ByteArray(8); source.readFully(magic)
            assertEquals("SPFXTST1", String(magic, Charsets.US_ASCII))
            fun int() = Integer.reverseBytes(source.readInt())
            val count = int(); assertEquals(119124, count)
            repeat(count) { case ->
                val input = source.readUnsignedByte() == 1
                val effect = int(); val parameter = int(); val value = Float.fromBits(int()); val expected = int()
                val actual = if (input) table.inputValue(effect, parameter, value) else table.outputValue(effect, parameter, value)
                assertEquals("native case $case", expected, actual.toRawBits())
            }
            assertEquals(-1, source.read())
        }
    }

    @Test fun rejectsTruncatedOrUnboundedParameterData() {
        fun rejects(bytes: ByteArray) {
            try { EffectParameterTable.read(ByteArrayInputStream(bytes)); fail("Malformed table accepted") }
            catch (_: IllegalArgumentException) { } catch (_: java.io.EOFException) { }
        }
        rejects(byteArrayOf(1, 2, 3))
        rejects("SPFXMAP1".toByteArray() + byteArrayOf(-1, -1, -1, 127))
        rejects("SPFXMAP1".toByteArray() + byteArrayOf(1, 0, 0, 0))
    }
}
