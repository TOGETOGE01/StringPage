package com.stringpage.app.rse

import kotlin.math.floor

/** AMAudio's independent source-rate stage, before decoder gain and the
 * GPRSE 32-frame pitch resampler. Uses the matched MIT r8brain 1.6 converter.
 * Vorbis decoding itself remains Android MediaCodec, not the desktop decoder.
 */
object NativeSourceRateConverter {
    const val OUTPUT_RATE = 44100
    init { System.loadLibrary("stringpage_rse") }

    /** Native IDecoder::outputFrameCount: float32 multiply/divide, then floor.
     * Used for both the declared stream length and authored loop coordinates.
     */
    fun outputFrameCount(sourceFrames: Long, sourceRate: Int): Long {
        require(sourceFrames >= 0 && sourceRate in 8000..192000)
        return floor((sourceFrames.toFloat() * OUTPUT_RATE.toFloat() / sourceRate.toFloat()).toDouble()).toLong()
    }

    fun normalize(audio: DecodedBankSample, cancelled: () -> Boolean = { false }): DecodedBankSample {
        fun checkActive() { check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消采样转换。" } }
        checkActive()
        if (audio.sampleRate == OUTPUT_RATE) return audio
        val frames = outputFrameCount(audio.frames.toLong(), audio.sampleRate)
        require(frames <= BankSampleDecoder.MAX_PCM_BYTES / (4 * audio.channels)) { "采样转换后过大。" }
        val output = FloatArray(frames.toInt() * audio.channels)
        if (output.isEmpty()) return DecodedBankSample(output, audio.channels, OUTPUT_RATE)
        val handle = create(audio.sampleRate, audio.channels)
        check(handle != 0L) { "无法创建采样转换器。" }
        var inputAt = 0
        var outputAt = 0
        var zeroFrames = 0
        try {
            while (outputAt < output.size) {
                checkActive()
                val count = minOf(8192 / audio.channels, audio.frames - inputAt)
                val block = if (count > 0) {
                    process(handle, audio.samples, inputAt * audio.channels, count).also { inputAt += count }
                } else {
                    // Consume linear-phase filter latency. Only retain the
                    // declared stream length; never expose the padded tail.
                    zeroFrames += 1024
                    check(zeroFrames <= 131072) { "采样转换未能完成。" }
                    process(handle, null, 0, 1024)
                }
                val copied = minOf(block.size, output.size - outputAt)
                block.copyInto(output, outputAt, 0, copied)
                outputAt += copied
            }
            checkActive()
        } finally { destroy(handle) }
        return DecodedBankSample(output, audio.channels, OUTPUT_RATE)
    }

    private external fun create(sourceRate: Int, channels: Int): Long
    private external fun process(handle: Long, input: FloatArray?, sampleOffset: Int, frames: Int): FloatArray
    private external fun destroy(handle: Long)
}
