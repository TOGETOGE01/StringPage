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
    val defaultCabinet: GuitarCabinet, val cleanPower: Boolean = false
) {
    Clean("清音", 1.4, 0.0, 56.0, 1.05, GuitarCabinet.Open, cleanPower = true),
    Crunch("轻过载", 6.0, 0.10, 85.0, 0.28, GuitarCabinet.Open),
    British("英式失真", 15.0, 0.16, 125.0, 0.166, GuitarCabinet.Closed),
    Modern("现代高增益", 29.0, 0.06, 175.0, 0.130, GuitarCabinet.Closed),
    Bright("明亮清音", 1.12, 0.025, 80.0, 1.12, GuitarCabinet.Twin, cleanPower = true),
    Warm("暖调清音", 2.15, 0.07, 48.0, 0.67, GuitarCabinet.Compact, cleanPower = true),
    SolidState("晶体管清音", 0.85, 0.0, 62.0, 1.36, GuitarCabinet.Twin, cleanPower = true),
    SmallTube("小功率电子管", 4.1, 0.17, 67.0, 0.40, GuitarCabinet.OpenTen),
    Tight("紧致高增益", 24.0, 0.035, 196.0, 0.154, GuitarCabinet.Deep)
}

internal enum class GuitarCabinet(val label: String, val resonance: Double, val presence: Double, val cutoff: Double) {
    Open("开放式 1×12", 112.0, 2350.0, 4900.0),
    Closed("封闭式 4×12", 133.0, 1800.0, 3900.0),
    Compact("小型 1×10", 150.0, 1380.0, 3600.0),
    Twin("半开放式 2×12", 94.0, 2750.0, 5450.0),
    OpenTen("开放式 2×10", 134.0, 2980.0, 5550.0),
    ClosedTwin("封闭式 2×12", 118.0, 2180.0, 4400.0),
    Deep("加深式 4×12", 97.0, 1560.0, 3780.0)
}

internal enum class CabinetMic(val label: String, val cutoffRatio: Double, val presenceDb: Double) {
    Center("中心", 1.0, 3.0), Edge("边缘", 0.87, 0.9), OffAxis("离轴", 0.75, -1.7)
}

internal enum class GuitarDrive(val label: String, val gainRange: Double, val bassCut: Double, val lowPass: Double) {
    Off("旁通", 0.0, 135.0, 6400.0),
    Boost("提升", 2.0, 42.0, 6400.0),
    Overdrive("过载", 8.0, 135.0, 6400.0),
    Distortion("失真", 20.0, 135.0, 4700.0),
    Fuzz("法兹", 30.0, 75.0, 3650.0),
    Transparent("透明过载", 5.0, 60.0, 7200.0),
    MidOverdrive("中频过载", 10.0, 185.0, 5400.0),
    OctaveFuzz("八度法兹", 16.0, 104.0, 4150.0)
}

internal data class GuitarPedalSettings(
    val model: GuitarDrive = GuitarDrive.Off,
    val drive: Int = 35,
    val tone: Int = 50,
    val levelDb: Int = 0
)

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
    val effects: GuitarEffects = GuitarEffects(),
    val pedalTone: Int = 50,
    val pedalLevelDb: Int = 0,
    val secondPedal: GuitarPedalSettings = GuitarPedalSettings(),
    val cabinetMic: CabinetMic = CabinetMic.Center
) {
    val firstPedal get() = GuitarPedalSettings(pedal, pedalDrive, pedalTone, pedalLevelDb)
}

