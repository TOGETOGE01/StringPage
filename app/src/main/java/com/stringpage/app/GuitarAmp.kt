package com.stringpage.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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

internal enum class GuitarDrive(val label: String) {
    Off("旁通"), Boost("提升"), Overdrive("过载"), Distortion("失真")
}

internal data class GuitarAmpSettings(
    val preset: AmpPreset = AmpPreset.Clean,
    val gain: Int = 50,
    val volume: Int = 75,
    val pan: Int = 0,
    val cabinet: GuitarCabinet = preset.defaultCabinet,
    val bass: Int = 0,
    val mids: Int = 0,
    val treble: Int = 0,
    val enabled: Boolean = true,
    val inputDb: Int = 0,
    val gateDb: Int? = null,
    val pedal: GuitarDrive = GuitarDrive.Off,
    val pedalDrive: Int = 35,
    val ampEnabled: Boolean = true,
    val cabinetEnabled: Boolean = true,
    val cabinetImpulse: CabinetImpulse? = null,
    val eqEnabled: Boolean = true,
    val effects: GuitarEffects = GuitarEffects()
)

/** Dynamics and distortion run in mono before the post-cabinet stereo stages. */
internal class GuitarAmp(config: GuitarAmpSettings, private val sampleRate: Int, beats: SourceBeatMap = SourceBeatMap(sampleRate)) {
    private val preset = config.preset
    private val drive = preset.drive * 2.0.pow((config.gain - 50) / 32.0)
    private val inputGain = 10.0.pow(config.inputDb / 20.0)
    private val gate = config.gateDb?.let { NoiseGate(it, sampleRate) }
    private val compressor = config.effects.compression.takeIf { it > 0 }?.let { GuitarCompressor(it, sampleRate) }
    private val wah = config.effects.wah.takeUnless { it == GuitarWah.Off }
        ?.let { WahPedal(it, config.effects.wahPosition, sampleRate) }
    private val pedal = config.pedal.takeUnless { it == GuitarDrive.Off }
    private val pedalGain = 1 + config.pedalDrive / 100.0 * when (config.pedal) {
        GuitarDrive.Off -> 0.0
        GuitarDrive.Boost -> 2.0
        GuitarDrive.Overdrive -> 8.0
        GuitarDrive.Distortion -> 20.0
    }
    private val pedalInput = pedal?.let { Biquad.highPass(sampleRate.toDouble(), if (it == GuitarDrive.Boost) 42.0 else 135.0) }
    private val pedalTone = pedal?.let {
        Biquad.lowPass(sampleRate * 4.0, if (it == GuitarDrive.Distortion) 4700.0 else 6400.0)
    }
    private val ampEnabled = config.ampEnabled
    private val gain = (if (ampEnabled) preset.output else 1.0) * config.volume / 75.0
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
    private val eqEnabled = config.eqEnabled
    private val cabinet = if (config.cabinetEnabled) CabinetConvolution(
        config.cabinetImpulse?.atRate(sampleRate) ?: modeledCabinet(config.cabinet, sampleRate.toDouble())
    ) else null
    private val beforeAmp = space(config.effects.run { copy(
        modulation = if (modulationPosition == ModulationPosition.BeforeAmp) modulation else GuitarModulation.Off,
        delay = GuitarDelay.Off, reverb = GuitarReverb.Off
    ) }, beats)
    private val ampLoop = space(config.effects.run { copy(modulation = GuitarModulation.Off,
        delay = if (delayPosition == SpacePosition.AmpLoop) delay else GuitarDelay.Off,
        reverb = if (reverbPosition == SpacePosition.AmpLoop) reverb else GuitarReverb.Off
    ) }, beats)
    private val afterCabinet = space(config.effects.run { copy(
        modulation = if (modulationPosition == ModulationPosition.AfterCabinet) modulation else GuitarModulation.Off,
        delay = if (delayPosition == SpacePosition.AfterCabinet) delay else GuitarDelay.Off,
        reverb = if (reverbPosition == SpacePosition.AfterCabinet) reverb else GuitarReverb.Off
    ) }, beats)
    private val loopPower = if (ampLoop != null && ampEnabled && preset != AmpPreset.Clean) {
        Biquad.lowPass(sampleRate * 4.0, 8200.0)
    } else null
    private var previousPowerInput = 0.0
    private var nextFrame = 0L
    private var lastInput = 0.0
    private var envelope = 0.0
    private val biasOffset = saturate(preset.bias)
    private val recovery = 1.0 - exp(-1.0 / (sampleRate * 0.042))

    fun process(stereo: FloatArray, frames: Int, startFrame: Long = nextFrame, speed: Double = 1.0, warmup: Boolean = false) {
        val pre = if (warmup) null else beforeAmp
        val loop = if (warmup) null else ampLoop
        val post = if (warmup) null else afterCabinet
        loop?.setSpeed(speed)
        post?.setSpeed(speed)
        for (i in 0 until frames * 2 step 2) {
            val frame = startFrame + i / 2
            var input = (stereo[i].toDouble() + stereo[i + 1]) * 0.5 * inputGain
            if (gate != null) input = gate.process(input)
            if (compressor != null) input = compressor.process(input)
            if (wah != null) input = wah.process(input)
            if (pre != null) input = mono(pre, input, frame)
            if (pedalInput != null) input = pedalInput.process(input)
            if (ampEnabled) input = inputFilter.process(input)
            val ampPath = ampEnabled || pedal != null
            var shaped = if (ampPath) shape(input) else input
            if (loop != null) shaped = mono(loop, shaped, frame)
            if (loopPower != null) shaped = powerAfterLoop(shaped, loopPower)
            if (ampPath) shaped = dc.process(shaped)
            val cab = cabinet?.process(shaped) ?: shaped
            val output = if (eqEnabled) treble.process(mids.process(bass.process(cab))) else cab
            if (post != null) post.process(output, frame)
            stereo[i] = ((post?.left ?: output) * left).toFloat()
            stereo[i + 1] = ((post?.right ?: output) * right).toFloat()
        }
        nextFrame = startFrame + frames
    }

