package com.stringpage.app.rse

/** Parameter-only SlideInterpreter transitions observed in GPRSE 8.1.3.67.
 *
 * These helpers do not choose which original/generated note or slide phase is
 * affected. Native predicate/error flags, tied-note traversal and generated
 * slide scheduling must be resolved by the caller. In particular, assigning
 * 512 frames to every note bearing a slide symbol would not reproduce this.
 * StartingCuePoint is an engine enum; the player applies its own frame clamp.
 */
object NativeFadePolicy {
    const val SLIDE_FADE_FRAMES = 512L
    data class State(
        val fadeInFrames: Long = NativeNoteAmplitude.DEFAULT_FADE_IN_FRAMES,
        val fadeOutFrames: Long = NativeNoteAmplitude.DEFAULT_FADE_OUT_FRAMES,
        val startingCuePoint: Int = 2,
        val userDefinedFrame: Long = 0,
        val shouldDecreaseAmplitude: Boolean = false,
        val decreaseAmplitudeFactor: Float = 1f
    )

    /** RVA b4a70; also inlined in the predecessor-legato branch of b2630. */
    fun legatoDestination(state: State?, previousMidi: Int, currentMidi: Int): State? = state?.copy(
        fadeInFrames = SLIDE_FADE_FRAMES,
        startingCuePoint = 3,
        userDefinedFrame = 15000,
        shouldDecreaseAmplitude = true,
        decreaseAmplitudeFactor = if (previousMidi > currentMidi) 1f else state.decreaseAmplitudeFactor
    )

    /** RVA b4af0; independently controlled in/out changes preserve other state. */
    fun shift(state: State?, fadeIn: Boolean, fadeOut: Boolean): State? = state?.copy(
        fadeInFrames = if (fadeIn) SLIDE_FADE_FRAMES else state.fadeInFrames,
        fadeOutFrames = if (fadeOut) SLIDE_FADE_FRAMES else state.fadeOutFrames,
        startingCuePoint = 2
    )

    /** RVA b4b60; fade-in enables the decrease flag, without resetting it otherwise. */
    fun generatedSegment(
        state: State?, fadeIn: Boolean, fadeOut: Boolean, previousMidi: Int, currentMidi: Int
    ): State? = state?.copy(
        fadeInFrames = if (fadeIn) SLIDE_FADE_FRAMES else state.fadeInFrames,
        fadeOutFrames = if (fadeOut) SLIDE_FADE_FRAMES else state.fadeOutFrames,
        startingCuePoint = 3,
        userDefinedFrame = 1024,
        shouldDecreaseAmplitude = if (fadeIn) true else state.shouldDecreaseAmplitude,
        decreaseAmplitudeFactor = if (fadeIn && previousMidi > currentMidi) 1f else state.decreaseAmplitudeFactor
    )
}
