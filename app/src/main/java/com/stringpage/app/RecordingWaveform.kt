package com.stringpage.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Reduces decoded PCM to peaks without retaining full-song samples. */
internal object RecordingWaveform {
    fun read(file: File, durationMs: Double, cancelled: () -> Boolean = { false }): FloatArray =
        RecordingAudio.withAudioTrack(file) { source, input ->
            require(durationMs.isFinite() && durationMs > 0)
            val peaks = FloatArray(2048)
            val firstUs = source.sampleTime.coerceAtLeast(0)
            val mime = requireNotNull(input.getString(MediaFormat.KEY_MIME))
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floating = false
            var width = 2
            var eightBit = false
            fun configure(format: MediaFormat) {
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                require(channels in 1..16 && rate > 0)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
                floating = encoding == AudioFormat.ENCODING_PCM_FLOAT
                eightBit = encoding == AudioFormat.ENCODING_PCM_8BIT
                width = if (floating) 4 else if (eightBit) 1 else 2
                require(floating || eightBit || encoding == AudioFormat.ENCODING_PCM_16BIT) { "此音频的波形格式暂未支持。" }
            }
            fun consume(inputBytes: ByteBuffer, offset: Int, length: Int, timeUs: Long) {
                val bytes = inputBytes.duplicate().order(ByteOrder.nativeOrder())
                bytes.limit(offset + length)
                bytes.position(offset)
                val frames = length / (width * channels)
                val ratio = peaks.size / (durationMs * 1000.0)
                val position = (timeUs - firstUs).coerceAtLeast(0) * ratio
                val step = 1_000_000.0 / rate * ratio
                for (frame in 0 until frames) {
                    val bin = (position + frame * step).toInt().coerceIn(peaks.indices)
                    var level = peaks[bin]
                    repeat(channels) {
                        val sample = when {
                            floating -> bytes.float
                            eightBit -> ((bytes.get().toInt() and 255) - 128) / 128f
                            else -> bytes.short / 32768f
                        }
                        if (sample.isFinite()) level = maxOf(level, abs(sample))
                    }
                    peaks[bin] = level.coerceAtMost(1f)
                }
            }
            fun checkRunning() {
                check(!cancelled() && !Thread.currentThread().isInterrupted) { "已关闭波形。" }
            }
            if (mime == "audio/raw") {
                configure(input)
                val buffer = ByteBuffer.allocateDirect(512 * 1024)
                while (source.sampleTime >= 0L) {
                    checkRunning()
                    val length = source.readSampleData(buffer, 0)
                    if (length <= 0) break
                    consume(buffer, 0, length, source.sampleTime)
                    source.advance()
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                try {
                    decoder.configure(input, null, null, 0)
                    decoder.start()
                    val info = MediaCodec.BufferInfo()
                    var done = false
                    var sentEnd = false
                    var progress = SystemClock.elapsedRealtime()
                    while (!done) {
                        checkRunning()
                        check(SystemClock.elapsedRealtime() - progress < 12000) { "读取音频波形超时。" }
                        if (!sentEnd) {
                            val index = decoder.dequeueInputBuffer(2000L)
                            if (index >= 0) {
                                val buffer = requireNotNull(decoder.getInputBuffer(index))
                                val count = source.readSampleData(buffer, 0)
                                if (count < 0) {
                                    decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sentEnd = true
                                } else {
                                    decoder.queueInputBuffer(index, 0, count, source.sampleTime, 0)
                                    source.advance()
                                }
                            }
                        }
                        when (val index = decoder.dequeueOutputBuffer(info, 3000L)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> configure(decoder.outputFormat)
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0) consume(requireNotNull(decoder.getOutputBuffer(index)),
                                        info.offset, info.size, info.presentationTimeUs)
                                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    progress = SystemClock.elapsedRealtime()
                                } finally {
                                    decoder.releaseOutputBuffer(index, false)
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { decoder.stop() }
                    decoder.release()
                }
            }
            peaks
        }
}
