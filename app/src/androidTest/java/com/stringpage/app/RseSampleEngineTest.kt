package com.stringpage.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RseSampleEngineTest {
    @Test fun coordinateMetricMatchesTwoThousandNativeDllResultsBitForBit() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val cases = JSONArray(context.assets.open("rse-coordinate-oracle.json").bufferedReader().use { it.readText() })
        fun coordinate(values: JSONArray) = SampleCoordinate(values.getInt(0), values.getInt(1),
            values.getInt(2), values.getInt(3), values.getInt(4))
        assertEquals(1000, cases.length())
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val request = coordinate(row.getJSONArray("request")); val sample = coordinate(row.getJSONArray("sample"))
            assertEquals("forward $i", row.getLong("bits").toInt(), request.distance(sample).toRawBits())
            assertEquals("reverse $i", row.getLong("reverseBits").toInt(), sample.distance(request).toRawBits())
        }
        assertEquals("first", SampleCoordinate.nearest(SampleCoordinate(), listOf("first", "second")) { SampleCoordinate() })
        assertNull(SampleCoordinate.nearest(SampleCoordinate(), emptyList<SampleCoordinate>()) { it })
    }

    @Test fun stereoChannelsRemainSeparateAndForwardLoopWrapsAtExclusiveEnd() {
        val audio = DecodedBankSample(floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f, 4f, -4f), 2, 44100)
        val voice = SampleVoice(audio, 44100, gain = 1f, loopStart = 1, loopEndExclusive = 3)
        val block = FloatArray(12); voice.render(block, 6)
        assertArrayEquals(floatArrayOf(1f,-1f,2f,-2f,3f,-3f,2f,-2f,3f,-3f,2f,-2f), block, 0f)
        val tail = FloatArray(8); voice.render(tail, 4, sustain = false)
        assertArrayEquals(floatArrayOf(3f,-3f,4f,-4f,0f,0f,0f,0f), tail, 0f)
        assertTrue(voice.finished)
        val atEnd = SampleVoice(audio, 44100, gain = 1f, loopStart = 2, loopEndExclusive = audio.frames)
        val wrapped = FloatArray(12); atEnd.render(wrapped, 6)
        assertArrayEquals(floatArrayOf(1f,-1f,2f,-2f,3f,-3f,4f,-4f,3f,-3f,4f,-4f),wrapped,0f)
    }

    @Test fun pitchAndSampleRateCombineWithoutCollapsingStereoOrProducingNaN() {
        val audio = DecodedBankSample(floatArrayOf(0f, 0.25f, 0.5f, 0.75f), 1, 44100)
        val octave = SampleVoice(audio, 44100, semitones = 12.0, gain = 1f)
        val block = FloatArray(8); octave.render(block, 4)
        assertArrayEquals(floatArrayOf(0f,0f,0.5f,0.5f,0f,0f,0f,0f), block, 0f)
        val halfRate = SampleVoice(audio, 88200, gain = 1f)
        halfRate.render(block, 4)
        assertArrayEquals(floatArrayOf(0f,0f,0.125f,0.125f,0.25f,0.25f,0.375f,0.375f), block, 0f)
        try { SampleVoice(audio, 44100, loopStart = 0, loopEndExclusive = 20); fail("Invalid loop accepted") }
        catch (_: IllegalArgumentException) { }
        try { DecodedBankSample(floatArrayOf(Float.NaN), 1, 44100); fail("NaN accepted") }
        catch (_: IllegalArgumentException) { }
    }

    /** The separately installed bank stays in private debug storage, never in test/release APKs. */
    @Test fun installedReferenceBankDecodesFromArchiveRegionAndLoopsInMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "rse-reference.gp7bank")
        assumeTrue("Optional local original bank has not been provisioned", file.isFile)
        val bank = Gp7Bank.open(file); val metadata = bank.readMetadata()
        val wave = metadata.samples.first().waves.first()
        val header = bank.readAudioMetadata(wave.entry)
        val decoded = BankSampleDecoder.decode(file, wave.entry.offset, wave.entry.length)
        assertEquals(header.channels, decoded.channels)
        assertEquals(header.sampleRate.toInt(), decoded.sampleRate)
        assertTrue(decoded.frames > 1000)
        assertTrue(decoded.samples.any { kotlin.math.abs(it) > 0.0001f })
        val loop = header.sampler?.loops?.firstOrNull { it.type == 0L && it.end <= decoded.frames }
        val voice = SampleVoice(decoded, 44100, gain = 0.2f,
            loopStart = loop?.start?.toInt(), loopEndExclusive = loop?.end?.toInt())
        val block = FloatArray(1024)
        repeat(1000) { voice.render(block, 512); assertTrue(block.all { it.isFinite() && kotlin.math.abs(it) <= 1f }) }
        if (loop != null) assertFalse(voice.finished)
        // Exercise Android audio output and lifecycle cancellation without storing any recording.
        BankAudition.play(file, wave, 0) { false }
    }
}
