@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.model.Automation
import alphaTab.model.AutomationType
import alphaTab.model.Score
import alphaTab.model.SyncPointData
import alphaTab.midi.MidiTickLookup
import kotlin.math.abs

internal object ScoreRecording {
    data class Point(val bar: Int, val occurrence: Int, val position: Double, val timeMs: Double)
    data class Visit(val bar: Int, val occurrence: Int, val start: Double, val end: Double)

    fun visits(lookup: MidiTickLookup): List<Visit> {
        val counts = mutableMapOf<Int, Int>()
        return buildList {
            for (entry in lookup.masterBars) {
                val bar = entry.masterBar.index.toInt()
                val occurrence = counts.getOrDefault(bar, 0)
                counts[bar] = occurrence + 1
                add(Visit(bar, occurrence, entry.start, entry.end))
            }
        }
    }

    fun points(score: Score): List<Point> = buildList {
        for ((bar, measure) in score.masterBars.withIndex()) for (event in measure.syncPoints?.toList().orEmpty()) {
            val data = event.syncPointValue ?: continue
            add(Point(bar, data.barOccurence.toInt(), event.ratioPosition, data.millisecondOffset))
        }
    }

    fun putPoint(score: Score, point: Point): Boolean {
        require(point.bar in 0 until score.masterBars.length.toInt() && point.occurrence >= 0 &&
            point.position in 0.0..1.0 && point.timeMs.isFinite() && point.timeMs >= 0.0) { "请选择有效的同步位置。" }
        val measure = score.masterBars[point.bar]
        val existing = measure.syncPoints?.firstOrNull { event ->
            event.syncPointValue?.barOccurence == point.occurrence.toDouble() &&
                abs(event.ratioPosition - point.position) < 0.000001
        }
        if (existing != null) {
            val data = existing.syncPointValue!!
            if (data.millisecondOffset == point.timeMs) return false
            data.millisecondOffset = point.timeMs
        } else measure.addSyncPoint(Automation().apply {
            type = AutomationType.SyncPoint
            ratioPosition = point.position
            syncPointValue = SyncPointData().apply {
                barOccurence = point.occurrence.toDouble()
                millisecondOffset = point.timeMs
            }
        })
        return true
    }

    fun samePosition(a: Point, b: Point): Boolean =
        a.bar == b.bar && a.occurrence == b.occurrence && abs(a.position - b.position) < 0.000001

    /** Validates the unfolded order, including repeats, before saving the map. */
    fun ordered(points: List<Point>, visits: List<Visit>, durationMs: Double): List<Point> {
        require(durationMs.isFinite() && durationMs > 0.0) { "请先准备录音。" }
        val order = visits.associateBy { it.bar to it.occurrence }
        val byScoreTime = points.map { point ->
            val visit = order[point.bar to point.occurrence]
                ?: throw IllegalArgumentException("第 ${point.bar + 1} 小节第 ${point.occurrence + 1} 遍未出现在播放顺序中。")
            require(point.position in 0.0..1.0 && point.timeMs in 0.0..durationMs) { "同步点超出谱面或录音范围。" }
            val tick = visit.start + point.position * (visit.end - visit.start)
            tick to point
        }.sortedBy { it.first }
        for (i in 1 until byScoreTime.size) {
            val (lastTick, last) = byScoreTime[i - 1]
            val (tick, point) = byScoreTime[i]
            require(tick - lastTick > 0.000001) { "有两个同步点落在同一个谱面位置。" }
            require(point.timeMs - last.timeMs >= 1.0) { "同步点的录音时间须随播放顺序递增。" }
        }
        return byScoreTime.map { it.second }
    }
}
