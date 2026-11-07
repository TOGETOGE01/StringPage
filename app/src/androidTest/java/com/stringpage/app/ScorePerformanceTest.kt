@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.importer.AlphaTexImporter
import alphaTab.midi.*
import alphaTab.model.Automation
import alphaTab.model.BendPoint
import alphaTab.model.BendType
import com.stringpage.app.rse.ScorePerformance
import org.junit.Assert.*
import org.junit.Test

class ScorePerformanceTest {
    private fun score() = AlphaTexImporter().run {
        initFromString("""\tempo 120 . 0.1.4 2.1.4 3.1.4 5.1.4 | 7.1.4 5.1.4 3.1.4 2.1.4""", Settings())
        readScore()
    }
    @Test fun repeatsAndTempoChangesAreExpandedIntoTheActualPerformance() {
        val score = score(); assertEquals(2, score.masterBars.length.toInt())
        score.masterBars[0].isRepeatStart = true; score.masterBars[1].repeatCount = 2.0
        score.masterBars[1].tempoAutomations.push(Automation.buildTempoAutomation(false, 0.0, 60.0, 2.0))
        score.rebuildRepeatGroups()
        val first = score.tracks[0].staves[0].bars[0].voices[0].beats[0]
        first.showTimer = true; first.timer = 321.125
        val result = ScorePerformance.compile(score)
        assertEquals(listOf(0,1,0,1), result.bars.map { it.index })
        assertEquals(listOf(0,0,1,1), result.bars.map { it.occurrence })
        assertEquals(16, result.notes.size)
        assertEquals(12000.0, result.durationMs, 1.0)
        assertEquals(2000.0, result.notes[4].startMs, 0.01)
        assertEquals(6000.0, result.notes[8].startMs, 0.01)
        assertEquals(8000.0, result.notes[12].startMs, 0.01)
        assertEquals(321.125, first.timer!!, 0.0)
        assertTrue(result.notes.all { it.sourceNote != null && it.sourceMatch == ScorePerformance.SourceMatch.ExactPitch })
        for (tick in listOf(0.0, 960.0, 3840.0, 9000.0, 15360.0)) assertEquals(tick, result.msToTick(result.tickToMs(tick)), 0.0001)
    }
    @Test fun noteTimesAndBendsAgreeWithThePublicMidiExporter() {
        val score = score()
        val first = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        first.bendType = BendType.Bend
        first.addBendPoint(BendPoint(0.0,0.0)); first.addBendPoint(BendPoint(60.0,4.0))
        val result = ScorePerformance.compile(score)
        val midi = MidiFile(); val handler = AlphaSynthMidiFileHandler(midi)
        val generator = MidiFileGenerator(score, Settings(), handler).apply { applyTranspositionPitches = false; generate() }
        val on = midi.events.toList().filterIsInstance<NoteOnEvent>().sortedWith(compareBy<NoteOnEvent> { it.tick }.thenBy { it.noteKey })
        val notes = result.notes.sortedWith(compareBy<ScorePerformance.NoteEvent> { it.startTick }.thenBy { it.midi })
        assertEquals(on.size, notes.size)
        for (i in on.indices) {
            assertEquals(on[i].tick, notes[i].startTick, 0.0)
            assertEquals(on[i].noteKey.toInt() + (generator.transpositionPitches.get(on[i].channel) ?: 0.0).toInt(), notes[i].midi)
            assertEquals(on[i].noteVelocity.toInt(), notes[i].velocity)
        }
        assertTrue(result.pitches.any { it.midi != null && it.value > 8192.0 })
        val nativeBends = midi.events.toList().filterIsInstance<NoteBendEvent>()
        val customBends = result.pitches.filter { it.midi != null }
        assertEquals(nativeBends.size, customBends.size)
        for (i in nativeBends.indices) {
            assertEquals(nativeBends[i].tick, customBends[i].tick, 0.0)
            assertEquals(nativeBends[i].value, customBends[i].value * 4294967296.0 / 16384.0, 0.01)
        }
    }
}
