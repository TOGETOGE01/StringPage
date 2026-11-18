@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

import com.stringpage.app.GuitarAmp
import com.stringpage.app.GuitarAmpSettings
import com.stringpage.app.AmpStems
import com.stringpage.app.PcmStem
import com.stringpage.app.SourceBeatMap
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Streaming preparation of a private playback file. It uses original samples,
 * generated GP performance events and bounded PCM caching; the sampled voice's
 * native attack/release and 32-frame resampler are applied to each voice.
 * Complete native slide/tick scheduling and mixer calibration remain.
 */
object FactoryScoreRenderer {
    const val SAMPLE_RATE = 44100
    data class Route(val track: Int, val percussionMidi: Int? = null)
    data class Result(val file: File, val durationMs: Double, val noteCount: Int,
        val substitutedArticulations: Int, val peak: Float, val clippedSamples: Long,
        val syncOffsetMs: Double = 0.0, val firstCueMs: Double = 0.0)
    internal data class StemResult(val stems: AmpStems, val noteCount: Int, val substitutedArticulations: Int)
    private data class MixStats(val frames: Long, val noteCount: Int, val substitutions: Int)
    private data class Source(val item: BankLibrary.Item, val wave: RseWave, val transposition: Int,
        val nativeMidi: Int, val pitchCorrection: Float, val amplitudeCorrection: Float, val dynamicCorrection: Float)
    private data class Audio(val decoded: DecodedBankSample, val metadata: RseAudioMetadata)
    private data class Action(val frame: Long, val priority: Int, val note: ScorePerformance.NoteEvent? = null,
        val pitch: ScorePerformance.PitchEvent? = null, val control: ScorePerformance.ControlEvent? = null)
    private class Channel {
        var volume = 1f; var expression = 1f
        val pan = NativeVolumePanProcessor()
        var wheel = 8192.0; var bendRange = 2.0; var rpnHigh = 127; var rpnLow = 127
        val noteWheels = HashMap<Int, Double>()
    }
    private data class Active(val event: ScorePerformance.NoteEvent, val voice: NativeSampleVoice,
        val basePitch: Double, val audio: Audio, var releaseAt: Long? = null,
        val releaseFrames: Long = NativeNoteAmplitude.DEFAULT_FADE_OUT_FRAMES,
        val attack: NativeAttackEnvelope = NativeAttackEnvelope())

