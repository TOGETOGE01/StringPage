package com.stringpage.app.rse

/** PCM bridge to the recovered 32-frame native resampler. Source loops retain
 * their exclusive boundary while the caller applies the native release fade.
 * Source-rate conversion is performed once, before this voice is constructed;
 * the pitch resampler always receives PCM at the output rate.
 */
class NativeSampleVoice(
    private val audio: DecodedBankSample,
    outputRate: Int,
    semitones: Double = 0.0,
    private val gain: Float = 1f,
    private val pitchCorrection: Float = 0f,
    loopStart: Int? = null,
    loopEndExclusive: Int? = null,
    private val decoderGain: Float = 1f,
    private val sampleGain: Float = 1f,
    startingFrame: Int = 0,
    private val pitchModulation: ((Long) -> Float)? = null
) {
    private val resamplers = List(audio.channels) { NativeResampler() }
    private val input = List(audio.channels) { FloatArray(1024) }
    private val output = List(audio.channels) { FloatArray(32) }
    private var cachePosition = 32
    private var position = startingFrame
    private var renderedFrames = 0L
    private val loop: IntRange?
    var pitchSemitones = semitones
        set(value) { require(value.isFinite()); field = value }
    var finished = false
        private set

    init {
        require(outputRate in 8000..192000 && semitones.isFinite() && gain.isFinite() && gain >= 0f && pitchCorrection.isFinite())
        require(audio.sampleRate == outputRate) { "源采样必须先转换到播放采样率。" }
        require(startingFrame in 0..audio.frames)
        require(decoderGain.isFinite() && decoderGain >= 0f && sampleGain.isFinite() && sampleGain >= 0f)
        require((loopStart == null) == (loopEndExclusive == null))
        loop = if (loopStart != null && loopEndExclusive != null) {
            require(loopStart >= 0 && loopEndExclusive > loopStart && loopEndExclusive <= audio.frames)
            loopStart until loopEndExclusive
        } else null
    }

    private fun refill() {
        // readTick clamps the requested pitch before subtracting the sample's
        // stored correction, rather than adding correction into that clamp.
        val requested=pitchSemitones.toFloat()+(pitchModulation?.invoke(renderedFrames) ?: 0f)
        val clamped=if(requested>= -36f) { if(requested<=36f) requested else 36f } else -36f
        val effective = clamped - pitchCorrection
        for (state in resamplers) state.setPitchSemitones(effective)
        val needed = resamplers.first().requiredInputFrames()
        require(resamplers.all { it.requiredInputFrames() == needed })
        for (frame in 0 until needed) {
            if (loop != null && position >= loop.last+1) position = loop.first + (position-loop.first) % (loop.last+1-loop.first)
            for (channel in 0 until audio.channels) input[channel][frame] =
                if (position < audio.frames) audio.samples[position*audio.channels+channel]*decoderGain else 0f
            if (position < audio.frames) position++
        }
        for (channel in 0 until audio.channels) {
            resamplers[channel].process(input[channel],output[channel],needed)
            for(frame in 0 until 32) output[channel][frame] *= sampleGain
        }
        cachePosition = 0
        if (loop == null && position >= audio.frames && resamplers.all { it.isHistorySilent() } &&
            output.all { channel -> channel.all { it == 0f } }) finished = true
    }

    fun render(target: FloatArray, frames: Int) {
        require(frames >= 0 && frames <= target.size/2)
        target.fill(0f,0,frames*2)
        for (frame in 0 until frames) {
            if (finished) return
            if (cachePosition == 32) refill()
            for (channel in 0..1) target[frame*2+channel] = output[if(audio.channels == 1)0 else channel][cachePosition]*gain
            cachePosition++
            renderedFrames++
        }
    }
}
