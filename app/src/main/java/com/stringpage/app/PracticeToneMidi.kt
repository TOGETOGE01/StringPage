@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.ControlChangeEvent
import alphaTab.midi.ControllerType
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.midi.ProgramChangeEvent
import alphaTab.model.JsonConverter
import alphaTab.model.Score
import alphaTab.model.Track

internal enum class GuitarTone(val label: String, val program: Double?) {
    Original("跟随原谱", null),
    Clean("清音", 27.0),
    Overdrive("过载", 29.0),
    Distortion("失真", 30.0);

    companion object {
        fun supports(track: Track): Boolean {
            if (track.isPercussion) return false
            val program = track.playbackInfo.program.toInt()
            return program in 24..31 || (program !in 32..39 && track.staves.any { it.isStringed } &&
                (track.name.contains("guitar", ignoreCase = true) || "吉他" in track.name || "ギター" in track.name))
        }
    }
}

/** Channel assignments live in the synthesizer only. GP and MIDI exports use the authored score. */
internal class PracticeToneMidi(private val score: Score) {
    data class Channels(val primary: Double, val secondary: Double) {
        val all: Set<Double> get() = setOf(primary, secondary)
    }

    private val original = score.tracks.toList().map { Channels(it.playbackInfo.primaryChannel, it.playbackInfo.secondaryChannel) }
    private val guitars = score.tracks.toList().map(GuitarTone::supports)
    val channels: List<Channels>

    init {
        val used = mutableSetOf(9.0)
        original.forEachIndexed { index, route -> if (!guitars[index]) used.addAll(route.all) }
        var available = 0.0
        fun claim(preferred: Double): Double {
            if (preferred in 0.0..255.0 && preferred % 1 == 0.0 && used.add(preferred)) return preferred
            // AlphaSynth uses in-memory channel numbers and grows its channel list as needed.
            // No serialized MIDI events are produced from these assignments.
            while (available in used) available++
            used.add(available)
            return available
        }
        channels = original.mapIndexed { index, route ->
            if (!guitars[index]) route else {
                val primary = claim(route.primary)
                Channels(primary, if (route.primary == route.secondary) primary else claim(route.secondary))
            }
        }
    }

    fun canChooseTone(index: Int) = guitars.getOrNull(index) == true

    fun apply(midi: MidiFile, settings: Settings, tones: List<GuitarTone>) {
        if (channels != original) {
            // Regenerate with separate channels so alphaTab retains both tracks' authored program
            // automation and routes bends to the new secondary channel before loading the synth.
            val json = checkNotNull(JsonConverter.scoreToJsObject(score))
            json.delete("backingtrack")
            val copy = JsonConverter.jsObjectToScore(json, settings)
            for ((index, track) in copy.tracks.withIndex()) {
                val route = channels[index]
                track.playbackInfo.primaryChannel = route.primary
                track.playbackInfo.secondaryChannel = route.secondary
            }
            val routed = MidiFile()
            MidiFileGenerator(copy, settings, AlphaSynthMidiFileHandler(routed)).apply {
                applyTranspositionPitches = false
                generate()
            }
            midi.tracks = routed.tracks
        }
        val programs = HashMap<Double, Double>()
        for ((index, tone) in tones.withIndex()) {
            val program = tone.program ?: continue
            if (canChooseTone(index)) channels[index].all.forEach { programs[it] = program }
        }
        if (programs.isEmpty()) return
        for (event in midi.events) when (event) {
            is ProgramChangeEvent -> programs[event.channel]?.let { event.program = it }
            is ControlChangeEvent -> if (event.channel in programs &&
                (event.controller == ControllerType.BankSelectCoarse || event.controller == ControllerType.BankSelectFine)) {
                event.value = 0.0
            }
        }
    }
}
