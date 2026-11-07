package com.stringpage.app.rse

/** SampleAccessor.readTick's float32 attack, verified on owned cached PCM.
 * The caller supplies the matching native tick's frame count independently of
 * the requested PCM length; a missing tick advances time by zero. The offline
 * renderer currently uses output block length, not full native tick scheduling.
 */
class NativeAttackEnvelope(val fadeFrames: Long = NativeNoteAmplitude.DEFAULT_FADE_IN_FRAMES) {
    var elapsedFrames: Long = 0
        private set
    var gain: Float = 0f
        private set

    init { require(fadeFrames in 0 until Long.MAX_VALUE) }

    fun applyTick(samples: FloatArray, channels: Int, frames: Int, tickFrameCount: Long?, offsetFrames: Int = 0) {
        require(channels in 1..2 && frames >= 0 && offsetFrames >= 0)
        require(offsetFrames <= samples.size / channels && frames <= samples.size / channels - offsetFrames)
        val advance = tickFrameCount ?: 0L
        require(advance >= 0 && advance <= Long.MAX_VALUE - elapsedFrames)
        if (elapsedFrames < fadeFrames) {
            val count = minOf(fadeFrames - elapsedFrames + 1, frames.toLong()).toInt()
            val increment = 1f / fadeFrames.toFloat()
            repeat(count) { frame ->
                val position = (offsetFrames + frame) * channels
                repeat(channels) { channel -> samples[position + channel] *= gain }
                gain = minOf(1f, gain + increment)
            }
        }
        elapsedFrames += advance
    }
}
