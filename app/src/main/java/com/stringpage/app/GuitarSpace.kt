package com.stringpage.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Stereo processing after the cabinet and EQ. Each instance belongs to one track. */
internal class GuitarSpace(effects: GuitarEffects, private val sampleRate: Int, beats: SourceBeatMap) {
    private val chorus = if (effects.modulation == GuitarModulation.Chorus && effects.modulationDepth > 0) {
        Chorus(effects.modulationDepth, sampleRate)
    } else null
    private val phaser = if (effects.modulation == GuitarModulation.Phaser && effects.modulationDepth > 0) {
        Phaser(effects.modulationDepth)
    } else null
    private val delay = if (effects.delay != GuitarDelay.Off) Echo(effects, sampleRate, beats) else null
    private val reverb = if (effects.reverb != GuitarReverb.Off) Reverb(effects, sampleRate) else null
    private val phaseStep = 2 * PI * effects.modulationRate / (100.0 * sampleRate)
    private var phase = 0.0
    private var lastInputFrame = Long.MIN_VALUE
    var left = 0.0
        private set
    var right = 0.0
        private set

    fun setSpeed(speed: Double) { delay?.setSpeed(speed) }

    fun updateControls(effects: GuitarEffects) {
        delay?.updateControls(effects)
        reverb?.updateMix(effects.reverbMix)
    }

    fun process(sample: Double, frame: Long) {
        if (abs(sample) >= 0.0001) lastInputFrame = frame
        left = sample
        right = sample
        if (chorus != null) {
            chorus.process(sample, sin(phase), cos(phase))
            left = chorus.left
            right = chorus.right
        } else if (phaser != null) {
            left = phaser.process(sample, sin(phase), 0)
            right = phaser.process(sample, cos(phase), 1)
        }
        if (chorus != null || phaser != null) {
            phase += phaseStep
            if (phase >= 2 * PI) phase -= 2 * PI
        }
        if (delay != null) {
            delay.process(left, right, frame)
            left = delay.left
            right = delay.right
        }
        if (reverb != null) {
            reverb.process(left, right)
            left = reverb.left
            right = reverb.right
        }
    }

    fun remainingFrames(frame: Long, speed: Double): Int {
        if (lastInputFrame == Long.MIN_VALUE) return 0
        val budget = (chorus?.tailFrames ?: 0) + (delay?.tailFrames ?: 0) + (reverb?.tailFrames ?: 0)
        return (budget - (frame - lastInputFrame)).coerceIn(0L, (sampleRate * speed * 4.0).toLong()).toInt()
    }

    fun reset() {
        phase = 0.0
        lastInputFrame = Long.MIN_VALUE
        chorus?.reset()
        phaser?.reset()
        delay?.reset()
        reverb?.reset()
    }

    private class DelayLine(capacity: Int) {
        private val samples = FloatArray(capacity)
        private var cursor = 0

        fun read(delay: Double): Double {
            val whole = delay.toInt()
            var index = cursor - whole
            if (index < 0) index += samples.size
            val earlier = if (index > 0) index - 1 else samples.lastIndex
            return samples[index] + (samples[earlier] - samples[index]) * (delay - whole)
        }

        fun write(value: Double) {
            samples[cursor] = value.toFloat()
            if (++cursor == samples.size) cursor = 0
        }

        fun reset() {
            samples.fill(0f)
            cursor = 0
        }
    }

    private class Chorus(depth: Int, sampleRate: Int) {
        val tailFrames = sampleRate * 24 / 1000
        private val line = DelayLine(tailFrames + 2)
        private val center = sampleRate * 0.013
        private val sweep = sampleRate * (0.001 + 0.008 * depth / 100.0)
        private val mix = depth * 0.0045
        var left = 0.0
        var right = 0.0

        fun process(sample: Double, sine: Double, cosine: Double) {
            left = sample * (1 - mix * 0.55) + line.read(center + sweep * sine) * mix
            right = sample * (1 - mix * 0.55) + line.read(center + sweep * cosine) * mix
            line.write(sample)
        }

        fun reset() = line.reset()
    }

    private class Phaser(depth: Int) {
        private val strength = depth / 100.0
        private val state = Array(2) { DoubleArray(4) }
        private val feedback = DoubleArray(2)

        fun process(sample: Double, oscillator: Double, channel: Int): Double {
            val coefficient = 0.64 + 0.31 * strength * oscillator
            var wet = sample + feedback[channel] * 0.22
            val stages = state[channel]
            for (stage in stages.indices) {
                val result = stages[stage] - coefficient * wet
                stages[stage] = wet + coefficient * result
                wet = result
            }
            feedback[channel] = wet
            return sample * (1 - strength * 0.43) + wet * strength * 0.43
        }

        fun reset() {
            state.forEach { it.fill(0.0) }
            feedback.fill(0.0)
        }
    }

    private class Echo(effects: GuitarEffects, private val rate: Int, private val beats: SourceBeatMap) {
        private val note = effects.delay.beats
        private var fixedMs = effects.delayMs
        private var feedback = effects.delayFeedback / 100.0
        private var feedbackTarget = feedback
        private var mix = effects.delayMix / 100.0
        private var mixTarget = mix
        private val ramp = 1 - exp(-1.0 / (0.008 * rate))
        private val damping = 1 - exp(-2 * PI * 4600 / rate)
        private val maxDelay = if (note == 0.0) rate * 1.8
            else minOf(rate * 6.0, beats.maxFramesPerBeat * note)
        private val leftLine = DelayLine(ceil(maxDelay).toInt() + 2)
        private val rightLine = DelayLine(ceil(maxDelay).toInt() + 2)
        private var fixedDelay = -1.0
        private var previousDelay = 0.0
        private var transition = 1.0
        private val transitionStep = 1.0 / (0.018 * rate)
        private var filteredLeft = 0.0
        private var filteredRight = 0.0
        private var lastDelay = 0.0
        private var repeats = repeatCount(feedback)
        val tailFrames get() = if (maxOf(mix, mixTarget) <= 0.001) 0 else ceil(repeats * lastDelay).toInt()
        var left = 0.0
        var right = 0.0

