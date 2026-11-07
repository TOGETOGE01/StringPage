package com.stringpage.app.rse

/** Independent implementation of the five-dimensional AMSoundFont matching metric.
 * Observed in AMSoundFont 8.1.3.67 at 0x180007740 / init at 0x180007a40.
 * This is a selection primitive, not the complete articulation/fallback state machine.
 */
data class SampleCoordinate(
    val stringIndex: Int = -1,
    val midi: Int = -1,
    val dynamic: Int = 9,
    val option: Int = 0,
    val fret: Int = -1
) {
    fun distance(other: SampleCoordinate): Float {
        var result = 0f
        if (stringIndex >= 0 && other.stringIndex >= 0 && fret >= 0 && other.fret >= 0) {
            val difference = ((other.fret - fret) - other.midi + midi).toFloat()
            result += difference * 100f * difference
        }
        val pitchDifference = (midi - other.midi).toFloat()
        result += pitchDifference * 10f * pitchDifference
        val dynamicDifference = (dynamic - other.dynamic).toFloat()
        result += dynamicDifference * 20f * dynamicDifference
        // Option and fret have weight zero. Fret also participates in the special
        // string term above; replacing it with a direct string-index distance is wrong.
        return result
    }

    companion object {
        /** Strictly smaller replaces the winner: native equal-distance ties keep first. */
        fun <T> nearest(request: SampleCoordinate, samples: Iterable<T>, coordinate: (T) -> SampleCoordinate): T? {
            var winner: T? = null
            var best = Float.POSITIVE_INFINITY
            for (sample in samples) {
                val distance = request.distance(coordinate(sample))
                if (distance < best) { winner = sample; best = distance }
            }
            return winner
        }
    }
}
