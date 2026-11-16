package com.stringpage.app

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

internal data class PcmStem(val file: File, val frames: Long) {
    class Writer(val file: File) : Closeable {
        private val stream = BufferedOutputStream(file.outputStream(), 64 * 1024)
        private val bytes = ByteBuffer.allocate(4096 * FRAME_BYTES)
        private val samplesView = bytes.asFloatBuffer()
        private var frames = 0L

        fun write(samples: FloatArray, count: Int) {
            var offset = 0
            while (offset < count) {
                val block = minOf(count - offset, bytes.capacity() / FRAME_BYTES)
                samplesView.clear()
                samplesView.put(samples, offset * 2, block * 2)
                stream.write(bytes.array(), 0, block * FRAME_BYTES)
                offset += block
            }
            frames += count
        }

        fun stem() = PcmStem(file, frames)
        override fun close() = stream.close()
    }

    class Reader(private val stem: PcmStem, capacity: Int) : Closeable {
        private val file = RandomAccessFile(stem.file, "r")
        private val bytes = ByteBuffer.allocate(capacity * FRAME_BYTES)
        private val samplesView = bytes.asFloatBuffer()

        fun read(frame: Long, target: FloatArray, count: Int) {
            val available = minOf(count.toLong(), (stem.frames - frame).coerceAtLeast(0)).toInt()
            if (available > 0) {
                val offset = frame * FRAME_BYTES
                if (file.filePointer != offset) file.seek(offset)
                file.readFully(bytes.array(), 0, available * FRAME_BYTES)
                samplesView.clear()
                samplesView.get(target, 0, available * 2)
            }
            target.fill(0f, available * 2, count * 2)
        }

        override fun close() = file.close()
    }

    companion object { const val FRAME_BYTES = 8 }
}

/** Owns private, time-aligned stereo PCM. A null track denotes fixed accompaniment. */
internal class AmpStems(
    val parts: List<Part>, val sampleRate: Int, val syncOffsetMs: Double,
    val firstCueMs: Double = 0.0, val headroom: Double = 0.86
) : Closeable {
    data class Part(val track: Int?, val original: PcmStem, val dry: PcmStem = original)

    val frames = parts.maxOf { maxOf(it.original.frames, it.dry.frames) }
    val guitarTracks = parts.mapNotNull { it.track }.toSet()

    override fun close() {
        parts.flatMap { listOf(it.original.file, it.dry.file) }.distinct().forEach { it.delete() }
    }
}

/** All mutable DSP and file readers are confined to the transport worker. */
internal class LiveAmpMixer(private val stems: AmpStems, private val block: Int) : Closeable {
    private val warmup = stems.sampleRate * 45 / 1000
    private val transitionFrames = stems.sampleRate * 12 / 1000
    private val input = FloatArray(block * 2)
    private val outgoing = FloatArray(block * 2)
    private val incoming = FloatArray(block * 2)
    private val tracks = ArrayList<PartMixer>()
    private var position = 0L

    init {
        try { stems.parts.forEach { tracks += PartMixer(it) } }
        catch (error: Exception) { close(); throw error }
    }

    private inner class PartMixer(part: AmpStems.Part) : Closeable {
        val index = part.track
        val original = PcmStem.Reader(part.original, block)
        val dry = try { if (part.original == part.dry) original else PcmStem.Reader(part.dry, block) }
        catch (error: Exception) { original.close(); throw error }
        var config: GuitarAmpSettings? = null
        var amp: GuitarAmp? = null
        var nextConfig: GuitarAmpSettings? = null
        var nextAmp: GuitarAmp? = null
        var transition = transitionFrames

        fun processor(settings: GuitarAmpSettings?): GuitarAmp? {
            if (settings == null) return null
            val processor = GuitarAmp(settings, stems.sampleRate)
            var cursor = (position - warmup).coerceAtLeast(0)
            while (cursor < position) {
                val count = minOf(block.toLong(), position - cursor).toInt()
                dry.read(cursor, input, count)
                processor.process(input, count)
                cursor += count
            }
            return processor
        }

        fun reset(settings: GuitarAmpSettings?) {
            config = settings; amp = processor(settings)
            nextConfig = null; nextAmp = null; transition = transitionFrames
        }

        fun add(target: FloatArray, frames: Int, settings: GuitarAmpSettings?) {
            if (transition == transitionFrames && settings != config) {
                nextConfig = settings; nextAmp = processor(settings); transition = 0
            }
            val changing = transition < transitionFrames
            if (amp != null || (changing && nextAmp != null)) dry.read(position, input, frames)
            fun render(active: GuitarAmp?, buffer: FloatArray) {
                if (active == null) original.read(position, buffer, frames)
                else {
                    input.copyInto(buffer, endIndex = frames * 2)
                    active.process(buffer, frames)
                }
            }
            render(amp, outgoing)
            if (changing) render(nextAmp, incoming)
            for (frame in 0 until frames) {
                val mix = if (changing) (transition + frame).coerceAtMost(transitionFrames).toFloat() / transitionFrames else 0f
                val i = frame * 2
                target[i] += outgoing[i] + if (changing) (incoming[i] - outgoing[i]) * mix else 0f
                target[i + 1] += outgoing[i + 1] + if (changing) (incoming[i + 1] - outgoing[i + 1]) * mix else 0f
            }
            if (changing) {
                transition = (transition + frames).coerceAtMost(transitionFrames)
                if (transition == transitionFrames) {
                    config = nextConfig; amp = nextAmp; nextAmp = null
                }
            }
        }

        override fun close() {
            try { original.close() } finally { if (dry !== original) dry.close() }
        }
    }

    fun seek(frame: Long, settings: Map<Int, GuitarAmpSettings>) {
        position = frame
        tracks.forEach { it.reset(it.index?.let(settings::get)) }
    }

    fun read(target: FloatArray, count: Int, settings: Map<Int, GuitarAmpSettings>) {
        target.fill(0f, toIndex = count * 2)
        for (track in tracks) track.add(target, count, track.index?.let(settings::get))
        for (i in 0 until count * 2) {
            val sample = target[i] * stems.headroom
            check(sample.isFinite()) { "音箱混音产生了无效音频。" }
            val magnitude = abs(sample)
            target[i] = (if (magnitude < 0.82) sample else
                Math.copySign(0.82 + 0.17 * (1.0 - exp(-(magnitude - 0.82) / 0.17)), sample)).toFloat()
        }
        position += count
    }

    override fun close() { tracks.forEach { it.close() } }
}
