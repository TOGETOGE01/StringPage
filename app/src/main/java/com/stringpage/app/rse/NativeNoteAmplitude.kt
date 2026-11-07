package com.stringpage.app.rse

/** Independent GPRSE 8.1.3.67 amplitude primitives, verified with synthetic PCM.
 * This is the release multiplier used by the native voice mixer, plus the
 * ordered product of SampleAccessor's nine amplitude-functor slots. Scheduling,
 * technique-specific fade lengths, time-varying functors and the remaining
 * voice envelope are separate; the default lengths alone do not restore them.
 */
object NativeNoteAmplitude {
    const val DEFAULT_FADE_IN_FRAMES = 128L
    const val DEFAULT_FADE_OUT_FRAMES = 128L
    const val MODIFIER_SLOTS = 9

    /** Null slots are skipped; the original accumulates float32 in slot order. */
    fun multiplyModifiers(factors: List<Float?>): Float {
        require(factors.size == MODIFIER_SLOTS)
        var result = 1f
        for (factor in factors) if (factor != null) {
            require(factor.isFinite())
            result *= factor
        }
        return result
    }

    /** Layout adapter for matched mono/stereo interleaved buffers.
     * The native numerator is computed as an integer before conversion to float;
     * `1 - elapsed / total` would round differently. A zero release duration is
     * outside this contract: the native mixer removes those voices before calling.
     */
    fun applyRelease(
        samples: FloatArray, channels: Int, frames: Int,
        elapsedFrames: Long, totalFrames: Long, offsetFrames: Int = 0
    ) {
        require(channels in 1..2 && frames >= 0 && offsetFrames >= 0)
        require(offsetFrames <= samples.size / channels && frames <= samples.size / channels - offsetFrames)
        require(totalFrames > 0 && elapsedFrames >= 0 && elapsedFrames <= Long.MAX_VALUE - frames.toLong())
        val denominator = totalFrames.toFloat()
        for (index in 0 until frames) {
            val gain = ((totalFrames - elapsedFrames - index.toLong()).toFloat() / denominator).coerceIn(0f, 1f)
            val position = (offsetFrames + index) * channels
            for (channel in 0 until channels) samples[position + channel] *= gain
        }
    }
}
