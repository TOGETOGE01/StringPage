@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.synth.IExternalMediaHandler
import alphaTab.synth.IExternalMediaSynthOutput
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Streams the mix, with the Android playback head as the score's clock. */
internal class StemAudio(
    private val stems: AmpStems, initial: Map<Int, GuitarAmpSettings>, private val failed: (Exception) -> Unit
) : IExternalMediaHandler, Closeable {
    private val ui = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("StringPage guitar audio", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val worker = Handler(thread.looper)
    private val sampleRate = stems.sampleRate
    private val samples = FloatArray(BLOCK * 2)
    private var mixer: LiveAmpMixer? = null
    private var track: AudioTrack? = null
    private var sourceStart = 0L
    private var written = 0L
    private var played = 0L
    private var blockOffset = 0
    private var blockSamples = 0
    private var underruns = 0
    private var recoveredBufferFrames = 0
    private var workerPlaying = false
    private var workerEpoch = 0
    private var rate = 1.0
    private var volume = 1.0
    private var playing = false
    private var epoch = 0
    private var desiredLoop: Loop? = null
    private var queuedLoop: Loop? = null
    private var reportedWrap = 0L
    private var reportingWrap = false
    private var nextSourceFrame = 0L

    private data class Position(val epoch: Int, val frame: Long, val wraps: Long = 0)
    private data class Loop(val start: Long, val end: Long) {
        private val length get() = end - start
        fun wraps(frame: Long) = if (frame < end) 0 else 1 + (frame - end) / length
        fun wrap(frame: Long) = if (frame < end) frame else start + (frame - end) % length
    }
    private val clock = AtomicReference(Position(0, 0))
    @Volatile private var settings = initial.toMap()
    @Volatile private var closed = false

    val currentPositionMs: Double get() = ms(clock.get().frame)
    val completedLoops get() = clock.get().wraps
    val firstCueMs get() = stems.firstCueMs
    val guitarTracks get() = stems.guitarTracks
    val syncOffsetMs get() = stems.syncOffsetMs
    override val backingTrackDuration get() = ms(stems.frames)

    var output: IExternalMediaSynthOutput? = null
        set(value) {
            if (field === value) return
            if (field?.handler === this) field?.handler = null
            field = value
            value?.handler = this
        }

    override var playbackRate: Double
        get() = rate
        set(value) {
            if (rate == value) return
            rate = value
            command { applyRate(value) }
        }
    override var masterVolume: Double
        get() = volume
        set(value) {
            volume = value
            command { track?.setVolume(value.toFloat()) }
        }

    fun setAmps(newSettings: Map<Int, GuitarAmpSettings>) { settings = newSettings.toMap() }

    fun open(ready: () -> Unit) {
        val initialVolume = volume
        val initialSpeed = rate
        command {
            mixer = LiveAmpMixer(stems, BLOCK).also { it.seek(0, settings) }
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minimum > 0) { "设备无法打开实时吉他音频。" }
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, BLOCK * 8 * 4)).build()
            check(track?.state == AudioTrack.STATE_INITIALIZED) { "设备无法打开实时吉他音频。" }
            track?.setVolume(initialVolume.toFloat())
            applyRate(initialSpeed)
            ui.post { if (!closed) ready() }
        }
    }

    private fun applyRate(speed: Double) {
        val device = track ?: return
        device.playbackParams = PlaybackParams().setSpeed(speed.toFloat()).setPitch(1f)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
        // A full native buffer delays new guitar settings longer in wall time
        // at slower practice speeds. Keep its capacity available for recovery.
        val preferred = BLOCK * if (speed < 0.5) 3 else 4
        device.setBufferSizeInFrames(maxOf(preferred, recoveredBufferFrames).coerceAtMost(device.bufferCapacityInFrames))
    }

    private fun allowForUnderruns(device: AudioTrack) {
        val count = device.underrunCount
        if (count > underruns && device.bufferSizeInFrames < device.bufferCapacityInFrames) {
            recoveredBufferFrames = (device.bufferSizeInFrames + BLOCK).coerceAtMost(device.bufferCapacityInFrames)
            device.setBufferSizeInFrames(recoveredBufferFrames)
        }
        underruns = count
    }

    override fun seekTo(time: Double) {
        val frame = cueFrame(time)
        // alphaTab acknowledges the loop by requesting a seek at the start.
        // The new loop is already queued against the same AudioTrack clock.
        if (reportingWrap && desiredLoop?.let { abs(it.start - frame) < sampleRate / 100 } == true) return
        seek(frame)
    }

    fun restorePausedPosition(time: Double) = seek(frameAt(time))

    fun setLoop(startMs: Double?, endMs: Double?) {
        val next = if (startMs != null && endMs != null) {
            val start = cueFrame(startMs)
            val end = ceil(endMs * sampleRate / 1000.0).toLong().coerceIn(0, stems.frames)
            if (end > start) Loop(start, end) else null
        } else null
        if (next == desiredLoop) return
        desiredLoop = next
        seek(clock.get().frame)
    }

    private fun seek(requested: Long) {
        if (closed) return
        val loop = desiredLoop
        val frame = if (loop != null && requested >= loop.end) loop.start else requested
        val seekEpoch = ++epoch
        reportedWrap = 0
        clock.set(Position(seekEpoch, frame))
        command {
            if (clock.get().epoch != seekEpoch) return@command
            val device = track ?: return@command
            worker.removeCallbacks(pump)
            device.pause()
            device.flush()
            workerEpoch = seekEpoch
            sourceStart = frame
            nextSourceFrame = frame
            queuedLoop = loop
            written = 0L
            played = 0L
            blockSamples = 0
            blockOffset = 0
            mixer?.seek(frame, settings)
            if (workerPlaying) startDevice(device)
            ui.post { if (!closed && epoch == seekEpoch) output?.updatePosition(ms(frame)) }
        }
    }

    override fun play() {
        if (playing || closed) return
        playing = true
        command {
            workerPlaying = true
            track?.let(::startDevice)
        }
        ui.post(tick)
    }

    private fun startDevice(device: AudioTrack) {
        if (device.playState != AudioTrack.PLAYSTATE_PLAYING) {
            fill(device)
            device.play()
        }
        worker.removeCallbacks(pump)
        worker.post(pump)
    }

    override fun pause() {
        playing = false
        ui.removeCallbacks(tick)
        command {
            workerPlaying = false
            worker.removeCallbacks(pump)
            track?.let { device -> device.pause(); updateClock(device) }
        }
    }

    private fun updateClock(device: AudioTrack): Boolean {
        val head = device.playbackHeadPosition.toLong() and 0xffffffffL
        played = maxOf(played, head.coerceAtMost(written))
        val unwrapped = sourceStart + played
        val loop = queuedLoop
        val frame = loop?.wrap(unwrapped) ?: minOf(unwrapped, stems.frames)
        val current = clock.get()
        val wraps = loop?.wraps(unwrapped) ?: 0
        if (current.epoch == workerEpoch && (current.frame != frame || current.wraps != wraps)) {
            clock.compareAndSet(current, Position(workerEpoch, frame, wraps))
        }
        return frame == stems.frames
    }

    private fun fill(device: AudioTrack) {
        // Limit the mix-ahead queue to the platform buffer. Render at most
        // two blocks per turn so pending seek, pause and speed changes can run.
        repeat(2) {
            if (blockOffset == blockSamples) {
                val loop = queuedLoop
                val unwrapped = sourceStart + written
                val frame = loop?.wrap(unwrapped) ?: unwrapped
                val end = loop?.end ?: stems.frames
                if (frame >= end + TAIL) return
                val count = if (frame < end) {
                    val count = minOf(BLOCK.toLong(), end - frame).toInt()
                    val controls = settings
                    if (frame != nextSourceFrame) mixer?.seek(frame, controls)
                    mixer?.read(samples, count, controls)
                    if (loop != null) fadeLoopEdges(frame, count, loop)
                    nextSourceFrame = frame + count
                    count
                } else {
                    samples.fill(0f)
                    minOf(BLOCK.toLong(), end + TAIL - frame).toInt()
                }
                blockOffset = 0
                blockSamples = count * 2
            }
            val result = device.write(samples, blockOffset, blockSamples - blockOffset, AudioTrack.WRITE_NON_BLOCKING)
            check(result >= 0) { "实时吉他音频输出失败（$result）。" }
            if (result == 0) return
            check(result % 2 == 0)
            blockOffset += result
            written += result / 2
        }
    }

    private fun fadeLoopEdges(frame: Long, count: Int, loop: Loop) {
        val edge = minOf(sampleRate * 3L / 1000, (loop.end - loop.start) / 2).coerceAtLeast(1)
        if (frame >= loop.start + edge && frame + count <= loop.end - edge) return
        for (i in 0 until count) {
            val at = frame + i
            if (at < loop.start) continue
            val gain = minOf(at - loop.start, loop.end - at - 1, edge).toFloat() / edge
            samples[i * 2] *= gain
            samples[i * 2 + 1] *= gain
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            val device = track ?: return
            if (closed || !workerPlaying) return
            try {
                if (updateClock(device)) return
                allowForUnderruns(device)
                fill(device)
                worker.postDelayed(this, 4)
            } catch (error: Exception) { reportFailure(error) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed || !playing) return
            val current = clock.get()
            if (current.epoch == epoch) {
                val loop = desiredLoop
                if (loop != null && current.wraps > reportedWrap) {
                    reportedWrap = current.wraps
                    reportingWrap = true
                    try { output?.updatePosition(ms(loop.end) + 0.1) }
                    finally { reportingWrap = false }
                }
                if (current.epoch == epoch) output?.updatePosition(ms(current.frame))
            }
            if (playing && !closed && (clock.get().epoch != current.epoch || current.frame < stems.frames)) ui.postDelayed(this, 25)
        }
    }

    private fun command(action: () -> Unit) {
        if (!closed) worker.post {
            if (closed) return@post
            try { action() } catch (error: Exception) { reportFailure(error) }
        }
    }

    private fun reportFailure(error: Exception) {
        ui.post {
            if (!closed) {
                close()
                failed(error)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        output = null
        ui.removeCallbacksAndMessages(null)
        worker.removeCallbacksAndMessages(null)
        worker.post {
            try { track?.release() } finally {
                try { mixer?.close() } finally { stems.close(); thread.quitSafely() }
            }
        }
    }

    private fun ms(frame: Long) = frame * 1000.0 / sampleRate
    private fun frameAt(time: Double) = (time * sampleRate / 1000.0).roundToLong().coerceIn(0, stems.frames)
    private fun cueFrame(time: Double) = if (firstCueMs > 0.0 && abs(time - firstCueMs) < 1.0) 0 else frameAt(time)

    companion object {
        private const val BLOCK = 512
        private const val TAIL = 4096
    }
}
