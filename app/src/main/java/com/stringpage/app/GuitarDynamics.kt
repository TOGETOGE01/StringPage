package com.stringpage.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tan

internal class GuitarCompressor(amount: Int, sampleRate: Int) {
    private val strength = amount / 100.0
    private val threshold = 10.0.pow((-12 - 22 * strength) / 20)
    private val exponent = 1 / (1 + 5 * strength) - 1
    private val attack = 1 - exp(-1.0 / (sampleRate * 0.008))
    private val release = 1 - exp(-1.0 / (sampleRate * 0.085))
    private val makeup = 10.0.pow(4.5 * strength / 20)
    private var envelope = 0.0

    fun process(sample: Double): Double {
        val magnitude = abs(sample)
        envelope += (magnitude - envelope) * if (magnitude > envelope) attack else release
        val above = envelope / threshold
        val gain = if (above > 1) above.pow(exponent) else 1.0
        return sample * makeup * gain
    }

    fun reset() { envelope = 0.0 }
}

/** Resonant filter in front of the drive pedal. The auto mode follows the note attack. */
internal class WahPedal(private val mode: GuitarWah, position: Int, sampleRate: Int) {
    private val cutoff = DoubleArray(101) { tan(PI * minOf(330 * 7.5.pow(it / 100.0), sampleRate * 0.42) / sampleRate) }
    private val fixed = cutoff[position]
    private val attack = 1 - exp(-1.0 / (sampleRate * 0.006))
    private val decay = 1 - exp(-1.0 / (sampleRate * 0.13))
    private var envelope = 0.0
    private var band = 0.0
    private var low = 0.0

    fun process(sample: Double): Double {
        val g = if (mode == GuitarWah.Auto) {
            val magnitude = abs(sample)
            envelope += (magnitude - envelope) * if (magnitude > envelope) attack else decay
            val sweep = (envelope * 310).coerceIn(0.0, 100.0)
            val lower = sweep.toInt()
            cutoff[lower] + (cutoff[minOf(lower + 1, 100)] - cutoff[lower]) * (sweep - lower)
        } else fixed
        // Topology-preserving state variable filter, stable as the cutoff moves.
        val v = sample - low
        val bandpass = (band + g * v) / (1 + g * (0.27 + g))
        val lowpass = low + g * bandpass
        band = 2 * bandpass - band
        low = 2 * lowpass - low
        return sample * 0.44 + bandpass * 0.86
    }

    fun reset() {
        envelope = 0.0
        band = 0.0
        low = 0.0
    }
}
