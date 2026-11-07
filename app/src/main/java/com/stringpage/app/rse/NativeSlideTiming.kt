package com.stringpage.app.rse

/** SlideInterpreter's integer MIDI sequence and float32 tick allocation.
 * Inputs/outputs use the original clock (480 ticks per quarter). The middle
 * control values are not consulted by these two native vector builders.
 * Selection, ties, generated voices and their envelopes belong to the caller.
 */
object NativeSlideTiming {
    const val QUARTER_TICKS = 480
    const val AUTO_BRUSH_REDUCED_TICKS = 20

    private fun count(first: Int, last: Int): Int {
        val count = kotlin.math.abs(last.toLong()-first.toLong())
        require(count <= 1024) { "Slide interval exceeds the supported allocation bound" }
        return count.toInt()
    }

    /** b3fd0: integer addition after float32 multiply/truncation; final endpoint
     * does not subtract brush ticks. Short intervals can produce decreasing
     * intermediate times in the original and are retained at this layer.
     */
    fun ticks(first: Int, last: Int, start: Int, end: Int, offset: Int, brush: Int): IntArray {
        val count = count(first,last)
        val step = (end-start-brush).toFloat()/count.toFloat()
        return IntArray(count+1) { at ->
            if (at == count) end+offset else (at.toFloat()*step).toInt()+start+offset
        }
    }

    /** b3e40: includes first and excludes last, ascending or descending. */
    fun midis(first: Int, last: Int, midi: Int): IntArray {
        val count = count(first,last)
        val direction = if (first < last) 1 else -1
        return IntArray(count) { at -> midi+first+at*direction }
    }
}
