package com.stringpage.app.rse

import kotlin.math.roundToLong

/** Maps the existing performance clock to integer native bend ticks. The full
 * AudioCore tick scheduler is still separate work. A pitch change takes effect
 * at the next 32-frame resampler refill, as in SampleAccessor.readTick.
 */
object RseBendClock {
    fun tick(performance: ScorePerformance,startTick: Double,startFrame: Long,elapsedFrames: Long,sampleRate: Int): Int {
        require(sampleRate>0 && startFrame>=0 && elapsedFrames>=0)
        // PCM event positions round to the nearest frame. Use the upper edge
        // of this frame's rounding interval, excluding an exact half-frame tie.
        val edge=Math.nextDown(startFrame.toDouble()+elapsedFrames.toDouble()+.5)
        val tick=(performance.msToTick(edge*1000.0/sampleRate)-startTick)/2.0
        require(tick.isFinite() && tick<Int.MAX_VALUE) { "弯音时值超出播放范围。" }
        var candidate=kotlin.math.floor(tick).toInt().coerceAtLeast(0)
        val absolute=startFrame+elapsedFrames
        fun boundary(at: Int)=(performance.tickToMs(startTick+at.toDouble()*2.0)*sampleRate/1000.0).roundToLong()
        // Mapping through tempo segments can round a half-frame edge back to
        // its adjacent double. Check the same frame rule used by the renderer.
        while(candidate>0 && boundary(candidate)>absolute) candidate--
        while(candidate<Int.MAX_VALUE-1 && boundary(candidate+1)<=absolute) candidate++
        return candidate
    }
}
