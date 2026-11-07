package com.stringpage.app.rse

import kotlin.math.floor
import kotlin.math.pow

/** Bank audition voice: stereo-preserving resampling and forward sustain loops.
 * AMAudio passes the stored smpl end through unchanged and wraps before that
 * frame; loopEndExclusive therefore follows the engine, rather than RIFF's label.
 * The short preview release is our own envelope; RSE envelope/DSP parity is not claimed.
 */
class SampleVoice(
    private val audio: DecodedBankSample,
    outputRate: Int,
    semitones: Double = 0.0,
    private val gain: Float = 0.2f,
    loopStart: Int? = null,
    loopEndExclusive: Int? = null
) {
    private val rateRatio: Double = audio.sampleRate.toDouble() / outputRate
    private var step: Double = 0.0
    var pitchSemitones: Double = semitones
        set(value) {
            require(value.isFinite() && value in -96.0..96.0)
            field = value; step = rateRatio * 2.0.pow(value / 12.0)
        }
    private val loop: IntRange?
    private var position = 0.0
    var finished = false
        private set
    init {
        require(outputRate in 8000..192000 && semitones.isFinite() && semitones in -48.0..48.0)
        require(gain.isFinite() && gain in 0f..4f)
        step = rateRatio * 2.0.pow(semitones / 12.0)
        require((loopStart == null) == (loopEndExclusive == null))
        loop = if (loopStart != null && loopEndExclusive != null) {
            require(loopStart >= 0 && loopEndExclusive > loopStart && loopEndExclusive <= audio.frames)
            loopStart until loopEndExclusive
        } else null
    }

    /** Writes interleaved stereo; the caller owns note-off scheduling and final output limiting. */
    fun render(output: FloatArray, frames: Int, sustain: Boolean = true) {
        require(frames >= 0 && frames <= output.size / 2)
        output.fill(0f, 0, frames * 2)
        for (frame in 0 until frames) {
            val activeLoop = if (sustain) loop else null
            if (activeLoop != null && position >= activeLoop.last + 1.0) {
                val length = activeLoop.last + 1.0 - activeLoop.first
                position = activeLoop.first + (position - activeLoop.first) % length
            }
            if (position >= audio.frames) { finished = true; return }
            val current = floor(position).toInt()
            val next = if (activeLoop != null && current == activeLoop.last) activeLoop.first
                else (current + 1).coerceAtMost(audio.frames - 1)
            val mix = (position - current).toFloat()
            for (channel in 0..1) {
                val source = if (audio.channels == 1) 0 else channel
                val a = audio.samples[current * audio.channels + source]
                val b = audio.samples[next * audio.channels + source]
                output[frame * 2 + channel] = (a + (b - a) * mix) * gain
            }
            position += step
        }
    }
}
