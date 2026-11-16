@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.collections.List as AlphaList
import alphaTab.midi.MidiFile
import alphaTab.midi.TempoChangeEvent
import alphaTab.synth.BackingTrackSyncPoint

/** Unfolded MIDI tempo map in source time, independent of practice speed. */
internal class PreparedScoreTime(file: MidiFile) {
    private data class Tempo(val tick: Double, val ms: Double, val bpm: Double)
    private val division = file.division
    private val endTick = file.events.lastOrNull()?.tick ?: 0.0
    private val tempos = ArrayList<Tempo>().apply {
        add(Tempo(0.0, 0.0, 120.0))
        for (event in file.events) {
            if (event !is TempoChangeEvent) continue
            val previous = last()
            val ms = previous.ms + (event.tick - previous.tick) * 60_000.0 / (previous.bpm * division)
            if (event.tick == previous.tick) removeAt(lastIndex)
            add(Tempo(event.tick, ms, event.beatsPerMinute.takeIf { it.isFinite() && it > 0.0 } ?: 120.0))
        }
    }

    private fun at(tick: Double): Tempo {
        val point = tempos.lastOrNull { it.tick <= tick } ?: tempos.first()
        return Tempo(tick, point.ms + (tick - point.tick) * 60_000.0 / (point.bpm * division), point.bpm)
    }

    fun timeAt(tick: Double) = at(tick).ms

    fun syncPoints(offset: Double, duration: Double): AlphaList<BackingTrackSyncPoint> {
        // Adding the tempo boundaries prevents interpolation across tempo changes.
        // Reserve a frame at the end for alphaTab's final interpolation segment.
        val last = at((endTick - 1.0).coerceAtLeast(0.0))
        val points = if (last.tick > 0) tempos.takeWhile { it.tick < last.tick } + last else listOf(tempos.first())
        val anchors = AlphaList<BackingTrackSyncPoint>()
        for (point in points) {
            val sync = point.ms + offset
            if (sync < duration - 0.023 || point.tick == 0.0) anchors.push(BackingTrackSyncPoint().apply {
                synthTick = point.tick
                synthTime = point.ms
                synthBpm = point.bpm
                syncBpm = point.bpm
                syncTime = sync
            })
        }
        return anchors
    }
}
