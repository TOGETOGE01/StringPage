package com.stringpage.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class AmpPreset(
    val label: String, val drive: Double, val bias: Double, val bassCut: Double, val output: Double,
    val defaultCabinet: GuitarCabinet
) {
    Clean("清音", 1.4, 0.0, 56.0, 1.05, GuitarCabinet.Open),
    Crunch("轻过载", 6.0, 0.10, 85.0, 0.28, GuitarCabinet.Open),
    British("英式失真", 15.0, 0.16, 125.0, 0.166, GuitarCabinet.Closed),
    Modern("现代高增益", 29.0, 0.06, 175.0, 0.130, GuitarCabinet.Closed)
}

internal enum class GuitarCabinet(val label: String, val resonance: Double, val presence: Double, val cutoff: Double) {
    Open("开放式 1×12", 112.0, 2350.0, 4900.0),
    Closed("封闭式 4×12", 133.0, 1800.0, 3900.0)
}

internal data class GuitarAmpSettings(
    val preset: AmpPreset = AmpPreset.Clean,
    val gain: Int = 50,
    val volume: Int = 75,
    val pan: Int = 0,
    val cabinet: GuitarCabinet = preset.defaultCabinet,
    val bass: Int = 0,
    val mids: Int = 0,
    val treble: Int = 0
)

/** A stateful mono amp followed by cabinet convolution, equalization and stereo output. */
internal class GuitarAmp(config: GuitarAmpSettings, sampleRate: Int) {
    private val preset = config.preset
    private val drive = preset.drive * 2.0.pow((config.gain - 50) / 32.0)
    private val gain = preset.output * config.volume / 75.0
    private val angle = (config.pan + 100) * PI / 400
    private val left = gain * cos(angle) * sqrt(2.0)
    private val right = gain * sin(angle) * sqrt(2.0)
    private val inputFilter = Biquad.highPass(sampleRate.toDouble(), preset.bassCut)
    private val antiAlias = Biquad.lowPass(sampleRate * 4.0, 8200.0)
    private val trebleCut = Biquad.lowPass(sampleRate * 4.0, 9600.0)
    private val dc = Biquad.highPass(sampleRate.toDouble(), 38.0)
    private val bass = Biquad.peak(sampleRate.toDouble(), 185.0, config.bass.toDouble(), 0.72)
    private val mids = Biquad.peak(sampleRate.toDouble(), 740.0, config.mids.toDouble(), 0.8)
    private val treble = Biquad.peak(sampleRate.toDouble(), 2850.0, config.treble.toDouble(), 0.75)
    private val impulse = modeledCabinet(config.cabinet, sampleRate.toDouble())
    private val delay = DoubleArray(impulse.size)
    private var head = 0
    private var lastInput = 0.0
    private var envelope = 0.0
    private val biasOffset = saturate(preset.bias)
    private val recovery = 1.0 - kotlin.math.exp(-1.0 / (sampleRate * 0.042))

    fun process(stereo: FloatArray, frames: Int) {
        for (i in 0 until frames * 2 step 2) {
            val input = inputFilter.process((stereo[i].toDouble() + stereo[i + 1]) * 0.5)
            envelope += (abs(input) - envelope) * recovery
            val inputGain = drive / (1.0 + envelope * preset.drive * 0.32)
            var shaped = 0.0
            for (phase in 1..4) {
                val interpolated = lastInput + (input - lastInput) * (phase * 0.25)
                val preamp = saturate(interpolated * inputGain + preset.bias) - biasOffset
                val power = if (preset == AmpPreset.Clean) preamp else saturate(preamp * 1.22)
                shaped = antiAlias.process(trebleCut.process(power))
            }
            lastInput = input
            delay[head] = dc.process(shaped)
            var cab = 0.0
            for (tap in 0..head) cab += impulse[tap] * delay[head - tap]
            for (tap in (head + 1) until impulse.size) cab += impulse[tap] * delay[impulse.size + head - tap]
            if (++head == impulse.size) head = 0
            val output = treble.process(mids.process(bass.process(cab)))
            stereo[i] = (output * left).toFloat()
            stereo[i + 1] = (output * right).toFloat()
        }
    }

    private fun saturate(x: Double): Double {
        val limited = x.coerceIn(-3.0, 3.0)
        val square = limited * limited
        return limited * (27.0 + square) / (27.0 + 9.0 * square)
    }

    private fun modeledCabinet(cabinet: GuitarCabinet, rate: Double): DoubleArray {
        // Generate a causal IR from a speaker resonance and cabinet roll-off. It uses no recording.
        val low = Biquad.highPass(rate, cabinet.resonance, 0.91)
        val peak = Biquad.peak(rate, cabinet.presence, 3.0, 0.85)
        val high = Biquad.lowPass(rate, cabinet.cutoff)
        val upper = Biquad.lowPass(rate, cabinet.cutoff * 1.32)
        return DoubleArray(96) { i ->
            val window = if (i < 64) 1.0 else 0.5 + 0.5 * cos(PI * (i - 64) / 31)
            window * upper.process(high.process(peak.process(low.process(if (i == 0) 1.0 else 0.0))))
        }
    }

    private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val input0 = b0 / a0
        private val input1 = b1 / a0
        private val input2 = b2 / a0
        private val feedback1 = a1 / a0
        private val feedback2 = a2 / a0
        private var s1 = 0.0
        private var s2 = 0.0

        fun process(value: Double): Double {
            val output = value * input0 + s1
            s1 = value * input1 - output * feedback1 + s2
            s2 = value * input2 - output * feedback2
            return output
        }

        companion object {
            fun lowPass(rate: Double, hz: Double) = pass(rate, hz, 0.707, false)
            fun highPass(rate: Double, hz: Double, q: Double = 0.707) = pass(rate, hz, q, true)

            private fun pass(rate: Double, hz: Double, q: Double, high: Boolean): Biquad {
                val omega = 2 * PI * hz / rate
                val cosine = cos(omega)
                val alpha = sin(omega) / (2 * q)
                val edge = (1 + if (high) cosine else -cosine) / 2
                return Biquad(edge, if (high) -2 * edge else 2 * edge, edge, 1 + alpha, -2 * cosine, 1 - alpha)
            }

            fun peak(rate: Double, hz: Double, db: Double, q: Double): Biquad {
                val omega = 2 * PI * hz / rate
                val alpha = sin(omega) / (2 * q)
                val a = 10.0.pow(db / 40)
                val middle = -2 * cos(omega)
                return Biquad(1 + alpha * a, middle, 1 - alpha * a, 1 + alpha / a, middle, 1 - alpha / a)
            }
        }
    }
}
