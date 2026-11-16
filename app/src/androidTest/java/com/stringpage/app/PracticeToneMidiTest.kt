@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.midi.NoteOnEvent
import alphaTab.midi.ProgramChangeEvent
import alphaTab.model.Automation
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Duration
import alphaTab.model.MasterBar
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.Track
import alphaTab.model.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PracticeToneMidiTest {
    @Test fun sharedGuitarsKeepIndependentProgramsAndArticulationChannels() {
        val settings = Settings()
        val score = guitarScore(settings, 10)
        val router = PracticeToneMidi(score)
        val routes = router.channels
        assertEquals(20, routes.flatMap { it.all }.distinct().size)
        assertTrue(routes.last().secondary > 15)
        assertTrue(routes.none { 9.0 in it.all })
        val tones = List(routes.size) { if (it == 0) GuitarTone.Distortion else GuitarTone.Original }
        val midi = MidiFile()
        MidiFileGenerator(score, settings, AlphaSynthMidiFileHandler(midi)).generate()
        val originalTicks = midi.events.filterIsInstance<NoteOnEvent>().map { it.tick to it.noteKey }
        router.apply(midi, settings, tones)
        assertEquals(originalTicks, midi.events.filterIsInstance<NoteOnEvent>().map { it.tick to it.noteKey })
        val changes = midi.events.filterIsInstance<ProgramChangeEvent>()
        assertEquals(routes[0].all, changes.filter { it.track == 0.0 }.map { it.channel }.toSet())
        assertEquals(setOf(30.0), changes.filter { it.track == 0.0 }.map { it.program }.toSet())
        assertEquals(routes[1].all, changes.filter { it.track == 1.0 }.map { it.channel }.toSet())
        assertEquals(setOf(27.0, 29.0), changes.filter { it.track == 1.0 }.map { it.program }.toSet())
        assertTrue(changes.any { it.track == 9.0 && it.channel > 15 })
        assertTrue(score.tracks.all { it.playbackInfo.program == 27.0 &&
            it.playbackInfo.primaryChannel == 0.0 && it.playbackInfo.secondaryChannel == 1.0 })
    }

    private fun guitarScore(settings: Settings, trackCount: Int): Score = Score().apply {
        addMasterBar(MasterBar().apply { timeSignatureNumerator = 4.0; timeSignatureDenominator = 4.0 })
        repeat(trackCount) { index ->
            val track = Track().apply {
                name = "Guitar ${index + 1}"
                ensureStaveCount(1.0)
                playbackInfo.program = 27.0
                playbackInfo.primaryChannel = 0.0
                playbackInfo.secondaryChannel = 1.0
            }
            addTrack(track)
            val bar = Bar()
            track.staves[0].addBar(bar)
            val voice = Voice()
            bar.addVoice(voice)
            repeat(2) { beatIndex ->
                val beat = Beat().apply {
                    duration = Duration.Half
                    if (beatIndex == 1) automations.push(Automation.buildInstrumentAutomation(false, 0.0, 29.0))
                }
                voice.addBeat(beat)
                beat.addNote(Note().apply { octave = 3.0; tone = index.toDouble() })
            }
        }
        finish(settings)
    }
}
