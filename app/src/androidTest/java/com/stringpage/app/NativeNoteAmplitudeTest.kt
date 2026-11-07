package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeNoteAmplitude
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeNoteAmplitudeTest {
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("native-note-amplitude.json").bufferedReader().use { it.readText() })

    @Test fun releaseMatchesOriginalDllAcrossNativeScalarAndUnrolledBoundaries() {
        val cases = fixture().getJSONArray("releases")
        assertEquals(224, cases.length())
        for (rowIndex in 0 until cases.length()) {
            val row = cases.getJSONObject(rowIndex)
            val channels = row.getInt("channels"); val frames = row.getInt("frames")
            val samples = FloatArray((frames + 2) * channels) { 12345.25f }
            for (index in 0 until frames) for (channel in 0 until channels) {
                samples[(index + 1) * channels + channel] = row.getJSONArray("input").getJSONArray(channel).getDouble(index).toFloat()
            }
            NativeNoteAmplitude.applyRelease(samples, channels, frames,
                row.getLong("elapsedFrames"), row.getLong("totalFrames"), offsetFrames = 1)
            for (index in 0 until frames) for (channel in 0 until channels) {
                val expected = row.getJSONArray("output").getJSONArray(channel).getDouble(index).toFloat()
                assertEquals("case $rowIndex channel $channel frame $index",expected.toRawBits(),samples[(index+1)*channels+channel].toRawBits())
            }
            for (channel in 0 until channels) {
                assertEquals(12345.25f,samples[channel],0f)
                assertEquals(12345.25f,samples[(frames+1)*channels+channel],0f)
            }
        }
    }

    @Test fun orderedAmplitudeModifiersMatchOriginalDllIncludingNullAndZeroSlots() {
        val cases = fixture().getJSONArray("products")
        assertEquals(128,cases.length())
        for (rowIndex in 0 until cases.length()) {
            val row=cases.getJSONObject(rowIndex);val values=row.getJSONArray("factors")
            val factors=List<Float?>(values.length()) { if(values.isNull(it)) null else values.getDouble(it).toFloat() }
            assertEquals("product case $rowIndex",row.getLong("bits").toInt(),NativeNoteAmplitude.multiplyModifiers(factors).toRawBits())
        }
        assertEquals(128L,NativeNoteAmplitude.DEFAULT_FADE_IN_FRAMES)
        assertEquals(128L,NativeNoteAmplitude.DEFAULT_FADE_OUT_FRAMES)
    }
}
