@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.collections.DoubleDoubleMap
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.ControlChangeEvent
import alphaTab.midi.ControllerType
import alphaTab.midi.EndOfTrackEvent
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.midi.ProgramChangeEvent
import alphaTab.midi.TempoChangeEvent
import alphaTab.midi.TimeSignatureEvent
import alphaTab.model.JsonConverter
import alphaTab.model.Score
import alphaTab.synth.AlphaSynthAudioExporter
import alphaTab.synth.AudioExportOptions
import java.io.File

/** Prepares the audio input once. Amplifier edits reuse these time-aligned stems. */
internal class SynthAmpMix(private val directory: File, soundFont: () -> ByteArray) {
    private val font by lazy { Uint8Array(soundFont().asUByteArray()) }

    fun prepare(
        score: Score, settings: Settings, tones: List<GuitarTone>, transpose: Int,
        cancelled: () -> Boolean, progress: (Int) -> Unit
    ): AmpStems {
        val tracks = score.tracks.toList()
        val solo = tracks.any { it.playbackInfo.isSolo }
        val audible = tracks.indices.filter { index ->
            val info = tracks[index].playbackInfo
            if (solo) info.isSolo else !info.isMute
        }.toSet()
        require(audible.isNotEmpty()) { "请至少保留一个可播放声部。" }
        val router = PracticeToneMidi(score)
        val guitars = audible.filter(router::canChooseTone)
        val accompaniment = audible - guitars.toSet()
        val pitches = DoubleDoubleMap()
        val configured = settings.notation.transpositionPitches
        for ((index, route) in router.channels.withIndex()) {
            val pitch = if (index < configured.length) configured[index] else -tracks[index].staves[0].transpositionPitch
            for (channel in route.all) pitches.set(channel, pitch)
        }
        fun checkActive() = check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消准备。" }
        checkActive()
        val json = checkNotNull(JsonConverter.scoreToJsObject(score))
        json.delete("backingtrack")
        val copy = JsonConverter.jsObjectToScore(json, settings)
        val midi = MidiFile()
        val handler = AlphaSynthMidiFileHandler(midi)
        MidiFileGenerator(copy, settings, handler).apply { applyTranspositionPitches = false; generate() }
        router.apply(midi, settings, tones)
        val firstCue = PreparedScoreTime(midi).timeAt(midi.tickShift)
        val stemCount = guitars.size * 2 + if (accompaniment.isEmpty()) 0 else 1
        val files = ArrayList<File>()
        var totalFrames = 0L
        val maximumFrames = minOf(512L * 1024 * 1024, directory.usableSpace - 64L * 1024 * 1024) / PcmStem.FRAME_BYTES
        var success = false
        try {
            fun render(slots: Set<Int>, cleanDry: Boolean): PcmStem {
                checkActive()
                val file = File.createTempFile("amp-dry-", ".pcm", directory).also(files::add)
                val stem = writeStem(selectMidi(midi, slots, cleanDry), pitches, transpose, file) { frames ->
                    checkActive()
                    require(totalFrames + frames <= maximumFrames) { "音箱干声需要更多可用空间，请减少参与播放的声部。" }
                }
                totalFrames += stem.frames
                progress(files.size * 100 / stemCount)
                return stem
            }
            val parts = guitars.map { slot ->
                val slots = setOf(slot)
                AmpStems.Part(slot, render(slots, false), render(slots, true))
            }.toMutableList()
            if (accompaniment.isNotEmpty()) parts += AmpStems.Part(null, render(accompaniment, false))
            // The audio exporter and alphaTab's reader use the same shifted MIDI.
            return AmpStems(parts, SAMPLE_RATE, syncOffsetMs = 0.0, firstCueMs = firstCue).also { success = true }
        } finally { if (!success) files.forEach { it.delete() } }
    }

    private fun selectMidi(score: MidiFile, slots: Set<Int>, dry: Boolean): MidiFile = MidiFile().apply {
        division = score.division
        tickShift = score.tickShift
        for (event in score.events) {
            when {
                event is TempoChangeEvent || event is TimeSignatureEvent || event is EndOfTrackEvent -> addEvent(event)
                event.track.toInt() !in slots -> continue
                dry && event is ProgramChangeEvent -> addEvent(
                    ProgramChangeEvent(event.track, event.tick, event.channel, GuitarTone.Clean.program!!)
                )
                dry && event is ControlChangeEvent -> {
                    val value = when (event.controller) {
                        ControllerType.BankSelectCoarse, ControllerType.BankSelectFine -> 0.0
                        ControllerType.VolumeCoarse -> if (event.tick <= tickShift) 110.0 else event.value
                        ControllerType.PanCoarse -> if (event.tick <= tickShift) 64.0 else event.value
                        else -> event.value
                    }
                    addEvent(ControlChangeEvent(event.track, event.tick, event.channel, event.controller, value))
                }
                else -> addEvent(event)
            }
        }
    }

    private fun writeStem(
        midi: MidiFile, pitches: DoubleDoubleMap, transpose: Int, file: File, checkActive: (Long) -> Unit
    ): PcmStem {
        val exporter = AlphaSynthAudioExporter(AudioExportOptions().apply {
            sampleRate = SAMPLE_RATE.toDouble()
            masterVolume = 1.0
        })
        exporter.loadMidiFile(midi)
        exporter.loadSoundFont(font)
        exporter.applyTranspositionPitches(pitches)
        if (transpose != 0) {
            val channels = midi.events.filterIsInstance<ProgramChangeEvent>().map { it.channel }.distinct()
            channels.filter { it != 9.0 }.forEach { exporter.setChannelTranspositionPitch(it, transpose.toDouble()) }
        }
        exporter.setup()
        var frames = 0L
        PcmStem.Writer(file).use { output ->
            var block = FloatArray(0)
            while (true) {
                checkActive(frames)
                val samples = exporter.render(100.0)?.samples ?: break
                val length = samples.length.toInt()
                if (length == 0) break
                if (block.size < length) block = FloatArray(length)
                for (i in 0 until length) block[i] = samples[i].toFloat()
                frames += length / 2
                checkActive(frames)
                output.write(block, length / 2)
            }
            require(frames > 0) { "这份谱子没有可准备的音频。" }
            return output.stem()
        }
    }

    companion object { private const val SAMPLE_RATE = 44100 }
}
