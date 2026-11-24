@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.collections.List as AlphaList
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MasterBarTickLookup
import alphaTab.midi.MidiFile
import alphaTab.model.MasterBar
import org.junit.Assert.*
import org.junit.Test

class MediaMetronomeTest {
    @Test fun recordingClicksUseSyncAnchorsAndDropTheOldSeekInterval() {
        val map = RecordingMetronomeMap(doubleArrayOf(0.0, 2000.0), doubleArrayOf(250.0, 4250.0))
        val hits = mutableListOf<Float>()
        val recording = RecordingMetronome(metronome().clock(map)!!, { 0.8 }, hits::add)
        recording.prime(240.0, 1.0)
        recording.update(246.0, 1.0)
        assertTrue(hits.isEmpty())
        recording.update(256.0, 1.0)
        recording.update(253.0, 1.0)
        recording.update(261.0, 1.0)
        assertEquals(listOf(0.8f), hits)
        recording.update(1240.0, 1.0)
        assertEquals(1, hits.size)
        recording.update(1256.0, 1.0)
        assertEquals(2, hits.size)
        assertEquals(0.52f, hits.last(), 0.001f)
        recording.reset()
        recording.prime(250.0, 0.6)
        recording.update(255.0, 0.6)
        assertEquals(3, hits.size)
        assertEquals(0.8f, hits.last(), 0.001f)
    }

    @Test fun stemsMixClicksAtTheExpectedFrameAndMuteWithoutAStaleTail() {
        var gain = 1.0
        val clock = metronome().clock(MetronomeTimeMap.offset(250.0))!!
        val mix = StemMetronome(clock, { gain }, sampleRate = 8000)
        val block = FloatArray(40)
        mix.mix(block, 1992, 20, 1.0)
        assertTrue((0 until 9).all { block[it * 2] == 0f && block[it * 2 + 1] == 0f })
        assertTrue((9 until 20).any { block[it * 2] != 0f })
        assertTrue((9 until 20).all { block[it * 2] == block[it * 2 + 1] })
        gain = 0.0
        val next = FloatArray(40)
        mix.mix(next, 2012, 20, 1.0)
        assertTrue(next.all { it == 0f })
    }

    @Test fun customBpmKeepsItsWallTimeAcrossSlowMediaAndIgnoresTheSyncStretch() {
        val controller = metronome().apply { settings = MetronomeSettings(150, MetronomeRhythm.Eighth) }
        val clock = controller.clock(RecordingMetronomeMap(doubleArrayOf(0.0, 2000.0), doubleArrayOf(250.0, 4250.0)))!!
        val clicks = mutableListOf<Double>()
        for (block in 0..27) {
            clock.render(block * 8.0, (block + 1) * 8.0, 0.5) { time, _, _, _, _ -> clicks.add(time) }
        }
        assertEquals(listOf(0.0, 100.0, 200.0), clicks)
    }

    private fun metronome(): ReaderMetronome {
        val file = MidiFile()
        AlphaSynthMidiFileHandler(file).apply { addTempo(0.0, 120.0); finishTrack(0.0, 3840.0) }
        val bars = AlphaList<MasterBarTickLookup>().apply {
            push(MasterBarTickLookup().apply {
                start = 0.0
                end = 3840.0
                masterBar = MasterBar().apply { timeSignatureNumerator = 4.0; timeSignatureDenominator = 4.0 }
            })
        }
        return ReaderMetronome().apply { prepare(file, bars, PreparedScoreTime(file), synth = false) }
    }
}
