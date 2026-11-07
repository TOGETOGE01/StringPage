package com.stringpage.app.rse

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder

data class DecodedBankSample(val samples: FloatArray, val channels: Int, val sampleRate: Int) {
    init {
        require(channels in 1..2 && sampleRate in 8000..192000)
        require(samples.size % channels == 0 && samples.all { it.isFinite() })
    }
    val frames get() = samples.size / channels
}

/** Decodes one bounded bank member directly from its file region, without extracting it.
 * Commercial recordings are neither bundled nor written to cache/output files.
 */
object BankSampleDecoder {
    const val MAX_PCM_BYTES = 32 * 1024 * 1024
    fun decode(file: File, offset: Long, length: Long, cancelled: () -> Boolean = { false }): DecodedBankSample {
        require(offset >= 0 && length > 0 && offset <= file.length() && length <= file.length() - offset)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd, offset, length) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/vorbis"
            } ?: throw IllegalArgumentException("音色库条目不是支持的 Vorbis 采样。")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            require(channels in 1..2 && sampleRate in 8000..192000) { "不支持该采样的声道数或采样率。" }
            val codec = MediaCodec.createDecoderByType("audio/vorbis")
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            val blocks = ArrayList<FloatArray>()
            var total = 0
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var progressAt = SystemClock.elapsedRealtime()
            while (!outputDone) {
                check(!cancelled() && !Thread.currentThread().isInterrupted) { "已停止试听。" }
                check(SystemClock.elapsedRealtime() - progressAt < 15000) { "采样解码超时。" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        require(channels in 1..2 && sampleRate in 8000..192000)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                        progressAt = SystemClock.elapsedRealtime()
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.nativeOrder())
                                output.position(info.offset); output.limit(info.offset + info.size)
                                val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                require(info.size % (width * channels) == 0) { "采样声道数据不完整。" }
                                val count = info.size / width
                                require(count <= MAX_PCM_BYTES / 4 - total) { "单个采样过大。" }
                                val block = FloatArray(count) {
                                    if (width == 4) output.float else output.short / 32768f
                                }
                                require(block.all { it.isFinite() }) { "采样包含无效音频数据。" }
                                blocks.add(block); total += count
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            progressAt = SystemClock.elapsedRealtime()
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            require(total > 0) { "采样没有可播放的音频。" }
            val joined = FloatArray(total)
            var cursor = 0
            for (block in blocks) { block.copyInto(joined, cursor); cursor += block.size }
            return DecodedBankSample(joined, channels, sampleRate)
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            decoder?.release(); extractor.release()
        }
    }
}
