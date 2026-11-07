@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.importer.AlphaTexImporter
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

class NativeSourceRateConverterTest {
    @Test fun finiteStereoStreamsMatchOriginalConverterIncludingShortInputsAndFilterTail() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        DataInputStream(assets.open("native-source-rate.bin").buffered()).use { input ->
            val magic = ByteArray(8); input.readFully(magic)
            assertEquals("SPRATE01", String(magic, Charsets.US_ASCII))
            fun int() = Integer.reverseBytes(input.readInt())
            fun pcm(count: Int) = FloatArray(count) { Float.fromBits(int()) }
            val cases = int(); assertEquals(32, cases)
            var compared = 0
            repeat(cases) { row ->
                val rate = int(); val channels = int(); val frames = int(); val expectedFrames = int()
                val source = pcm(frames * channels); val expected = pcm(expectedFrames * channels)
                val original = source.copyOf()
                val converted = NativeSourceRateConverter.normalize(DecodedBankSample(source, channels, rate))
                assertEquals(44100, converted.sampleRate); assertEquals(channels, converted.channels)
                assertEquals(expectedFrames, converted.frames)
                assertArrayEquals("Source must remain unchanged", original, source, 0f)
                for (at in expected.indices) assertEquals("case $row rate $rate frame ${at / channels} channel ${at % channels}",
                    expected[at].toRawBits(), converted.samples[at].toRawBits())
                compared += expected.size
            }
            assertTrue(compared > 150000); assertEquals(-1, input.read())
        }
    }

    @Test fun streamLengthAndCueCoordinatesMatch112NativeFloat32FloorCases() {
        val data = InstrumentationRegistry.getInstrumentation().context.assets.open("native-frame-count.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("rows")
        assertEquals(112, data.length())
        repeat(data.length()) {
            val row = data.getJSONObject(it)
            assertEquals("coordinate $it", row.getLong("output"),
                NativeSourceRateConverter.outputFrameCount(row.getLong("input"), row.getInt("rate")))
        }
    }

    @Test fun monoConversionKeepsItsOwnChannelAndRateConversionPrecedesPitchResampling() {
        val mono = DecodedBankSample(FloatArray(18000) { if (it == 5000) 1f else 0f }, 1, 96000)
        val stereo = DecodedBankSample(FloatArray(mono.frames * 2) { if (it % 2 == 0) mono.samples[it / 2] else 0f }, 2, 96000)
        val one = NativeSourceRateConverter.normalize(mono)
        val two = NativeSourceRateConverter.normalize(stereo)
        assertEquals(one.frames, two.frames)
        repeat(one.frames) {
            assertEquals(one.samples[it], two.samples[it * 2], 0.0000001f)
            assertEquals(0f, two.samples[it * 2 + 1], 0f)
        }
        try { NativeSampleVoice(mono, 44100); fail("Unnormalized PCM must not enter the pitch resampler") }
        catch (_: IllegalArgumentException) { }
        val voice = NativeSampleVoice(one, 44100)
        val output = FloatArray(1024); var peak = 0f
        repeat(32) {
            voice.render(output, 512)
            for (i in 0 until 512) {
                assertEquals(output[i * 2], output[i * 2 + 1], 0f)
                assertTrue(output[i * 2].isFinite()); peak = maxOf(peak, kotlin.math.abs(output[i * 2]))
            }
        }
        assertTrue(peak > 0.1f); assertTrue(voice.finished)
    }

    @Test fun normalizedPcmKeepsNativeRawLoopCoordinatesAndTheirExclusiveBoundary() {
        val loop = RseLoop(3, 0, 9601, 22050, 0, 0)
        val converted = NativeStreamPolicy.forwardLoop("other.ogg", listOf(loop), 44100, 96000)!!
        assertEquals(9601L, converted.start); assertEquals(22050L, converted.end)
        assertNull(NativeStreamPolicy.forwardLoop("other.ogg", listOf(loop), 22049, 96000))
        assertNull(NativeStreamPolicy.forwardLoop("other.ogg", listOf(loop.copy(start = 0, end = 0)), 100, 96000))
        val source = DecodedBankSample(FloatArray(96000) { if (it % 2 == 0) 0.5f else -0.25f }, 2, 96000)
        val normalized = NativeSourceRateConverter.normalize(source)
        val voice = NativeSampleVoice(normalized, 44100, loopStart = converted.start.toInt(), loopEndExclusive = converted.end.toInt())
        val buffer = FloatArray(1024)
        repeat(120) { voice.render(buffer, 512); assertTrue(buffer.all { value -> value.isFinite() }) }
        assertFalse(voice.finished)
    }

    @Test fun cancellationDuringNativeConversionReleasesStateAndUnchangedRateUsesOriginalPcm() {
        val source = DecodedBankSample(FloatArray(50000) { (it % 97) / 97f }, 1, 96000)
        var checks = 0
        try {
            NativeSourceRateConverter.normalize(source) { ++checks >= 4 }
            fail("Cancellation ignored")
        } catch (_: IllegalStateException) { }
        assertTrue(checks >= 4)
        assertTrue(NativeSourceRateConverter.normalize(source).frames > 0)
        val same = DecodedBankSample(floatArrayOf(0f, .5f, -.5f), 1, 44100)
        assertSame(same, NativeSourceRateConverter.normalize(same))
        try { NativeSourceRateConverter.normalize(same) { true }; fail("Passthrough ignored cancellation") }
        catch (_: IllegalStateException) { }
    }

    @Test fun separatelyInstalled96kBankUsesTheIndependentRateStageAndMappedLoop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "rse-source-rate-reference.gp7bank")
        assumeTrue("Optional original 96k reference bank is outside both APKs", file.isFile)
        val bank = Gp7Bank.open(file)
        val wave = bank.readMetadata().samples.flatMap { it.waves }
            .first { bank.readAudioMetadata(it.entry).sampleRate == 96000L }
        val metadata = bank.readAudioMetadata(wave.entry)
        val raw = BankSampleDecoder.decode(file, wave.entry.offset, wave.entry.length)
        assertEquals(96000, raw.sampleRate)
        val normalized = NativeSourceRateConverter.normalize(raw)
        assertEquals(44100, normalized.sampleRate)
        assertEquals(NativeSourceRateConverter.outputFrameCount(raw.frames.toLong(), raw.sampleRate).toInt(), normalized.frames)
        assertTrue(normalized.samples.any { kotlin.math.abs(it) > .0001f })
        val loop = NativeStreamPolicy.forwardLoop(wave.file, metadata.sampler?.loops.orEmpty(), normalized.frames, raw.sampleRate)
        val voice = NativeSampleVoice(normalized, 44100, loopStart = loop?.start?.toInt(), loopEndExclusive = loop?.end?.toInt())
        val buffer = FloatArray(1024)
        repeat(100) { voice.render(buffer, 512); assertTrue(buffer.all { it.isFinite() }) }
    }

    @Test fun actualScoreRendererNormalizesSelected96kSampleBeforeCreatingItsVoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "rse-source-rate-reference.gp7bank")
        assumeTrue("Optional original 96k reference bank is outside both APKs", file.isFile)
        val reader = Gp7Bank.open(file); val bank = reader.readMetadata()
        val sample = bank.samples.first { sample -> sample.waves.any { reader.readAudioMetadata(it.entry).sampleRate == 96000L } }
        val wave = sample.waves.first { reader.readAudioMetadata(it.entry).sampleRate == 96000L }
        // Restrict the bank view to this real 96k member so the selector must
        // exercise it, without changing the original archive on disk.
        val item = BankLibrary.Item(file, bank.copy(samples = listOf(sample.copy(waves = listOf(wave)))))
        val importer = AlphaTexImporter()
        importer.initFromString("""\tempo 120 . 0.1.4 r.4 r.4 r.4""", Settings())
        val performance = ScorePerformance.compile(importer.readScore())
        val destination = File(context.cacheDir, "source-rate-render-test.wav")
        val originalBytes = file.length()
        try {
            val result = FactoryScoreRenderer.render(performance, mapOf(FactoryScoreRenderer.Route(0) to item), destination)
            assertTrue(result.peak > .0001f)
            RandomAccessFile(destination, "r").use { output ->
                output.seek(24); assertEquals(44100, Integer.reverseBytes(output.readInt()))
                assertTrue(output.length() > 44100 * 4)
            }
            assertEquals(originalBytes, file.length())
        } finally { destination.delete() }
    }
}
