package com.stringpage.app

/** Conversions between unfolded quarter-note beats and source PCM frames. */
internal class SourceBeatMap(sampleRate: Int, points: List<Tempo> = emptyList()) {
    data class Tempo(val timeMs: Double, val bpm: Double)

    private val tempos = points.filter { it.timeMs.isFinite() && it.bpm.isFinite() && it.bpm > 0.0 }
        .distinctBy { it.timeMs }.sortedBy { it.timeMs }.ifEmpty { listOf(Tempo(0.0, 120.0)) }
    private val frames = DoubleArray(tempos.size) { tempos[it].timeMs * sampleRate / 1000.0 }
    private val framesPerBeat = DoubleArray(tempos.size) { sampleRate * 60.0 / tempos[it].bpm }
    private val beatAt = DoubleArray(tempos.size).apply {
        for (index in 1 until size) {
            this[index] = this[index - 1] + (frames[index] - frames[index - 1]) / framesPerBeat[index - 1]
        }
    }
    val maxFramesPerBeat = framesPerBeat.max()

    fun delayFramesAt(frame: Long, beatCount: Double): Double {
        val source = frame.toDouble()
        val current = segment(frames, source)
        val targetBeat = beatAt[current] + (source - frames[current]) / framesPerBeat[current] - beatCount
        val target = segment(beatAt, targetBeat)
        val pastFrame = frames[target] + (targetBeat - beatAt[target]) * framesPerBeat[target]
        return source - pastFrame
    }

    private fun segment(points: DoubleArray, value: Double): Int {
        val index = points.binarySearch(value)
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }
}
