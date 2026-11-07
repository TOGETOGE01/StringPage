@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

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
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Result {
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
        val channelBuffer = FloatArray(1024); val silence = FloatArray(0); val bytes = ByteArray(2048)
        var position = 0L; var actionIndex = 0; var lastProgress = -1; var peak = 0f; var clipped = 0L
        var success = false
        try {
            RandomAccessFile(destination, "rw").use { output ->
                output.setLength(0); output.write(ByteArray(44))
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
                                val voice = NativeSampleVoice(audio.decoded, SAMPLE_RATE, basePitch, gain, source.pitchCorrection,
                                    loop?.start?.toInt(), loop?.end?.toInt(), decoderGain, source.amplitudeCorrection,start.toInt())
                                voices.add(Active(event, voice, basePitch, audio,releaseFrames=envelope.fadeOutFrames,
                                    attack=NativeAttackEnvelope(envelope.fadeInFrames)))
                            }
                        }
                    }
                    val next = actions.getOrNull(actionIndex)?.frame ?: totalFrames
                    val count = minOf(512L, totalFrames - position, (next - position).coerceAtLeast(1)).toInt()
                    mixed.fill(0f)
                    val grouped = voices.groupBy { channel(it.event.track, it.event.channel) }
                    for (state in channels.values) {
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
                        for (i in 0 until count * 2) mixed[i] += channelBuffer[i]
                    }
                    for (i in 0 until count * 2) {
                        val sample = mixed[i]; require(sample.isFinite()) { "生成音频包含无效数据。" }
                        peak = maxOf(peak, kotlin.math.abs(sample)); if (kotlin.math.abs(sample) > 1f) clipped++
                        val value = (sample.coerceIn(-1f,1f) * 32767f).roundToInt()
                        bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    output.write(bytes, 0, count * 4); position += count
                    voices.removeAll { it.voice.finished || it.releaseAt?.let { at -> position - at >= it.releaseFrames } == true }
                    val percentage = (position * 100 / totalFrames).toInt()
                    if (percentage != lastProgress) { progress(percentage); lastProgress = percentage }
                }
                output.seek(0)
                fun int(value: Long) { output.writeInt(Integer.reverseBytes(value.toInt())) }
                fun short(value: Int) { output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt()) }
                output.writeBytes("RIFF"); int(totalFrames * 4 + 36); output.writeBytes("WAVEfmt "); int(16)
                short(1); short(2); int(SAMPLE_RATE.toLong()); int(SAMPLE_RATE * 4L); short(4); short(16)
                output.writeBytes("data"); int(totalFrames * 4)
            }
            success = true
            return Result(destination, totalFrames * 1000.0 / SAMPLE_RATE, notes.size, substitutions, peak, clipped,
                performance.rseSyncOffsetMs,performance.rseFirstCueMs)
        } finally { cache.clear(); voices.clear(); if (!success) destination.delete() }
    }
}
