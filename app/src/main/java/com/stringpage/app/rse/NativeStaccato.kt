package com.stringpage.app.rse

/** Registry stage after SlideInterpreter. Only the original automation's end
 * is replaced; the slide children already created retain their own ranges. */
object NativeStaccato {
    fun endTick(accentFlags: Int,soundingTicks: Int,startTick: Int): Int? {
        if(accentFlags and 3 == 0) return null
        val divisor=((accentFlags.inv() and 1)*2+2).toDouble()
        return (soundingTicks.toDouble()/divisor).toInt()+startTick
    }
}
