package com.stringpage.app.rse

import kotlin.math.pow

/** Independent port of AMAudio 8.1.3.67's pure VolumePan gain curve (RVA 0x35390).
 * The parent processor's block ramp/state and MIDI-to-fader conversion are separate.
 * powf differs between runtimes; retained native probes bound the measured error.
 */
object NativeVolumePanCurve {
    data class Gains(val left: Float, val right: Float)
    fun gains(volume: Float, pan: Float): Gains {
        require(volume.isFinite() && volume in 0f..1f && pan.isFinite() && pan in 0f..1f)
        if (volume <= 0f) return Gains(0f,0f)
        val opposite = 1f - pan
        val left = (opposite * -2f) * opposite + 3f * opposite
        val right = (-2f * pan) * pan + 3f * pan
        val exponent = (((volume * 100f) * 0.5f) - 40f) / 20f
        val gain = 10.0.pow(exponent.toDouble()).toFloat()
        return Gains(gain * left, gain * right)
    }
}
