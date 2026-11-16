@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Duration
import alphaTab.model.MasterBar
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.Track
import alphaTab.model.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class GuitarAmpTest {
    @Test fun amplifierRemainsContinuousAtBlockBoundariesAndProducesHarmonics() {
        val rate = 44100
        val frames = rate
        val source = FloatArray(frames * 2) { (0.09 * sin(2 * PI * 294 * (it / 2) / rate)).toFloat() }
        val config = GuitarAmpSettings(AmpPreset.Modern, cabinet = GuitarCabinet.Closed)
        val whole = source.clone().also { GuitarAmp(config, rate).process(it, frames) }
        val clean = source.clone().also { GuitarAmp(config.copy(preset = AmpPreset.Clean), rate).process(it, frames) }
        val amp = GuitarAmp(config, rate)
        val split = source.clone()
        for (start in 0 until frames step 257) {
            val end = minOf(frames, start + 257)
            val block = source.copyOfRange(start * 2, end * 2)
            amp.process(block, end - start)
            block.copyInto(split, start * 2)
        }
        assertArrayEquals(whole, split, 0f)
        assertTrue(whole.all { it.isFinite() && it in -1f..1f })
        fun harmonic(audio: FloatArray, order: Int) =
            (rate / 2 until rate).sumOf { audio[it * 2] * sin(2 * PI * 294 * order * it / rate) }
        val cleanRatio = kotlin.math.abs(harmonic(clean, 3) / harmonic(clean, 1))
        val dirtyRatio = kotlin.math.abs(harmonic(whole, 3) / harmonic(whole, 1))
        assertTrue("clean=$cleanRatio dirty=$dirtyRatio", dirtyRatio > cleanRatio * 3)
        fun rms(audio: FloatArray) = sqrt(audio.sumOf { it.toDouble() * it } / audio.size)
        assertTrue("clean/drive level ${rms(clean) / rms(whole)}", rms(clean) / rms(whole) in 0.45..2.2)
    }

    @Test fun twoGuitarMixReusesDryStemsAndChangesOnlyTheConfiguredVoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "amp-test-${System.nanoTime()}").apply { mkdir() }
        val settings = Settings()
        val score = testScore(settings)
        try {
            val source = SynthAmpMix(directory) { context.assets.open("sonivox.sf2").use { it.readBytes() } }
            source.prepare(score, settings, listOf(GuitarTone.Clean, GuitarTone.Clean), 0, { false }, {}).use { stems ->
                val config = GuitarAmpSettings(AmpPreset.Clean, pan = -100)
                val before = directory.listFiles()!!.filter { it.extension == "pcm" }.associate { it.name to it.lastModified() }
                val clean = FloatArray(1024)
                val dirty = FloatArray(1024)
                val original = mapOf(0 to config)
                val changed = mapOf(0 to config.copy(preset = AmpPreset.British))
                LiveAmpMixer(stems, 512).use { control ->
                    LiveAmpMixer(stems, 512).use { mixed ->
                        val at = 6000L
                        control.seek(at, original)
                        mixed.seek(at, original)
                        control.read(clean, 512, original)
                        mixed.read(dirty, 512, changed)
                        assertEquals(clean[0], dirty[0], 0f)
                        assertTrue(clean.indices.any { it % 2 == 0 && clean[it] != dirty[it] })
                        assertTrue(clean.indices.any { it % 2 == 1 && clean[it] != 0f })
                        assertTrue(clean.indices.all { it % 2 == 0 || clean[it] == dirty[it] })
                    }
                }
                val after = directory.listFiles()!!.filter { it.extension == "pcm" }.associate { it.name to it.lastModified() }
                assertEquals(4, before.size)
                assertEquals(before, after)
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun testScore(settings: Settings) = Score().apply {
        addMasterBar(MasterBar().apply { timeSignatureNumerator = 4.0; timeSignatureDenominator = 4.0 })
        repeat(2) { index ->
            val track = Track().apply {
                name = "Guitar ${index + 1}"
                ensureStaveCount(1.0)
                playbackInfo.program = 27.0
                playbackInfo.primaryChannel = 0.0
                playbackInfo.secondaryChannel = 1.0
                playbackInfo.balance = if (index == 0) 8.0 else 16.0
            }
            addTrack(track)
            val bar = Bar()
            track.staves[0].addBar(bar)
            val voice = Voice()
            bar.addVoice(voice)
            repeat(2) { at ->
                val beat = Beat().apply { duration = Duration.Half }
                voice.addBeat(beat)
                beat.addNote(Note().apply { octave = 4.0; tone = (index * 2 + at * 4).toDouble() })
            }
        }
        finish(settings)
    }
}
