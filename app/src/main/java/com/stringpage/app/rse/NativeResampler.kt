package com.stringpage.app.rse

import kotlin.math.pow

/** GPRSE's single-channel 32-frame resampler. Float32 grouping is intentional:
 * four distinct native branches share phase and four samples of history.
 * No generic high-quality resampler is substituted for these observed rules.
 */
class NativeResampler {
    var phase: Float = 0f
        private set
    var ratio: Float = 1f
        private set
    private val previous = FloatArray(4)
    private val scratch = FloatArray(1028)
    fun history(): FloatArray = previous.copyOf()
    internal fun isHistorySilent(): Boolean = previous.all { it == 0f }

    fun setRatio(value: Float) {
        require(value.isFinite() && value in (1f / 256f)..32f) { "采样移调倍率超出范围。" }
        ratio = value
    }
    fun setPitchSemitones(value: Float) {
        require(value.isFinite())
        setRatio(ratioForPitch(value))
        if (value == 0f) phase = 0f
    }
    companion object {
        fun ratioForPitch(value: Float): Float = 2.0.pow((value * 0.083333336f).toDouble()).toFloat()
    }
    fun requiredInputFrames(): Int = (phase + ratio * 32f).toInt()

    /** Input must contain the exact number requested before this block. Native
     * zero padding beyond that count is preserved, including sub-frame ratios. */
    fun process(input: FloatArray, output: FloatArray, inputFrames: Int = input.size) {
        val needed = requiredInputFrames()
        require(inputFrames == needed && inputFrames <= input.size && output.size >= 32)
        scratch.fill(0f); previous.copyInto(scratch); input.copyInto(scratch, 4, 0, needed)
        var at = 0
        repeat(32) { frame ->
            val a = scratch[at]; val b = scratch[at+1]; val c = scratch[at+2]; val d = scratch[at+3]
            val p = phase
            output[frame] = when {
                ratio < 1f -> {
                    val cubic = (d-a)*0.5f + (b-c)*1.5f
                    val quadratic = ((a-b*2.5f)+(c+c))-d*0.5f
                    ((cubic*p+quadratic)*p+(c-a)*0.5f)*p+b
                }
                ratio <= 1.25f -> (c-b)*p+b
                ratio < 2f -> (0.5926f-p*0.3888653f)*b + (0.2037347f-p*0.2037347f)*a +
                    (p*0.3888653f+0.2037347f)*c + p*0.2037347f*d
                else -> (0.507f-p*0.26050067f)*b + (0.24649934f-p*0.24649934f)*a +
                    (p*0.26050067f+0.24649934f)*c + p*0.24649934f*d
            }
            when {
                ratio < 1f -> {
                    phase += ratio
                    while (phase >= 1f) { phase -= 1f; at++ }
                }
                ratio < 2f -> {
                    phase += ratio-1f; at++
                    if (phase >= 1f) { phase -= 1f; at++ }
                }
                else -> {
                    phase = (ratio+phase)-2f; at+=2
                    while (phase >= 1f) { phase -= 1f; at++ }
                }
            }
        }
        scratch.copyInto(previous, 0, needed, needed+4)
    }
}
