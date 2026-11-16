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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToInt

/** Single-worker preparation. Cached PCM is pre-amp and can be reused for new gain/EQ/cabinets. */
internal class SynthAmpMix(private val directory: File, soundFont: () -> ByteArray) : Closeable {
    data class Result(val file: File, val syncOffsetMs: Double, val renderedGuitars: Int)
    private data class VoiceState(val tone: GuitarTone, val volume: Double, val balance: Double, val pitch: Double)
    private data class StemKey(val slots: List<Int>, val dry: Boolean, val transpose: Int, val voices: List<VoiceState>)
    private data class Stem(val file: File, val frames: Long)
    private class OpenStem(val input: DataInputStream, var remaining: Long, val amp: GuitarAmp?)
    private val font by lazy { Uint8Array(soundFont().asUByteArray()) }
    private var sourceScore: Score? = null
    private var syncOffsetMs = 0.0
    private val cache = linkedMapOf<StemKey, Stem>()

    fun render(
        score: Score, settings: Settings, tones: List<GuitarTone>, amps: Map<Int, GuitarAmpSettings>,
        transpose: Int, destination: File, cancelled: () -> Boolean, progress: (Int) -> Unit
    ): Result {
        if (sourceScore !== score) { close(); sourceScore = score }
        val tracks = score.tracks.toList()
        val solo = tracks.any { it.playbackInfo.isSolo }
        val audible = tracks.indices.filter { index ->
            val track = tracks[index].playbackInfo
            if (solo) track.isSolo else !track.isMute
        }
        require(audible.isNotEmpty()) { "请至少保留一个可播放声部。" }
        val effected = audible.filter { it in amps && GuitarTone.supports(tracks[it]) }
        val other = audible - effected.toSet()
        val stems = effected.map { listOf(it) to true } + listOfNotNull(other.takeIf { it.isNotEmpty() }?.let { it to false })
        val playbackTones = tracks.indices.map {
            if (it in effected) GuitarTone.Clean else tones.getOrNull(it) ?: GuitarTone.Original
        }
        val router = PracticeToneMidi(score)
        val pitches = DoubleDoubleMap()
        val configured = settings.notation.transpositionPitches
        val trackPitches = tracks.mapIndexed { index, track ->
            if (index < configured.length) configured[index] else -track.staves[0].transpositionPitch
        }
        for ((index, route) in router.channels.withIndex()) {
            for (channel in route.all) pitches.set(channel, trackPitches[index])
        }
        fun checkActive() = check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消准备。" }
        val midi by lazy {
            checkActive()
            val copy = JsonConverter.jsObjectToScore(JsonConverter.scoreToJsObject(score), settings)
            val file = MidiFile()
            val handler = AlphaSynthMidiFileHandler(file)
            MidiFileGenerator(copy, settings, handler).apply { applyTranspositionPitches = false; generate() }
            router.apply(file, settings, playbackTones)
            val bpm = file.events.filterIsInstance<TempoChangeEvent>().firstOrNull()?.beatsPerMinute ?: score.tempo
            syncOffsetMs = handler.tickShift * 60_000.0 / (file.division * bpm.coerceAtLeast(1.0))
            file
        }

        val keys = stems.map { (slots, dry) ->
            StemKey(slots, dry, transpose, slots.map { slot ->
                val info = tracks[slot].playbackInfo
                if (dry) VoiceState(GuitarTone.Clean, 16.0, 8.0, trackPitches[slot])
                else VoiceState(playbackTones[slot], info.volume, info.balance, trackPitches[slot])
            })
        }
        // Keep only the dry material used by the current configuration, with the combined
        // accompaniment as one stem. Gain, EQ and cabinet edits reuse these files.
        for (key in (cache.keys - keys.toSet())) cache.remove(key)?.file?.delete()
        val prepared = keys.mapIndexed { index, key ->
            checkActive()
            val stem = cache[key]?.takeIf { it.file.isFile } ?: run {
                val filtered = selectMidi(midi, key.slots.toSet(), key.dry)
                writeStem(filtered, pitches, transpose, ::checkActive).also { cache[key] = it }
            }
            progress((index + 1) * 75 / keys.size)
            stem to key.slots.singleOrNull()?.takeIf { key.dry }?.let { amps[it] }
        }
        mix(prepared, destination, ::checkActive) { progress(75 + it / 4) }
        return Result(destination, syncOffsetMs, effected.size)
    }

    private fun selectMidi(score: MidiFile, slots: Set<Int>, dry: Boolean): MidiFile = MidiFile().apply {
        division = score.division
        tickShift = score.tickShift
        for (event in score.events) {
            when {
                event is TempoChangeEvent || event is TimeSignatureEvent || event is EndOfTrackEvent -> addEvent(event)
                event.track.toInt() !in slots -> continue
                dry && event is ControlChangeEvent && event.tick <= tickShift &&
                    (event.controller == ControllerType.VolumeCoarse || event.controller == ControllerType.PanCoarse) ->
                    addEvent(ControlChangeEvent(event.track, event.tick, event.channel, event.controller,
                        if (event.controller == ControllerType.VolumeCoarse) 110.0 else 64.0))
                else -> addEvent(event)
            }
        }
    }

