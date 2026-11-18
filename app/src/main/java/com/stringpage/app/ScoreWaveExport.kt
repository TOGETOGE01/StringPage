@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.model.Score
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal object ScoreWaveExport {
    private const val BLOCK = 2048
    private const val FRAME_BYTES = 4
    private const val RIFF_MAX = 0xffffffffL

    fun write(
        score: Score, settings: Settings, tones: List<GuitarTone>, amps: Map<Int, GuitarAmpSettings>,
        file: File, soundFont: () -> ByteArray, cancelled: () -> Boolean = { false }
    ) {
        val folder = checkNotNull(file.parentFile)
        val enabled = amps.filterValues { it.enabled }
        SynthAmpMix(folder, soundFont).prepare(score, settings, tones, 0, cancelled, enabled.keys) {}.use { stems ->
            val maximum = (stems.frames + stems.sampleRate * 4L) * FRAME_BYTES
            require(maximum + 36 <= RIFF_MAX && folder.usableSpace > maximum + 16L * 1024 * 1024) {
                "可用空间不足，或 WAV 超出单文件容量。"
            }
            val bytes = ByteBuffer.allocate(BLOCK * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(BLOCK * 2)
            var frames = 0L
            LiveAmpMixer(stems, BLOCK).use { mixer ->
                mixer.seek(0, enabled)
                file.outputStream().buffered(64 * 1024).use { stream ->
                    stream.write(header(stems.sampleRate).array())
                    fun render(until: Long) {
                        while (frames < until) {
                            check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消导出。" }
                            val count = minOf(BLOCK.toLong(), until - frames).toInt()
                            mixer.read(samples, count, enabled)
                            bytes.clear()
                            for (i in 0 until count * 2) {
                                val pcm = (samples[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
                                bytes.putShort(pcm.toShort())
                            }
                            stream.write(bytes.array(), 0, count * FRAME_BYTES)
                            frames += count
                        }
                    }
                    render(stems.frames)
                    render(stems.frames + mixer.tailFrames(stems.frames, 1.0))
                }
            }
            val size = frames * FRAME_BYTES
            RandomAccessFile(file, "rw").use { output ->
                output.seek(4)
                output.writeInt(Integer.reverseBytes((size + 36).toInt()))
                output.seek(40)
                output.writeInt(Integer.reverseBytes(size.toInt()))
            }
        }
    }

    private fun header(rate: Int) = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(0)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(2)
        putInt(rate)
        putInt(rate * FRAME_BYTES)
        putShort(FRAME_BYTES.toShort())
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(0)
    }
}
