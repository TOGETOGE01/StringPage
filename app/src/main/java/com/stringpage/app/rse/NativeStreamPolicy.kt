package com.stringpage.app.rse

/** SoundFontV7's exact, case-sensitive member-name exceptions. These high
 * Eastern piano members deliberately have loopEnd set to loopStart upstream;
 * their authored sampler chunk remains unchanged in the metadata inspector. */
object NativeStreamPolicy {
    private val disabledLoops = listOf("03","04","05","07").flatMap { layer ->
        (81..108 step 3).map { midi -> "EasternGrandC7-Key-Sus-none-$layer-none-${midi.toString().padStart(3,'0')}-none.ogg" }
    }.toSet()

    fun disablesLoop(memberName: String): Boolean = memberName in disabledLoops

    fun forwardLoop(memberName: String, loops: List<RseLoop>, frameCount: Int, sourceRate: Int = 44100): RseLoop? {
        require(frameCount >= 0 && sourceRate in 8000..192000)
        if (disablesLoop(memberName)) return null
        return loops.asSequence().filter { it.type == 0L && it.start >= 0 && it.start < it.end }
            // AudioStream copies the decoder's sampler coordinates directly.
            // IDecoder.outputFrameCount is used for PCM length, not these fields.
            .firstOrNull { it.start < it.end && it.end <= frameCount.toLong() }
    }
}
