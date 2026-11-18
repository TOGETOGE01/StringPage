@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Beat
import alphaTab.model.Clef
import alphaTab.model.DynamicValue
import alphaTab.model.Score
import android.media.midi.MidiDevice
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MidiInputTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Test fun framesRunningStatusAcrossPacketsAndIgnoresNonNoteMessages() {
        val stream = MidiByteStream()
        val notes = mutableListOf<MidiByteStream.Note>()
        val clears = mutableListOf<Int>()
        fun wire(vararg data: Int) {
            val bytes = ByteArray(data.size) { data[it].toByte() }
            stream.read(bytes, 0, bytes.size, notes::add, clears::add)
        }
        wire(0x92, 60)
        wire(0xf8, 90, 64, 88)
        wire(60, 0, 0xc2, 5, 31, 0x82, 64, 95, 0xb2, 123, 0)
        wire(0xf0, 15, 61, 35, 0xf7, 61, 35, 0xff)
        assertEquals(listOf(MidiByteStream.Note(2, 60, 90), MidiByteStream.Note(2, 64, 88),
            MidiByteStream.Note(2, 60, 0), MidiByteStream.Note(2, 64, 0)), notes)
        assertEquals(listOf(2, -1), clears)
    }

    @Test fun guitarChordFitsTuningCapoAndVoiceAndIsOneUndoStep() {
        var score = ScoreStructure.newScore("吉他键盘录入", ScoreStructure.Instrument.STEEL, 100, Settings())
        val editor = BasicScoreEditor(context, { score }, { next, _, _ -> score = next })
        assertTrue(editor.setTuning(0, 0, listOf(64, 59, 55, 50, 45, 40), 2))
        val address = BasicScoreEditor.BeatAddress(0, 0, 0, 1, 0)
        assertTrue(editor.enableVoice(0, 0, 0, 1))
        val expected = listOf(42, 47, 52, 57, 61, 66)
        val duration = beat(score, address).duration
        assertTrue(editor.putMidiChord(address, expected.map { MidiAttack(it, 79) }, preferredString = 1))
        val authored = beat(score, address)
        assertEquals(expected, pitches(authored))
        assertEquals(6, authored.notes.toList().map { it.string }.toSet().size)
        assertTrue(authored.notes.all { it.fret == 0.0 })
        assertEquals(DynamicValue.MF, authored.dynamics)
        assertEquals(duration, authored.duration)
        assertEquals(expected, pitches(beat(reload(score), address)))
        assertTrue(editor.undo())
        assertTrue(beat(score, address).isRest)
        assertTrue(editor.redo())
        assertEquals(expected, pitches(beat(score, address)))
        assertFalse(editor.putMidiChord(address, expected.map { MidiAttack(it, 79) }))
    }

    @Test fun pianoHasIndependentStavesAndReloadsPitchedNotes() {
        var score = ScoreStructure.newScore("钢琴键盘录入", ScoreStructure.Instrument.PIANO, 96, Settings())
        val editor = BasicScoreEditor(context, { score }, { next, _, _ -> score = next })
        val treble = BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0)
        val bass = treble.copy(staff = 1)
        assertTrue(editor.putMidiChord(treble, listOf(MidiAttack(60, 100), MidiAttack(64, 94))))
        assertTrue(editor.putMidiChord(bass, listOf(MidiAttack(48, 62))))
        val reloaded = reload(score)
        assertEquals(Clef.G2, reloaded.tracks[0].staves[0].bars[0].clef)
        assertEquals(Clef.F4, reloaded.tracks[0].staves[1].bars[0].clef)
        assertEquals(listOf(60, 64), pitches(beat(reloaded, treble)))
        assertEquals(listOf(48), pitches(beat(reloaded, bass)))
        assertTrue(beat(reloaded, bass).notes.none { it.isStringed })
    }

    @Test fun androidMidiPortWritesChordsToScoreAndStopsWhenEditingCloses() {
        val connected = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val open = CountDownLatch(1)
        val sender = AtomicReference<MidiDevice?>()
        val fault = AtomicReference<String?>()
        val controller = AtomicReference<MidiInputController>()
        val addresses = mutableListOf<List<Int>>()
        var editing = true
        var score = ScoreStructure.newScore("Android MIDI", ScoreStructure.Instrument.STEEL, 96, Settings())
        val address = BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0)
        val editor = BasicScoreEditor(context, { score }, { next, _, _ -> score = next })
        instrument.runOnMainSync {
            controller.set(MidiInputController(context, { editing }, { chord ->
                editor.putMidiChord(address, chord, 4)
                addresses.add(pitches(beat(score, address)))
                captured.countDown()
            }, { _, error -> fault.set(error); connected.countDown() }))
        }
        try {
            val receiver = controller.get()
            var port = receiver.ports().firstOrNull { it.label.contains("Step Input Loopback") }
            repeat(20) {
                if (port == null) {
                    Thread.sleep(150)
                    port = receiver.ports().firstOrNull { entry -> entry.label.contains("Step Input Loopback") }
                }
            }
            val chosen = requireNotNull(port) { "Android did not register the test MIDI service" }
            instrument.runOnMainSync { receiver.connect(chosen) }
            assertTrue("MIDI connection did not respond", connected.await(5, TimeUnit.SECONDS))
            assertEquals(null, fault.get())
            context.getSystemService(MidiManager::class.java).openDevice(chosen.device, {
                sender.set(it); open.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue(open.await(5, TimeUnit.SECONDS))
            requireNotNull(requireNotNull(sender.get()).openInputPort(0)).use { input ->
                val on = byteArrayOf(0x90.toByte(), 52, 80, 0x90.toByte(), 55, 86, 0x90.toByte(), 59, 78)
                input.send(on, 0, on.size)
                assertTrue("Chord did not arrive over Android MIDI", captured.await(5, TimeUnit.SECONDS))
                instrument.runOnMainSync { editing = false }
                val extra = byteArrayOf(0x90.toByte(), 64, 95)
                input.send(extra, 0, extra.size)
                Thread.sleep(200)
                instrument.runOnMainSync {
                    assertEquals(listOf(listOf(52, 55, 59)), addresses)
                    assertEquals(listOf(52, 55, 59), pitches(beat(reload(score), address)))
                }
            }
        } finally {
            sender.get()?.close()
            instrument.runOnMainSync { controller.get().close() }
        }
    }

    private fun reload(score: Score): Score =
        ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())

    private fun beat(score: Score, address: BasicScoreEditor.BeatAddress): Beat =
        score.tracks[address.track].staves[address.staff].bars[address.bar].voices[address.voice].beats[address.beat]

    private fun pitches(beat: Beat): List<Int> = beat.notes.toList().map { it.realValue.toInt() }.sorted()
}
