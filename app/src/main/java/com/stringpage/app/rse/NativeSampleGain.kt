package com.stringpage.app.rse

import kotlin.math.pow

/** Native coordinate-delta compensation and AccentuationInterpreter rules.
 * Default decoder gain is the initialized SoundFontV7 value observed in all
 * 350 installed banks. Application headroom and MIDI channel controls remain
 * separate from these recovered rules and are not native mixer calibration.
 */
object NativeSampleGain {
    val DEFAULT_DECODER_GAIN: Float = Float.fromBits(1062423984)
    const val COMPATIBILITY_HEADROOM = 0.3f

    fun dynamicCorrection(delta: Int): Float = 2.15f.toDouble().pow(delta.toFloat().toDouble()).toFloat()
    fun pitchCorrection(delta: Int): Float = delta.toFloat()

    fun adjustedDynamic(dynamic: Int, accentFlags: Int, antiAccent: Int, processType: Int = 4): Int {
        if (processType != 4) return dynamic
        return dynamic + when {
            accentFlags and 4 != 0 -> 2
            accentFlags and 8 != 0 -> 1
            antiAccent == 1 || antiAccent == 2 -> -1
            antiAccent == 3 -> -2
            else -> 0
        }
    }
}