    fun render(performance: ScorePerformance, bindings: Map<Route, BankLibrary.Item>,
        destination: File, mutedTracks: Set<Int> = emptySet(), transposition: Int = 0,
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Result =
        renderWithAmps(performance, bindings, destination, mutedTracks, transposition, emptyMap(), cancelled, progress)

    internal fun renderWithAmps(
        performance: ScorePerformance, bindings: Map<Route, BankLibrary.Item>, destination: File,
        mutedTracks: Set<Int>, transposition: Int, ampSettings: Map<Int, GuitarAmpSettings>,
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}
    ): Result {
        var peak = 0f
        var clipped = 0L
        val bytes = ByteArray(2048)
        var success = false
        try {
            RandomAccessFile(destination, "rw").use { output ->
                output.setLength(0); output.write(ByteArray(44))
                val stats = renderBlocks(performance, bindings, mutedTracks, transposition, ampSettings, emptySet(),
                    cancelled, progress) { mixed, _, count ->
                    for (i in 0 until count * 2) {
                        val sample = mixed[i]
                        require(sample.isFinite()) { "生成音频包含无效数据。" }
                        peak = maxOf(peak, kotlin.math.abs(sample))
                        if (kotlin.math.abs(sample) > 1f) clipped++
                        val value = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt()
                        bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    output.write(bytes, 0, count * 4)
                }
                output.seek(0)
                fun int(value: Long) = output.writeInt(Integer.reverseBytes(value.toInt()))
                fun short(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
                output.writeBytes("RIFF"); int(stats.frames * 4 + 36); output.writeBytes("WAVEfmt "); int(16)
                short(1); short(2); int(SAMPLE_RATE.toLong()); int(SAMPLE_RATE * 4L); short(4); short(16)
                output.writeBytes("data"); int(stats.frames * 4)
                success = true
                return Result(destination, stats.frames * 1000.0 / SAMPLE_RATE, stats.noteCount, stats.substitutions, peak,
                    clipped, performance.rseSyncOffsetMs, performance.rseFirstCueMs)
            }
        } finally { if (!success) destination.delete() }
    }

    internal fun prepareStems(
        performance: ScorePerformance, bindings: Map<Route, BankLibrary.Item>, directory: File,
        mutedTracks: Set<Int>, transposition: Int, guitarTracks: Set<Int>,
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}
    ): StemResult {
        val audible = performance.notes.filter { it.track !in mutedTracks && it.velocity > 0 }.map { it.track }.toSet()
        val guitars = audible.intersect(guitarTracks)
        val slots: List<Int?> = if (audible == guitars) guitars.toList() else guitars.toList() + null
        val bytes = (performance.durationMs * SAMPLE_RATE / 1000.0).roundToLong() * PcmStem.FRAME_BYTES * slots.size
        require(bytes <= minOf(512L * 1024 * 1024, directory.usableSpace - 64L * 1024 * 1024)) {
            "采样干声需要更多可用空间，请减少参与播放的声部。"
        }
        val paths = ArrayList<File>()
        val writers = linkedMapOf<Int?, PcmStem.Writer>()
        var success = false
        try {
            for (slot in slots) {
                val file = File.createTempFile("amp-dry-", ".pcm", directory).also(paths::add)
                writers[slot] = PcmStem.Writer(file)
            }
            val stats = renderBlocks(performance, bindings, mutedTracks, transposition, emptyMap(), guitars,
                cancelled, progress) { mixed, dry, count ->
                for ((slot, writer) in writers) writer.write(if (slot == null) mixed else dry.getValue(slot), count)
            }
            val parts = writers.map { (slot, writer) -> AmpStems.Part(slot, writer.stem()) }
            while (writers.isNotEmpty()) writers.remove(writers.keys.first())?.close()
            val stems = AmpStems(parts, SAMPLE_RATE, performance.rseSyncOffsetMs, performance.rseFirstCueMs, headroom = 1.0,
                beats = beatMap(performance))
            return StemResult(stems, stats.noteCount, stats.substitutions).also { success = true }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            if (!success) paths.forEach { it.delete() }
        }
    }

    private fun beatMap(performance: ScorePerformance) = SourceBeatMap(SAMPLE_RATE,
        performance.tempos.map { SourceBeatMap.Tempo(it.timeMs, it.bpm) })

    private fun renderBlocks(
        performance: ScorePerformance, bindings: Map<Route, BankLibrary.Item>,
        mutedTracks: Set<Int>, transposition: Int, ampSettings: Map<Int, GuitarAmpSettings>,
        separateTracks: Set<Int>, cancelled: () -> Boolean, progress: (Int) -> Unit,
        block: (FloatArray, Map<Int, FloatArray>, Int) -> Unit
    ): MixStats {
        require(transposition in -12..12)
        val notes = performance.notes.filter { it.track !in mutedTracks && it.velocity > 0 }
        require(notes.isNotEmpty()) { "没有参与播放的音符。" }
        val totalFrames = (performance.durationMs * SAMPLE_RATE / 1000.0).roundToLong()
        require(totalFrames in 1..(256L * 1024 * 1024 / 4)) { "乐谱过长，生成音频不能超过 256 MB。" }
        fun frame(ms: Double) = (ms * SAMPLE_RATE / 1000.0).roundToLong().coerceAtLeast(0)
        fun checkActive() { check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消准备。" } }
        val selectors = HashMap<File, FactorySampleSelector>()
        var substitutions = performance.rsePartialTechniques
        val sources = notes.associate { note ->
            checkActive()
            val item = bindings[Route(note.track, note.midi)] ?: bindings[Route(note.track)]
                ?: throw IllegalArgumentException("声部 ${note.track + 1} 尚未分配音色库。")
            val selected = selectors.getOrPut(item.file) { FactorySampleSelector(item.bank) }.select(note)
            if (selected.substitutedArticulation) substitutions++
            note.id to Source(item, selected.wave, if (bindings.containsKey(Route(note.track,note.midi))) 0 else transposition,
                selected.nativeMidi, selected.pitchCorrection, selected.amplitudeCorrection, selected.dynamicCorrection)
        }
        val actions = ArrayList<Action>()
        for (note in notes) { actions.add(Action(frame(note.startMs), 3, note)); actions.add(Action(frame(note.endMs), 1, note)) }
        for (event in performance.pitches) actions.add(Action(frame(event.timeMs), 2, pitch = event))
        for (event in performance.controls) actions.add(Action(frame(event.timeMs), 0, control = event))
        actions.sortWith(compareBy<Action> { it.frame }.thenBy { it.priority })
        val channels = HashMap<Pair<Int, Int>, Channel>()
        fun channel(track: Int, channel: Int) = channels.getOrPut(track to channel) { Channel() }
        val voices = ArrayList<Active>()
        val cache = object : LinkedHashMap<Pair<File, String>, Audio>(16, 0.75f, true) {}
        var cacheBytes = 0L
        fun load(source: Source): Audio {
            val key = source.item.file to source.wave.file
            cache[key]?.let { return it }
            checkActive()
            val reader = Gp7Bank.open(source.item.file)
            val metadata = reader.readAudioMetadata(source.wave.entry)
            val raw = BankSampleDecoder.decode(source.item.file, source.wave.entry.offset, source.wave.entry.length, cancelled)
            require(raw.sampleRate.toLong() == metadata.sampleRate) { "采样解码后的采样率与音色库不一致。" }
            val decoded = NativeSourceRateConverter.normalize(raw, cancelled)
            val audio = Audio(decoded, metadata)
            val bytes = decoded.samples.size.toLong() * 4
            while (cache.isNotEmpty() && cacheBytes + bytes > 48L * 1024 * 1024) {
                val first = cache.entries.iterator().next(); cacheBytes -= first.value.decoded.samples.size.toLong() * 4; cache.remove(first.key)
            }
            cache[key] = audio; cacheBytes += bytes
            return audio
        }
        val mixed = FloatArray(1024); val voiceBuffer = FloatArray(1024)
        val channelBuffer = FloatArray(1024); val silence = FloatArray(0)
        val audibleTracks = notes.mapTo(mutableSetOf()) { it.track }
        val tempo by lazy { beatMap(performance) }
        val amps = ampSettings.filter { (track, settings) -> settings.enabled && track in audibleTracks }
            .mapValues { GuitarAmp(it.value, SAMPLE_RATE, tempo) }
        val dryBuffers = (amps.keys + separateTracks).associateWith { FloatArray(1024) }
        var position = 0L; var actionIndex = 0; var lastProgress = -1
        try {
            while (position < totalFrames) {
                checkActive()
                while (actionIndex < actions.size && actions[actionIndex].frame <= position) {
                    val action = actions[actionIndex++]
                    action.control?.let { event ->
                        val state = channel(event.track, event.channel)
                        when (event.controller) {
                            7 -> state.volume = event.value / 127f; 11 -> state.expression = event.value / 127f
                            10 -> state.pan.pan = event.value / 127f
                            101 -> state.rpnHigh = event.value; 100 -> state.rpnLow = event.value
                            6 -> if (state.rpnHigh == 0 && state.rpnLow == 0) state.bendRange = event.value.toDouble()
                        }
                    }
                    action.pitch?.let { event ->
                        val state = channel(event.track, event.channel)
                        if (event.midi == null) state.wheel = event.value else state.noteWheels[event.midi] = event.value
                    }
                    action.note?.let { event ->
                        if (action.priority == 1) {
                            voices.firstOrNull { it.event.id == event.id }?.releaseAt = position
                            channel(event.track, event.channel).noteWheels.remove(event.midi)
                        } else {
                            require(voices.size < 128) { "同时发声过多，请减少参与播放的声部。" }
                            val source = sources.getValue(event.id); val audio = load(source)
                            val distinct = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Audio, Boolean>())
                            distinct.add(audio); voices.forEach { distinct.add(it.audio) }
                            val activeBytes = distinct.sumOf { it.decoded.samples.size.toLong() * 4 }
                            require(activeBytes <= 64L * 1024 * 1024) { "同时发声的采样过大，请减少声部。" }
                            val loop = NativeStreamPolicy.forwardLoop(source.wave.file,
                                audio.metadata.sampler?.loops.orEmpty(), audio.decoded.frames, audio.metadata.sampleRate.toInt())
                            val basePitch = if (source.nativeMidi < 0) 0.0 else event.midi - source.nativeMidi + source.transposition.toDouble()
                            // Decoder gain precedes resampling; last-Wave ratio
                            // follows it, then note-dynamic compensation. Fixed
                            // application headroom remains explicitly independent.
                            val technique = NativeNoteTechnique.apply(event.sourceNote,source.dynamicCorrection)
                            val envelope = event.slideVoice?.state ?: NativeFadePolicy.State(startingCuePoint=technique.cuePoint)
                            if (technique.incompleteSource) substitutions++
                            val gain = NativeSampleGain.COMPATIBILITY_HEADROOM * technique.amplitude
                            val loopStart = audio.metadata.sampler?.loops?.firstOrNull()?.start ?: 0L
                            val start = NativeSampleStart.initialFrame(envelope.startingCuePoint,
                                NativeSampleStart.decoderStreamCuePoints(audio.metadata.nativeCues.map { it.sampleOffset.toInt().toLong() }),loopStart,envelope.userDefinedFrame)
                            require(start <= audio.decoded.frames) { "采样起点超出可播放音频。" }
                            val decoderGain = NativeSampleGain.DEFAULT_DECODER_GAIN * RseBankMetadata.waveGain(source.wave)
                            val voiceStart=position
                            val voice = NativeSampleVoice(audio.decoded, SAMPLE_RATE, basePitch, gain, source.pitchCorrection,
                                loop?.start?.toInt(), loop?.end?.toInt(), decoderGain, source.amplitudeCorrection,start.toInt(),
                                pitchModulation=event.nativeBend?.let { plan -> { elapsed ->
                                    plan.rawValue(RseBendClock.tick(performance,event.startTick,voiceStart,elapsed,SAMPLE_RATE))
                                } })
                            voices.add(Active(event, voice, basePitch, audio,releaseFrames=envelope.fadeOutFrames,
                                attack=NativeAttackEnvelope(envelope.fadeInFrames)))
                        }
                    }
                }
                val next = actions.getOrNull(actionIndex)?.frame ?: totalFrames
                val count = minOf(512L, totalFrames - position, (next - position).coerceAtLeast(1)).toInt()
                mixed.fill(0f)
                dryBuffers.values.forEach { it.fill(0f) }
                val grouped = voices.groupBy { channel(it.event.track, it.event.channel) }
                for ((route, state) in channels) {
                    val activeVoices = grouped[state]
                    if (activeVoices == null) {
                        // A silent active block reaches the same control state as
                        // a zero-frame block, without allocating silent PCM per track.
                        state.pan.processStereo(silence, 0)
                        continue
                    }
                    channelBuffer.fill(0f)
                    for (active in activeVoices) {
                        val wheel = state.wheel + (state.noteWheels[active.event.midi] ?: 8192.0) - 8192.0
                        val bend = if (wheel == 8192.0) 0.0 else wheel / 16383.0 * state.bendRange * 2 - state.bendRange
                        active.voice.pitchSemitones = active.basePitch + bend
                        active.voice.render(voiceBuffer, count)
                        active.attack.applyTick(voiceBuffer, 2, count, count.toLong())
                        active.releaseAt?.let { at ->
                            NativeNoteAmplitude.applyRelease(voiceBuffer, 2, count, position - at, active.releaseFrames)
                        }
                        // Authored MIDI volume/expression remain linear compatibility
                        // controls until their mapping to the native fader is verified.
                        val gain = state.volume * state.expression
                        for (i in 0 until count) {
                            channelBuffer[i * 2] += voiceBuffer[i * 2] * gain
                            channelBuffer[i * 2 + 1] += voiceBuffer[i * 2 + 1] * gain
                        }
                    }
                    // One native processor per channel, independently of voice
                    // count. Our event-split scheduling is not native timing parity.
                    state.pan.processStereo(channelBuffer, count)
                    val target = dryBuffers[route.first] ?: mixed
                    for (i in 0 until count * 2) target[i] += channelBuffer[i]
                }
                for ((track, amp) in amps) {
                    val dry = dryBuffers.getValue(track)
                    amp.process(dry, count)
                    for (i in 0 until count * 2) mixed[i] += dry[i]
                }
                block(mixed, dryBuffers, count)
                position += count
                voices.removeAll { it.voice.finished || it.releaseAt?.let { at -> position - at >= it.releaseFrames } == true }
                val percentage = (position * 100 / totalFrames).toInt()
                if (percentage != lastProgress) { progress(percentage); lastProgress = percentage }
            }
            return MixStats(totalFrames, notes.size, substitutions)
        } finally { cache.clear(); voices.clear() }
    }
}
