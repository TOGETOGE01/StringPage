@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.midi.*
import alphaTab.synth.AlphaSynthAudioExporter
import alphaTab.synth.AudioExportOptions
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Actual PCM from alphaTab's public synchronous exporter, independent of AudioTrack hardware. */
@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(29)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class SynthesisAudioQualityTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val current by lazy { context.assets.open("sonivox.sf2").use { it.readBytes() } }
    private val legacy by lazy { instrument.context.assets.open("legacy-sonivox.sf2").use { it.readBytes() } }
    private data class Stats(val samples: Int, val rms: Double, val peak: Double, val clipped: Int) {
        fun json() = JSONObject().put("samples", samples).put("rms", rms).put("peak", peak).put("clipped", clipped)
    }
    private fun stats(samples: FloatArray): Stats {
        var energy = 0.0; var peak = 0.0; var clipped = 0
        samples.forEach { value ->
            assertTrue("Non-finite synthesized sample", value.isFinite())
            energy += value.toDouble() * value; peak = maxOf(peak, abs(value.toDouble()))
            if (abs(value) >= 1f) clipped++
        }
        return Stats(samples.size, sqrt(energy / samples.size.coerceAtLeast(1)), peak, clipped)
    }
    private fun render(midi: MidiFile, font: ByteArray, milliseconds: Int): FloatArray {
        val exporter = AlphaSynthAudioExporter(AudioExportOptions().apply { sampleRate = 44100.0; masterVolume = 1.0 })
        // Program/key discovery must happen first: loadSoundFont loads only the MIDI's used samples.
        exporter.loadMidiFile(midi)
        exporter.loadSoundFont(Uint8Array(font.asUByteArray()))
        exporter.setup()
        val output = FloatArray(milliseconds * 44100 / 1000 * 2)
        var count = 0
        while (count < output.size) {
            val chunk = exporter.render(250.0) ?: break
            val length = min(chunk.samples.length.toInt(), output.size - count)
            if (length == 0) break
            for (i in 0 until length) output[count + i] = chunk.samples[i].toFloat()
            count += length
        }
        return output.copyOf(count)
    }
    private fun phrase(program: Int, drums: Boolean = false): MidiFile = MidiFile().apply {
        val channel = if (drums) 9.0 else 0.0
        addEvent(TempoChangeEvent(0.0, 500000.0))
        addEvent(ControlChangeEvent(0.0, 0.0, channel, ControllerType.VolumeCoarse, 100.0))
        addEvent(ControlChangeEvent(0.0, 0.0, channel, ControllerType.PanCoarse, 64.0))
        addEvent(ProgramChangeEvent(0.0, 0.0, channel, program.toDouble()))
        val keys = if (drums) intArrayOf(36, 38, 42) else intArrayOf(40, 52, 64)
        keys.forEachIndexed { index, key ->
            val tick = index * 960.0
            addEvent(NoteOnEvent(0.0, tick, channel, key.toDouble(), (56 + index * 25).toDouble()))
            addEvent(NoteOffEvent(0.0, tick + 720, channel, key.toDouble(), 0.0))
        }
        addEvent(EndOfTrackEvent(0.0, 3840.0))
    }

    @Test fun packagedBankReallyReplacesTheSmallLegacyBank() {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("00725296486f652ba42bdbf2098254762ca80239353d04b55a27454fc8edb8a8", sha(current))
        assertEquals("4978cf37e7164206f2d22dc4fdf4020ffe040787210bf781e31bdc79961f59ef", sha(legacy))
        assertTrue(current.size > 30_000_000)
    }

    @Test fun guitarsBassPianoAndDrumsProduceFiniteAudibleUnclippedPcm() {
        val results = JSONArray()
        val patches = (24..31).map { it to false } + listOf(32 to false, 33 to false, 0 to false, 0 to true)
        for ((program, drums) in patches) {
            val midi = phrase(program, drums)
            val old = stats(render(midi, legacy, 2200))
            val next = stats(render(midi, current, 2200))
            for ((name, value) in listOf("legacy" to old, "GeneralUser" to next)) {
                assertTrue("$name bank program=$program drums=$drums is silent", value.rms > 0.00001)
                assertTrue("$name bank program=$program has too few samples", value.samples > 44100)
                assertEquals("$name bank program=$program clips at the test gain", 0, value.clipped)
            }
            assertEquals("Changing the bank must not change MIDI timing", old.samples, next.samples)
            results.put(JSONObject().put("program", program).put("drums", drums).put("legacy", old.json()).put("current", next.json()))
        }
        publish("patch-validation.json", "application/json", JSONObject().put("sampleRate", 44100).put("masterVolume", 1.0).put("patches", results).toString(2).toByteArray())
    }

    @Test fun exportsLoudnessMatchedOriginalPhraseForListeningComparison() {
        val score = ScoreLoader.loadScoreFromBytes(Uint8Array(context.assets.open("morning.gp").use { it.readBytes() }.asUByteArray()), Settings())
        score.backingTrack = null
        for (bar in score.masterBars) bar.syncPoints = null
        score.tracks[0].playbackInfo.program = 25.0 // steel string guitar, same notes and performance
        val midi = MidiFile()
        MidiFileGenerator(score, Settings(), AlphaSynthMidiFileHandler(midi)).generate()
        val old = render(midi, legacy, 10000)
        val next = render(midi, current, 10000)
        val before = stats(old); val after = stats(next)
        assertEquals(882000, old.size); assertEquals(old.size, next.size)
        assertTrue(before.rms > 0.00001 && after.rms > 0.00001)
        assertFalse("The new sound bank must produce different audio", old.contentEquals(next))
        val targetRms = min(0.08, min(0.9 * before.rms / before.peak, 0.9 * after.rms / after.peak))
        val oldGain = targetRms / before.rms; val newGain = targetRms / after.rms
        publish("A-original-sonivox.wav", "audio/wav", wav(old, oldGain))
        publish("B-generaluser-1.44.wav", "audio/wav", wav(next, newGain))
        publish("comparison.json", "application/json", JSONObject().put("sampleRate", 44100).put("channels", 2)
            .put("seconds", 10).put("targetRms", targetRms).put("legacyGain", oldGain).put("currentGain", newGain)
            .put("legacy", before.json()).put("current", after.json()).put("note", "Equal RMS is not a perceptual quality verdict; no RSE audio is included.")
            .toString(2).toByteArray())
    }

    private fun wav(samples: FloatArray, gain: Double): ByteArray {
        val output = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        output.putShort(1).putShort(2).putInt(44100).putInt(44100 * 4).putShort(4).putShort(16)
        output.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { output.putShort((it * gain * 32767).roundToInt().coerceIn(-32768, 32767).toShort()) }
        return output.array()
    }

    private fun publish(name: String, mime: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/StringPageComparison/release-1.4")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Cannot create comparison output")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
}
