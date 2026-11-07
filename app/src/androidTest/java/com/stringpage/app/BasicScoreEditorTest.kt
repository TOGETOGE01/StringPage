@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BasicScoreEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun demo() = ScoreLoader.loadScoreFromBytes(Uint8Array(context.assets.open("morning.gp").use { it.readBytes() }.asUByteArray()), Settings())
    private val address = BasicScoreEditor.NoteAddress(0, 0, 0, 0, 0, 0)
    private fun first(score: Score) = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]

    @Test fun fretEditUndoRedoPreservesAudioSyncAndRhythm() {
        var score = demo()
        val original = score
        val editor = BasicScoreEditor(context, { score }, { changed, _ -> score = changed })
        editor.reset(score)
        val fret = first(score).fret
        val pitch = first(score).realValue
        val audio = score.backingTrack!!.rawAudioFile!!
        val duration = first(score).beat.duration
        assertTrue(editor.updateFret(address, fret.toInt() + 2))
        assertNotSame(original, score)
        assertEquals(pitch + 2, first(score).realValue, 0.0)
        assertEquals(duration, first(score).beat.duration)
        assertSame(audio, score.backingTrack!!.rawAudioFile)
        assertEquals(16, score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
        assertTrue(editor.undo()); assertEquals(fret, first(score).fret, 0.0)
        assertTrue(editor.redo()); assertEquals(fret + 2, first(score).fret, 0.0)
        val reloaded = ScoreLoader.loadScoreFromBytes(Uint8Array(ScoreExport.guitarProBytes(score).asUByteArray()), Settings())
        assertEquals(first(score).realValue, first(reloaded).realValue, 0.0)
        assertEquals(audio.length, reloaded.backingTrack!!.rawAudioFile!!.length, 0.0)
    }

    @Test fun redoBranchesNoopsAndHistoryLimit() {
        var score = demo()
        val editor = BasicScoreEditor(context, { score }, { changed, _ -> score = changed })
        editor.reset(score)
        assertFalse(editor.updateMetadata(score.title, score.artist))
        assertFalse(editor.canUndo)
        repeat(25) { editor.updateMetadata("Title $it", "Artist") }
        assertEquals(20, editor.undoCount)
        assertTrue(editor.undo()); assertTrue(editor.canRedo)
        editor.updateMetadata("New branch", "Artist")
        assertFalse(editor.canRedo)
        val before = score
        try { editor.updateFret(address, 99); fail("Invalid fret accepted") } catch (_: IllegalArgumentException) {}
        assertSame(before, score)
        editor.reset(demo()); assertFalse(editor.canUndo)
    }

    @Test fun externalMixIsUndoableWithoutReplacingLiveScore() {
        var score = demo()
        val editor = BasicScoreEditor(context, { score }, { changed, _ -> score = changed })
        editor.reset(score)
        val previousVolume = score.tracks[0].playbackInfo.volume
        editor.beginExternalChange()
        score.tracks[0].playbackInfo.volume = 4.0
        editor.beginExternalChange() // Entire gesture/dialog is one undo item.
        score.tracks[0].playbackInfo.volume = 7.0
        editor.commitExternalChange("Mix")
        assertEquals(1, editor.undoCount)
        assertTrue(editor.undo()); assertEquals(previousVolume, score.tracks[0].playbackInfo.volume, 0.0)
        assertTrue(editor.redo()); assertEquals(7.0, score.tracks[0].playbackInfo.volume, 0.0)
    }

    @Test fun draftWritesReplaceAndReloadAtomically() {
        // Separate fixture context directory to avoid changing the user's app recovery file.
        val directory = java.io.File(context.cacheDir, "draft-test").apply { mkdirs() }
        val wrapper = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        val store = DraftStore(wrapper)
        try {
            val score = demo()
            store.write(ScoreExport.guitarProBytes(score))
            score.title = "Recovered changes"
            store.write(ScoreExport.guitarProBytes(score))
            assertTrue(store.exists())
            val restored = ScoreLoader.loadScoreFromBytes(Uint8Array(store.read().asUByteArray()), Settings())
            assertEquals("Recovered changes", restored.title)
            assertEquals(score.backingTrack!!.rawAudioFile!!.length, restored.backingTrack!!.rawAudioFile!!.length, 0.0)
        } finally { store.delete(); directory.delete() }
    }

    @Test fun progressiveTempoRepeatsClampsAndResets() {
        val trainer = ProgressivePractice()
        assertEquals(0.6, trainer.start(ProgressivePractice.Configuration(60, 72, 5, 2)), 0.0)
        assertNull(trainer.onLoopFinished())
        assertEquals(0.65, trainer.onLoopFinished()!!, 0.0)
        assertNull(trainer.onLoopFinished()); assertEquals(0.70, trainer.onLoopFinished()!!, 0.0)
        assertNull(trainer.onLoopFinished()); assertEquals(0.72, trainer.onLoopFinished()!!, 0.0)
        trainer.stop(); assertNull(trainer.onLoopFinished()); assertFalse(trainer.enabled)
        try { ProgressivePractice.Configuration(100, 50, 5, 2); fail("Invalid range accepted") } catch (_: IllegalArgumentException) {}
    }
}