        fun updateControls(effects: GuitarEffects) {
            fixedMs = effects.delayMs
            feedbackTarget = effects.delayFeedback / 100.0
            mixTarget = effects.delayMix / 100.0
            repeats = repeatCount(feedbackTarget)
        }

        fun setSpeed(speed: Double) {
            if (note != 0.0) return
            // Android stretches the finished PCM. Shorten the source tap to keep its real-time duration fixed.
            val frames = (fixedMs * rate / 1000.0 * speed).coerceIn(1.0, maxDelay)
            if (fixedDelay == frames) return
            if (fixedDelay >= 0.0) {
                previousDelay += (fixedDelay - previousDelay) * transition
                transition = 0.0
            } else previousDelay = frames
            fixedDelay = frames
        }

        fun process(inputLeft: Double, inputRight: Double, frame: Long) {
            lastDelay = if (note > 0) beats.delayFramesAt(frame, note).coerceIn(1.0, maxOf(maxDelay, 1.0)) else fixedDelay
            var echoLeft = leftLine.read(lastDelay)
            var echoRight = rightLine.read(lastDelay)
            if (transition < 1.0) {
                echoLeft = previous(leftLine, echoLeft)
                echoRight = previous(rightLine, echoRight)
                transition = minOf(1.0, transition + transitionStep)
            }
            filteredLeft += (echoLeft - filteredLeft) * damping
            filteredRight += (echoRight - filteredRight) * damping
            mix += (mixTarget - mix) * ramp
            feedback += (feedbackTarget - feedback) * ramp
            leftLine.write(inputLeft + filteredLeft * feedback)
            rightLine.write(inputRight + filteredRight * feedback)
            left = inputLeft + echoLeft * mix
            right = inputRight + echoRight * mix
        }

        fun reset() {
            leftLine.reset()
            rightLine.reset()
            fixedDelay = -1.0
            previousDelay = 0.0
            transition = 1.0
            filteredLeft = 0.0
            filteredRight = 0.0
            lastDelay = 0.0
            mix = mixTarget
            feedback = feedbackTarget
        }

        private fun previous(line: DelayLine, echo: Double): Double {
            val old = line.read(previousDelay)
            return old + (echo - old) * transition
        }

        private fun repeatCount(amount: Double) = if (amount == 0.0) 1 else 1 + ceil(ln(0.003) / ln(amount)).toInt()
    }

    private class Reverb(effects: GuitarEffects, sampleRate: Int) {
        private val size = effects.reverb.size
        private val decaySeconds = (0.35 + 2.25 * effects.reverbDecay / 100.0) * size
        private val decayFrames = ceil(decaySeconds * sampleRate + sampleRate * 0.08).toInt()
        val tailFrames get() = if (maxOf(mix, mixTarget) <= 0.001) 0 else decayFrames
        private var mix = effects.reverbMix * 0.01
        private var mixTarget = mix
        private val ramp = 1 - exp(-1.0 / (0.008 * sampleRate))
        private val diffusion = Array(2) { index -> AllPass(sampleRate, (5.3 + index * 2.8) * size) }
        private val lines = doubleArrayOf(29.7, 37.1, 41.1, 43.7).map { ms ->
            val frames = (ms * size * sampleRate / 1000.0).roundToInt().coerceAtLeast(1)
            ReverbLine(frames, exp(-6.9078 * frames / (sampleRate * decaySeconds)), effects.reverb)
        }
        var left = 0.0
        var right = 0.0

        fun updateMix(amount: Int) { mixTarget = amount * 0.01 }

        fun process(inputLeft: Double, inputRight: Double) {
            val wetLeft = diffusion[0].process(inputLeft) * 0.47
            val wetRight = diffusion[1].process(inputRight) * 0.47
            val a = lines[0].read()
            val b = lines[1].read()
            val c = lines[2].read()
            val d = lines[3].read()
            val sum = (a + b + c + d) * 0.5
            lines[0].write(wetLeft + sum - a)
            lines[1].write(wetRight + sum - b)
            lines[2].write(-wetLeft + sum - c)
            lines[3].write(-wetRight + sum - d)
            mix += (mixTarget - mix) * ramp
            left = inputLeft + mix * (a + b - c - d) * 0.63
            right = inputRight + mix * (a - b - c + d) * 0.63
        }

        fun reset() {
            diffusion.forEach { it.reset() }
            lines.forEach { it.reset() }
            mix = mixTarget
        }
    }

    private class AllPass(rate: Int, ms: Double) {
        private val frames = (rate * ms / 1000).roundToInt().coerceAtLeast(1)
        private val line = DelayLine(frames + 1)

        fun process(input: Double): Double {
            val read = line.read(frames.toDouble())
            val output = read - input * 0.62
            line.write(input + output * 0.62)
            return output
        }

        fun reset() = line.reset()
    }

    private class ReverbLine(private val frames: Int, private val feedback: Double, type: GuitarReverb) {
        private val line = DelayLine(frames + 1)
        private val damp = when (type) {
            GuitarReverb.Room -> 0.30
            GuitarReverb.Plate -> 0.54
            else -> 0.38
        }
        private var filter = 0.0

        fun read() = line.read(frames.toDouble())

        fun write(input: Double) {
            filter += (input - filter) * damp
            line.write(filter * feedback)
        }

        fun reset() {
            line.reset()
            filter = 0.0
        }
    }
}
