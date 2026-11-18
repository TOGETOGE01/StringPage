@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.AlphaTexImporter
import alphaTab.importer.ScoreLoader
import alphaTab.model.Note
import alphaTab.model.HarmonicType
import alphaTab.model.Score
import alphaTab.model.SlideInType
import alphaTab.model.SlideOutType
import alphaTab.model.VibratoType
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.BasicScoreEditor.BeatAddress
import com.stringpage.app.BasicScoreEditor.Technique
import com.stringpage.app.rse.GpRseProperties
import com.stringpage.app.rse.NativeNoteTechnique
import com.stringpage.app.rse.NativeSlideScoreGraph
import com.stringpage.app.rse.ScorePerformance
import org.junit.Assert.*
import org.junit.Test

class ScoreTechniqueEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Document(var score: Score)

    private fun load(tablature: String): Document {
        val original = AlphaTexImporter().run {
            initFromString("\\tempo 120 . $tablature", Settings())
            readScore()
        }
        return Document(roundTrip(original))
    }

    private fun roundTrip(score: Score): Score {
        val bytes = ScoreExport.guitarProBytes(score)
        return ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings()).also {
            GpRseProperties.bind(it, GpRseProperties.read(bytes))
        }
    }

    private fun editor(doc: Document) = BasicScoreEditor(context, { doc.score }, { changed, _, _ -> doc.score = changed })
    private fun at(bar: Int = 0, beat: Int = 0) = BeatAddress(0, 0, bar, 0, beat)
    private fun note(score: Score, bar: Int = 0, beat: Int = 0): Note =
        score.tracks[0].staves[0].bars[bar].voices[0].beats[beat].notes[0]

    @Test fun barlineTiePlaysOneSustainedNoteAndSurvivesExport() {
        val doc = load("5.1.1 | 5.1.1")
        val editor = editor(doc)
        val string = note(doc.score).string.toInt()
        assertTrue(editor.toggleTechnique(at(), string, Technique.Tie))
        assertSame(note(doc.score, bar = 1), note(doc.score).tieDestination)
        assertEquals(true, GpRseProperties.note(note(doc.score, bar = 1))?.tieDestination)
        val restored = roundTrip(doc.score)
        assertTrue(note(restored, bar = 1).isTieDestination)
        val performance = ScorePerformance.compile(restored)
        assertEquals(1, performance.notes.size)
        assertTrue(performance.notes.single().endTick > 3840.0)
        assertTrue(editor.toggleTechnique(at(), string, Technique.Tie))
        assertEquals(2, ScorePerformance.compile(doc.score).notes.size)
        assertTrue(editor.undo())
        assertSame(note(doc.score), note(doc.score, bar = 1).tieOrigin)
    }

    @Test fun hammerAndPullCanFormAChainAndDeletingTheMiddleClearsBothLinks() {
        val doc = load("3.1.4 5.1.4 3.1.4 7.2.4")
        val editor = editor(doc)
        val string = note(doc.score).string.toInt()
        assertTrue(editor.toggleTechnique(at(), string, Technique.HammerPull))
        assertTrue(editor.toggleTechnique(at(beat = 1), string, Technique.HammerPull))
        val restored = roundTrip(doc.score)
        val middle = note(restored, beat = 1)
        val end = note(restored, beat = 2)
        assertSame(note(restored), middle.hammerPullOrigin)
        assertSame(end, middle.hammerPullDestination)
        assertEquals(0, NativeNoteTechnique.apply(middle).cuePoint)
        assertEquals(0.8f, NativeNoteTechnique.apply(middle).modifiers[1]!!, 0.0f)
        assertEquals(0.7f, NativeNoteTechnique.apply(end).modifiers[1]!!, 0.0f)

        assertTrue(editor.clearBeat(at(beat = 1), string))
        assertFalse(note(doc.score).isHammerPullOrigin)
        assertFalse(note(doc.score, beat = 2).isHammerPullDestination)
        assertEquals(false, GpRseProperties.note(note(doc.score, beat = 2))?.hopoDestination)
        assertTrue(editor.undo())
        assertSame(note(doc.score, beat = 2), note(doc.score, beat = 1).hammerPullDestination)
    }

    @Test fun slideCanBeChangedWithoutDroppingTheSlideInOrLeavingAStaleLink() {
        val doc = load("3.1.4 7.1.4 5.2.4 r.4")
        val editor = editor(doc)
        val string = note(doc.score).string.toInt()
        assertTrue(editor.toggleTechnique(at(), string, Technique.SlideFromBelow))
        assertTrue(editor.toggleTechnique(at(), string, Technique.ShiftSlide))
        assertEquals(17, NativeSlideScoreGraph.flags(note(doc.score)) and 0xff)
        assertTrue(editor.toggleTechnique(at(), string, Technique.LegatoSlide))
        val restored = roundTrip(doc.score)
        assertEquals(SlideInType.IntoFromBelow, note(restored).slideInType)
        assertEquals(SlideOutType.Legato, note(restored).slideOutType)
        assertSame(note(restored, beat = 1), note(restored).slideTarget)
        assertTrue(ScorePerformance.compile(restored).pitches.any { it.value != 8192.0 })
        assertTrue(editor.toggleTechnique(at(), string, Technique.SlideOutUp))
        assertEquals(24, NativeSlideScoreGraph.flags(note(doc.score)) and 0xff)
        assertNull(note(doc.score, beat = 1).slideOrigin)
    }

    @Test fun vibratoAndArticulationsRoundTripAndHavePlaybackEffects() {
        val doc = load("3.1.4 5.2.4 7.3.4 8.4.4")
        val editor = editor(doc)
        val before = ScorePerformance.compile(doc.score).notes
        fun set(index: Int, technique: Technique) {
            assertTrue(editor.toggleTechnique(at(beat = index), note(doc.score, beat = index).string.toInt(), technique))
        }
        set(0, Technique.WideVibrato)
        set(1, Technique.Staccato)
        set(2, Technique.LetRing)
        set(3, Technique.Ghost)
        val restored = roundTrip(doc.score)
        assertEquals(VibratoType.Wide, note(restored).vibrato)
        assertTrue(note(restored, beat = 1).isStaccato)
        assertTrue(note(restored, beat = 2).isLetRing)
        assertTrue(note(restored, beat = 3).isGhost)
        val after = ScorePerformance.compile(restored)
        assertTrue(after.pitches.any { it.value != 8192.0 })
        assertTrue(after.notes[1].endTick < before[1].endTick)
        assertTrue(after.notes[2].endTick > before[2].endTick)
    }

    @Test fun aLinkCannotJumpOverARestOrAlterOtherStrings() {
        val doc = load("3.1.4 r.4 3.1.4 7.2.4")
        val editor = editor(doc)
        val string = note(doc.score).string.toInt()
        assertTrue(runCatching { editor.toggleTechnique(at(), string, Technique.Tie) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(editor.canUndo)
        assertEquals(3, ScorePerformance.compile(doc.score).notes.size)
    }

    @Test fun naturalAndOctaveHarmonicsSoundAtTheCorrectPitchAndTappingKeepsItsAttack() {
        val doc = load("7.1.4 5.1.4 3.2.4 r.4")
        val editor = editor(doc)
        val top = note(doc.score).string.toInt()
        assertTrue(editor.toggleTechnique(at(), top, Technique.NaturalHarmonic))
        assertTrue(editor.toggleTechnique(at(beat = 1), top, Technique.OctaveHarmonic))
        val tap = note(doc.score, beat = 2).string.toInt()
        assertTrue(editor.toggleTechnique(at(beat = 2), tap, Technique.LeftHandTap))
        val restored = roundTrip(doc.score)
        assertEquals(HarmonicType.Natural, note(restored).harmonicType)
        assertEquals(HarmonicType.Artificial, note(restored, beat = 1).harmonicType)
        assertEquals(7.0, note(restored).harmonicValue, 0.0)
        assertEquals(12.0, note(restored, beat = 1).harmonicValue, 0.0)
        assertEquals(listOf(83, 81), ScorePerformance.compile(restored).notes.take(2).map { it.midi })
        assertTrue(note(restored, beat = 2).isLeftHandTapped)
        assertEquals(0, NativeNoteTechnique.apply(note(restored, beat = 2)).cuePoint)
        assertEquals(.8f, NativeNoteTechnique.apply(note(restored, beat = 2)).modifiers[2]!!, 0.0f)
        assertFalse(runCatching { editor.putFret(at(), top, 1) }.isSuccess)
        assertEquals(7.0, note(doc.score).fret, 0.0)
        assertTrue(editor.putFret(at(), top, 12))
        assertEquals(76, ScorePerformance.compile(doc.score).notes.first().midi)
    }
}