    fun tailFrames(frame: Long, speed: Double): Int {
        val before = beforeAmp?.remainingFrames(frame, speed) ?: 0
        val inside = ampLoop?.remainingFrames(frame, speed) ?: 0
        val after = afterCabinet?.remainingFrames(frame, speed) ?: 0
        return minOf(before + inside + after, (sampleRate * speed * 4.0).toInt())
    }

    fun updateSpaceControls(previous: GuitarAmpSettings, next: GuitarAmpSettings): Boolean {
        if (ampLoop == null && afterCabinet == null) return false
        val old = previous.effects
        val updated = next.effects
        val otherSettings = next.copy(effects = updated.copy(delayMs = old.delayMs, delayMix = old.delayMix,
            delayFeedback = old.delayFeedback, reverbMix = old.reverbMix))
        if (otherSettings != previous) return false
        ampLoop?.updateControls(updated)
        afterCabinet?.updateControls(updated)
        return true
    }

    fun reset() {
        nextFrame = 0L
        lastInput = 0.0
        previousPowerInput = 0.0
        envelope = 0.0
        gate?.reset()
        compressor?.reset()
        wah?.reset()
        pedalInput?.reset()
        pedalTone?.reset()
        inputFilter.reset()
        antiAlias.reset()
        trebleCut.reset()
        dc.reset()
        bass.reset()
        mids.reset()
        treble.reset()
        cabinet?.reset()
        loopPower?.reset()
        beforeAmp?.reset()
        ampLoop?.reset()
        afterCabinet?.reset()
    }

    private fun space(effects: GuitarEffects, beats: SourceBeatMap): GuitarSpace? {
        val modulation = effects.modulation != GuitarModulation.Off && effects.modulationDepth > 0
        if (!modulation && effects.delay == GuitarDelay.Off && effects.reverb == GuitarReverb.Off) return null
        return GuitarSpace(effects, sampleRate, beats)
    }

    private fun mono(space: GuitarSpace, value: Double, frame: Long): Double {
        space.process(value, frame)
        return (space.left + space.right) * 0.5
    }

    private fun powerAfterLoop(input: Double, filter: Biquad): Double {
        val step = (input - previousPowerInput) * 0.25
        var shaped = 0.0
        for (phase in 1..4) shaped = filter.process(saturate((previousPowerInput + step * phase) * 1.22))
        previousPowerInput = input
        return shaped
    }

    private fun shape(input: Double): Double {
        envelope += (abs(input) - envelope) * recovery
        val ampGain = drive / (1.0 + envelope * preset.drive * 0.32)
        val step = (input - lastInput) * 0.25
        var shaped = 0.0
        for (phase in 1..4) {
            val interpolated = lastInput + step * phase
            val driven = when (pedal) {
                GuitarDrive.Boost -> interpolated * pedalGain
                GuitarDrive.Overdrive -> (saturate(interpolated * pedalGain + 0.09) - OVERDRIVE_BIAS) * 0.53
                GuitarDrive.Distortion -> (interpolated * pedalGain).coerceIn(-0.72, 0.72) * 0.56
                else -> interpolated
            }
            val pedalOut = pedalTone?.process(driven) ?: driven
            val power = if (ampEnabled) {
                val preamp = saturate(pedalOut * ampGain + preset.bias) - biasOffset
                if (preset == AmpPreset.Clean || ampLoop != null) preamp else saturate(preamp * 1.22)
            } else pedalOut
            shaped = antiAlias.process(trebleCut.process(power))
        }
        lastInput = input
        return shaped
    }

    private class NoiseGate(thresholdDb: Int, sampleRate: Int) {
        private val openAt = 10.0.pow(thresholdDb / 20.0)
        private val closeAt = openAt * 0.65
        private val holdFrames = sampleRate * 25 / 1000
        private val detectorRelease = exp(-1.0 / (sampleRate * 0.028))
        private val attack = 1 - exp(-1.0 / (sampleRate * 0.0012))
        private val release = 1 - exp(-1.0 / (sampleRate * 0.038))
        private var peak = 0.0
        private var gain = 0.0
        private var hold = 0

        fun process(input: Double): Double {
            peak = maxOf(abs(input), peak * detectorRelease)
            if (peak >= openAt || (hold > 0 && peak >= closeAt)) hold = holdFrames
            else if (hold > 0) hold--
            gain += if (hold > 0) (1 - gain) * attack else -gain * release
            return input * gain
        }

        fun reset() {
            peak = 0.0
            gain = 0.0
            hold = 0
        }
    }

    private companion object {
        val OVERDRIVE_BIAS = saturate(0.09)

        fun saturate(x: Double): Double {
            val limited = x.coerceIn(-3.0, 3.0)
            val square = limited * limited
            return limited * (27.0 + square) / (27.0 + 9.0 * square)
        }
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

        fun reset() {
            s1 = 0.0
            s2 = 0.0
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
