@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.collections.List as AlphaList
import alphaTab.midi.MasterBarTickLookup
import alphaTab.midi.MidiFile
import kotlin.math.abs
import kotlin.math.ceil

enum class MetronomeRhythm(val label: String, private val quarters: Double) {
    Meter("跟随拍号", 0.0),
    Half("二分音符", 2.0),
    Quarter("四分音符", 1.0),
    Eighth("八分音符", 0.5),
    Triplet("八分三连音", 1.0 / 3),
    Sixteenth("十六分音符", 0.25);

    internal fun spacing(numerator: Int, denominator: Int): Double {
        if (this != Meter) return quarters
        val group = if (denominator >= 8 && numerator >= 6 && numerator % 3 == 0) 3 else 1
        return 4.0 * group / denominator
    }
}

internal data class MetronomeSettings(val bpm: Int? = null, val rhythm: MetronomeRhythm = MetronomeRhythm.Meter) {
    companion object { val bpmRange = 20..300 }
}

/** Converts between score milliseconds and the source audio's unscaled milliseconds. */
internal interface MetronomeTimeMap {
    fun scoreAt(media: Double): Double
    fun mediaAt(score: Double): Double

    companion object {
        fun offset(ms: Double): MetronomeTimeMap = object : MetronomeTimeMap {
            override fun scoreAt(media: Double) = media - ms
            override fun mediaAt(score: Double) = score + ms
        }
    }
}

internal class RecordingMetronomeMap(score: DoubleArray, media: DoubleArray) : MetronomeTimeMap {
    private val pairs = score.indices.map { score[it] to media[it] }.filter { (s, m) -> s.isFinite() && m.isFinite() }
        .fold(mutableListOf<Pair<Double, Double>>()) { valid, next ->
            val previous = valid.lastOrNull()
            if (previous == null || (next.first > previous.first && next.second > previous.second)) valid.add(next)
            valid
        }
    private val scorePoints = pairs.map { it.first }.toDoubleArray()
    private val mediaPoints = pairs.map { it.second }.toDoubleArray()

    override fun scoreAt(media: Double) = interpolate(media, mediaPoints, scorePoints)
    override fun mediaAt(score: Double) = interpolate(score, scorePoints, mediaPoints)

    private fun interpolate(value: Double, from: DoubleArray, to: DoubleArray): Double {
        if (from.isEmpty()) return value
        if (from.size == 1) return value - from[0] + to[0]
        val found = from.binarySearch(value)
        if (found >= 0) return to[found]
        val index = (-found - 2).coerceIn(0, from.lastIndex - 1)
        return to[index] + (value - from[index]) * ((to[index + 1] - to[index]) / (from[index + 1] - from[index]))
    }
}

/** Shares tempo and rhythm settings while giving each playback source its own clock. */
internal class ReaderMetronome {
    @Volatile var settings = MetronomeSettings()
    @Volatile var volume = 0.0
    private var pendingId = 0L
    private var grid: Grid? = null

    fun prepare(file: MidiFile, bars: AlphaList<MasterBarTickLookup>, time: PreparedScoreTime, synth: Boolean) {
        cancelPendingLoad()
        grid = null
        if (bars.length == 0.0 || !file.division.isFinite() || file.division <= 0.0) return
        val measures = bars.mapNotNull { bar ->
            val numerator = bar.masterBar.timeSignatureNumerator.toInt()
            val denominator = bar.masterBar.timeSignatureDenominator.toInt()
            if (numerator <= 0 || denominator <= 0 || bar.end <= bar.start) null
            else Measure(bar.start + file.tickShift, bar.end + file.tickShift, numerator, denominator)
        }
        if (measures.isNotEmpty()) {
            grid = Grid(measures, time, file.division)
            if (synth) {
                pendingId = AlphaTabMetronomeBridge.register(file, clock(MetronomeTimeMap.offset(0.0))!!)
            }
        }
    }

    fun clock(map: MetronomeTimeMap): AlphaTabMetronomeBridge.Clock? = grid?.let { Timeline(this, it, map) }

    fun reset() {
        cancelPendingLoad()
        grid = null
    }

    fun cancelPendingLoad() {
        if (pendingId == 0L) return
        AlphaTabMetronomeBridge.cancel(pendingId)
        pendingId = 0L
    }

    private data class Measure(val start: Double, val end: Double, val numerator: Int, val denominator: Int)
    private data class Grid(val measures: List<Measure>, val time: PreparedScoreTime, val division: Double)

    private class Timeline(
        private val controller: ReaderMetronome,
        private val grid: Grid,
        private val map: MetronomeTimeMap
    ) : AlphaTabMetronomeBridge.Clock {
        private var previousEnd = Double.NaN
        private var previous = MetronomeSettings()
        private var remainingMs = 0.0
        private var fixedClick = 0

        override fun mute() { previousEnd = Double.NaN }

        override fun render(start: Double, end: Double, speed: Double, clicks: AlphaTabMetronomeBridge.Clicks) {
            if (end <= start || !speed.isFinite() || speed <= 0.0) return
            val config = controller.settings
            val bpm = config.bpm
            if (bpm == null) {
                followScore(start, end, config.rhythm, clicks)
            } else {
                if (config != previous || !previousEnd.isFinite() || abs(start - previousEnd) > 0.01) {
                    remainingMs = 0.0
                    fixedClick = 0
                }
                val quarters = config.rhythm.spacing(4, 4)
                val period = 60_000.0 * quarters / bpm
                val clicksPerBar = (4 / quarters).toInt()
                val renderedMs = (end - start) / speed
                while (remainingMs < renderedMs) {
                    val at = start + remainingMs * speed
                    clicks.emit(at, grid.time.tickAt(map.scoreAt(at)), fixedClick, quarters * grid.division, period * speed)
                    fixedClick = (fixedClick + 1) % clicksPerBar
                    remainingMs += period
                }
                remainingMs -= renderedMs
            }
            previous = config
            previousEnd = end
        }

        private fun followScore(start: Double, end: Double, rhythm: MetronomeRhythm, clicks: AlphaTabMetronomeBridge.Clicks) {
            // Use half-open windows so a click on a render boundary is played just once.
            val time = grid.time
            val measures = grid.measures
            val from = time.tickAt(map.scoreAt(start - 0.00001))
            val until = time.tickAt(map.scoreAt(end - 0.00001))
            val found = measures.binarySearchBy(from) { it.start }
            var index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
            while (index < measures.size && measures[index].start < until) {
                val bar = measures[index++]
                val step = rhythm.spacing(bar.numerator, bar.denominator) * grid.division
                var count = ceil((from - bar.start) / step).toInt().coerceAtLeast(0)
                val limit = minOf(bar.end, until)
                var tick = bar.start + count * step
                while (tick < limit) {
                    val at = map.mediaAt(time.timeAt(tick))
                    val duration = map.mediaAt(time.timeAt(minOf(tick + step, bar.end))) - at
                    clicks.emit(at, tick, count, step, duration)
                    count++
                    tick = bar.start + count * step
                }
            }
        }
    }
}