/** Dynamics and distortion run in mono before the post-cabinet stereo stages. */
internal class GuitarAmp(config: GuitarAmpSettings, private val sampleRate: Int, beats: SourceBeatMap = SourceBeatMap(sampleRate)) {
    private val preset = config.preset
    private val drive = preset.drive * 2.0.pow((config.gain - 50) / 32.0)
    private val inputGain = 10.0.pow(config.inputDb / 20.0)
    private val gate = config.gateDb?.let { NoiseGate(it, sampleRate) }
    private val compressor = config.effects.compression.takeIf { it > 0 }?.let { GuitarCompressor(it, sampleRate) }
    private val wah = config.effects.wah.takeUnless { it == GuitarWah.Off }
        ?.let { WahPedal(it, config.effects.wahPosition, sampleRate) }
    private val pedals = listOf(config.firstPedal, config.secondPedal).filter { it.model != GuitarDrive.Off }
    private val frontPedal = pedals.firstOrNull()?.let { DrivePedal(it, sampleRate * 4.0) }
    private val pedalInput = pedals.firstOrNull()?.let { Biquad.highPass(sampleRate.toDouble(), it.model.bassCut) }
    private val nextPedal = pedals.getOrNull(1)?.let { DrivePedal(it, sampleRate * 4.0) }
    private val nextPedalInput = pedals.getOrNull(1)?.let { Biquad.highPass(sampleRate * 4.0, it.model.bassCut) }
    private val ampEnabled = config.ampEnabled
    private val gain = (if (ampEnabled) preset.output else 1.0) * config.volume / 75.0
    private val angle = (config.pan + 100) * PI / 400
    private val left = gain * cos(angle) * sqrt(2.0)
    private val right = gain * sin(angle) * sqrt(2.0)
    private val inputFilter = Biquad.highPass(sampleRate.toDouble(), preset.bassCut)
    private val stackAmpInput = if (ampEnabled && nextPedal != null) Biquad.highPass(sampleRate * 4.0, preset.bassCut) else null
    private val ampPath = ampEnabled || frontPedal != null
    private val antiAlias = Biquad.lowPass(sampleRate * 4.0, 8200.0)
    private val trebleCut = Biquad.lowPass(sampleRate * 4.0, 9600.0)
    private val dc = Biquad.highPass(sampleRate.toDouble(), 38.0)
    private val bass = Biquad.peak(sampleRate.toDouble(), 185.0, config.bass.toDouble(), 0.72)
    private val mids = Biquad.peak(sampleRate.toDouble(), 740.0, config.mids.toDouble(), 0.8)
    private val treble = Biquad.peak(sampleRate.toDouble(), 2850.0, config.treble.toDouble(), 0.75)
    private val eqEnabled = config.eqEnabled
    private val cabinet = if (config.cabinetEnabled) CabinetConvolution(
        config.cabinetImpulse?.atRate(sampleRate) ?: modeledCabinet(config.cabinet, config.cabinetMic, sampleRate.toDouble())
    ) else null
    private val beforeAmp = space(config.effects.run { copy(
        modulation = if (modulationPosition == ModulationPosition.BeforeAmp) modulation else GuitarModulation.Off,
        delay = GuitarDelay.Off, reverb = GuitarReverb.Off
    ) }, beats)
    private val delayInLoop = config.effects.delayPosition == SpacePosition.AmpLoop &&
        config.effects.delayVoice != GuitarDelayVoice.PingPong
    private val ampLoop = space(config.effects.run { copy(
        modulation = if (modulationPosition == ModulationPosition.AmpLoop && ampEnabled) modulation else GuitarModulation.Off,
        delay = if (delayInLoop) delay else GuitarDelay.Off,
        reverb = if (reverbPosition == SpacePosition.AmpLoop) reverb else GuitarReverb.Off
    ) }, beats)
    private val afterCabinet = space(config.effects.run { copy(
        modulation = if (modulationPosition == ModulationPosition.AfterCabinet ||
            modulationPosition == ModulationPosition.AmpLoop && !ampEnabled) modulation else GuitarModulation.Off,
        delay = if (!delayInLoop) delay else GuitarDelay.Off,
        reverb = if (reverbPosition == SpacePosition.AfterCabinet) reverb else GuitarReverb.Off
    ) }, beats)
    private val loopPower = if (ampLoop != null && ampEnabled && !preset.cleanPower) {
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
            if (ampEnabled && stackAmpInput == null) input = inputFilter.process(input)
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
        frontPedal?.reset()
        nextPedalInput?.reset()
        nextPedal?.reset()
        inputFilter.reset()
        stackAmpInput?.reset()
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
            var signal = lastInput + step * phase
            if (frontPedal != null) signal = frontPedal.process(signal)
            if (nextPedal != null && nextPedalInput != null) {
                signal = nextPedal.process(nextPedalInput.process(signal))
            }
            if (stackAmpInput != null) signal = stackAmpInput.process(signal)
            val power = if (ampEnabled) {
                val preamp = saturate(signal * ampGain + preset.bias) - biasOffset
                if (preset.cleanPower || ampLoop != null) preamp else saturate(preamp * 1.22)
            } else signal
            shaped = antiAlias.process(trebleCut.process(power))
        }
        lastInput = input
        return shaped
    }

    private class DrivePedal(settings: GuitarPedalSettings, rate: Double) {
        private val model = settings.model
        private val gain = 1 + settings.drive / 100.0 * model.gainRange
        private val level = 10.0.pow(settings.levelDb / 20.0)
        private val cutoff = (model.lowPass * 2.0.pow((settings.tone - 50) / 50.0)).coerceAtMost(rate * 0.45)
        private val tone = Biquad.lowPass(rate, cutoff)
        private val mids = if (model == GuitarDrive.MidOverdrive) Biquad.peak(rate, 790.0, 5.0, 0.83) else null
        private val octaveDc = if (model == GuitarDrive.OctaveFuzz) Biquad.highPass(rate, 70.0) else null

        fun process(input: Double): Double {
            val driven = (mids?.process(input) ?: input) * gain
            val clipped = when (model) {
                GuitarDrive.Off, GuitarDrive.Boost -> driven
                GuitarDrive.Overdrive -> (saturate(driven + 0.09) - OVERDRIVE_BIAS) * 0.53
                GuitarDrive.Distortion -> driven.coerceIn(-0.72, 0.72) * 0.56
                GuitarDrive.Fuzz -> (saturate((driven + 0.23) * 2.8) - FUZZ_BIAS) * 0.43
                GuitarDrive.Transparent -> (saturate(driven * 0.65) * 0.85 + driven.coerceIn(-0.9, 0.9) * 0.16) * 0.75
                GuitarDrive.MidOverdrive -> (saturate(driven + 0.14) - MID_DRIVE_BIAS) * 0.50
                GuitarDrive.OctaveFuzz -> {
                    val octave = octaveDc!!.process(abs(saturate(driven * 1.3)))
                    saturate(octave * 2.2 + driven * 0.22) * 0.52
                }
            }
            return tone.process(clipped) * level
        }

        fun reset() {
            tone.reset()
            mids?.reset()
            octaveDc?.reset()
        }
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
        val MID_DRIVE_BIAS = saturate(0.14)
        val FUZZ_BIAS = saturate(0.23 * 2.8)

        fun saturate(x: Double): Double {
            val limited = x.coerceIn(-3.0, 3.0)
            val square = limited * limited
            return limited * (27.0 + square) / (27.0 + 9.0 * square)
        }
    }

    private fun modeledCabinet(cabinet: GuitarCabinet, microphone: CabinetMic, rate: Double): DoubleArray {
        // Generate a causal IR from a speaker resonance and cabinet roll-off. It uses no recording.
        val low = Biquad.highPass(rate, cabinet.resonance, 0.91)
        val peak = Biquad.peak(rate, cabinet.presence, microphone.presenceDb, 0.85)
        val cutoff = cabinet.cutoff * microphone.cutoffRatio
        val high = Biquad.lowPass(rate, minOf(cutoff, rate * 0.45))
        val upper = Biquad.lowPass(rate, minOf(cutoff * 1.32, rate * 0.45))
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