    private fun writeStem(midi: MidiFile, pitches: DoubleDoubleMap, transpose: Int, checkActive: () -> Unit): Stem {
        val exporter = AlphaSynthAudioExporter(AudioExportOptions().apply { sampleRate = RATE.toDouble(); masterVolume = 1.0 })
        exporter.loadMidiFile(midi)
        exporter.loadSoundFont(font)
        exporter.applyTranspositionPitches(pitches)
        if (transpose != 0) {
            val channels = midi.events.filterIsInstance<ProgramChangeEvent>().map { it.channel }.distinct()
            channels.filter { it != 9.0 }.forEach { exporter.setChannelTranspositionPitch(it, transpose.toDouble()) }
        }
        exporter.setup()
        val file = File.createTempFile("amp-dry-", ".pcm", directory)
        var frames = 0L
        var success = false
        try {
            DataOutputStream(BufferedOutputStream(file.outputStream(), 64 * 1024)).use { output ->
                while (true) {
                    checkActive()
                    val samples = exporter.render(100.0)?.samples ?: break
                    val length = samples.length.toInt()
                    if (length == 0) break
                    for (i in 0 until length) output.writeFloat(samples[i].toFloat())
                    frames += length / 2
                    require(frames <= MAX_FRAMES) { "音箱混音超过 WAV 文件的长度上限。" }
                }
            }
            require(frames > 0) { "这份谱子没有可准备的音频。" }
            success = true
            return Stem(file, frames)
        } finally { if (!success) file.delete() }
    }

    private fun mix(stems: List<Pair<Stem, GuitarAmpSettings?>>, output: File, checkActive: () -> Unit, progress: (Int) -> Unit) {
        val frames = stems.maxOf { it.first.frames }
        val sources = ArrayList<OpenStem>()
        var success = false
        try {
            for ((stem, settings) in stems) sources.add(OpenStem(
                DataInputStream(BufferedInputStream(stem.file.inputStream(), 32 * 1024)),
                stem.frames, settings?.let { GuitarAmp(it, RATE) }
            ))
            RandomAccessFile(output, "rw").use { wave ->
                wave.setLength(0)
                writeWaveHeader(wave, frames)
                val track = FloatArray(BLOCK * 2)
                val total = FloatArray(BLOCK * 2)
                val bytes = ByteArray(BLOCK * 4)
                var position = 0L
                var lastProgress = -1
                while (position < frames) {
                    checkActive()
                    val count = minOf(BLOCK.toLong(), frames - position).toInt()
                    total.fill(0f)
                    for (source in sources) {
                        val read = minOf(count.toLong(), source.remaining).toInt()
                        for (i in 0 until read * 2) track[i] = source.input.readFloat()
                        track.fill(0f, read * 2, count * 2)
                        source.remaining -= read
                        source.amp?.process(track, count)
                        for (i in 0 until count * 2) total[i] += track[i]
                    }
                    for (i in 0 until count * 2) {
                        val sample = total[i].toDouble() * 0.86
                        require(sample.isFinite()) { "音箱混音产生了无效音频。" }
                        val magnitude = abs(sample)
                        val limited = if (magnitude < 0.82) sample else
                            Math.copySign(0.82 + 0.17 * (1.0 - kotlin.math.exp(-(magnitude - 0.82) / 0.17)), sample)
                        val pcm = (limited * 32767).roundToInt()
                        bytes[i * 2] = pcm.toByte(); bytes[i * 2 + 1] = (pcm shr 8).toByte()
                    }
                    wave.write(bytes, 0, count * 4)
                    position += count
                    val percent = (position * 100 / frames).toInt()
                    if (percent != lastProgress) { progress(percent); lastProgress = percent }
                }
            }
            success = true
        } finally {
            sources.forEach { it.input.close() }
            if (!success) output.delete()
        }
    }

    override fun close() {
        cache.values.forEach { it.file.delete() }
        cache.clear()
        sourceScore = null
        syncOffsetMs = 0.0
    }

    private fun writeWaveHeader(output: RandomAccessFile, frames: Long) {
        fun int(value: Int) = output.writeInt(Integer.reverseBytes(value))
        fun short(value: Short) = output.writeShort(java.lang.Short.reverseBytes(value).toInt())
        val bytes = (frames * 4).toInt()
        output.writeBytes("RIFF"); int(bytes + 36); output.writeBytes("WAVEfmt "); int(16)
        short(1); short(2); int(RATE); int(RATE * 4); short(4); short(16)
        output.writeBytes("data"); int(bytes)
    }

    companion object {
        private const val RATE = 44100
        private const val BLOCK = 1024
        private const val MAX_FRAMES = (Int.MAX_VALUE - 36L) / 4
    }
}
