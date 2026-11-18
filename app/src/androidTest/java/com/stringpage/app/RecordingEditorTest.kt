@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.model.Score
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RecordingEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Document(var score: Score)

    // 4 seconds with a distinct audible section in the second quarter.
    private fun wav(rate: Int = 8000): ByteArray {
        val frames = 4 * rate
        return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { frame ->
                putShort(if (frame in rate until 2 * rate) (if (frame % 36 < 18) 16000 else -16000).toShort() else 0)
            }
        }.array()
    }

    private fun editor(doc: Document) = BasicScoreEditor(context, { doc.score }, { changed, _, _ -> doc.score = changed })
    private fun open(bytes: ByteArray) = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())
    private fun bytes(score: Score): ByteArray? = score.backingTrack?.rawAudioFile?.let {
        it.buffer.asByteArray().copyOfRange(it.byteOffset.toInt(), (it.byteOffset + it.length).toInt())
    }

    @Test fun localAudioIsValidatedAndItsWaveformMatchesTime() {
        val wav = wav()
        val clip = RecordingAudio.read(wav.inputStream(), context.cacheDir)
        assertEquals(4000L, clip.durationMs)
        val file = RecordingAudio.materialize(clip.bytes, context.cacheDir)
        try {
            val wave = RecordingWaveform.read(file, clip.durationMs.toDouble())
            assertEquals(2048, wave.size)
            assertTrue(wave.sliceArray(560..970).all { it > 0.4f })
            assertTrue(wave.sliceArray(0..487).all { it < 0.01f })
            assertTrue(wave.sliceArray(1060..2000).all { it < 0.01f })
        } finally { file.delete() }
        assertTrue(runCatching { RecordingAudio.read("not audio".byteInputStream(), context.cacheDir) }.isFailure)
    }

    @Test fun replacingAndRemovingAudioIsUndoableAndExportPreservesTheNewBytes() {
        val original = context.assets.open("morning.gp").use { open(it.readBytes()) }
        val originalBytes = bytes(original)
        val originalPoints = ScoreRecording.points(original)
        assertTrue(originalPoints.size > 1)
        val doc = Document(original)
        val editor = editor(doc)
        val newBytes = wav()
        val clip = RecordingAudio.read(newBytes.inputStream(), context.cacheDir)
        assertTrue(editor.replaceRecording(clip))
        assertEquals(listOf(ScoreRecording.Point(0, 0, 0.0, 0.0)), ScoreRecording.points(doc.score))
        val exported = open(ScoreExport.guitarProBytes(doc.score))
        assertArrayEquals(newBytes, bytes(exported))
        assertEquals(ScoreRecording.points(doc.score), ScoreRecording.points(exported))
        assertTrue(editor.undo())
        assertArrayEquals(originalBytes, bytes(doc.score))
        assertEquals(originalPoints, ScoreRecording.points(doc.score))
        assertTrue(editor.redo())
        assertTrue(editor.replaceRecording(null))
        assertNull(bytes(doc.score))
        assertTrue(ScoreRecording.points(doc.score).isEmpty())
        assertTrue(editor.undo())
        assertArrayEquals(newBytes, bytes(doc.score))
    }

    @Test fun syncPointsUsePlayedPassOrderAndPersistFractionalBarPositions() {
        val doc = Document(ScoreStructure.newScore("录音校准", ScoreStructure.Instrument.STEEL, 120, Settings()))
        val editor = editor(doc)
        assertTrue(editor.appendBar())
        assertTrue(editor.setRepeatMarks(0, ScoreStructure.RepeatMarks(true, 0, 0)))
        assertTrue(editor.setRepeatMarks(1, ScoreStructure.RepeatMarks(false, 2, 0)))
        val generator = MidiFileGenerator(doc.score, Settings(), AlphaSynthMidiFileHandler(MidiFile()))
        generator.generate()
        val visits = ScoreRecording.visits(generator.tickLookup)
        assertEquals(listOf(0, 1, 0, 1), visits.map { it.bar })
        assertEquals(listOf(0, 0, 1, 1), visits.map { it.occurrence })
        val clip = RecordingAudio.read(wav().inputStream(), context.cacheDir)
        assertTrue(editor.replaceRecording(clip))
        val points = listOf(ScoreRecording.Point(0, 0, 0.0, 350.0),
            ScoreRecording.Point(1, 0, 0.25, 1600.0), ScoreRecording.Point(0, 1, 0.0, 2700.0))
        assertTrue(editor.setRecordingPoints(points.reversed(), visits, 4000.0))
        val bad = points + ScoreRecording.Point(1, 1, 0.25, 1600.0)
        assertTrue(runCatching { editor.setRecordingPoints(bad, visits, 4000.0) }.exceptionOrNull() is IllegalArgumentException)
        val restored = open(ScoreExport.guitarProBytes(doc.score))
        val roundTrip = ScoreRecording.ordered(ScoreRecording.points(restored), visits, 4000.0)
        assertEquals(points.size, roundTrip.size)
        for ((expected, actual) in points.zip(roundTrip)) {
            assertTrue(ScoreRecording.samePosition(expected, actual))
            // GP stores recording offsets in whole 44.1 kHz reference frames.
            assertEquals(expected.timeMs, actual.timeMs, 1000.0 / 44100.0 + 0.000001)
        }
        assertTrue(editor.undo())
        assertEquals(listOf(ScoreRecording.Point(0, 0, 0.0, 0.0)), ScoreRecording.points(doc.score))
    }
}
