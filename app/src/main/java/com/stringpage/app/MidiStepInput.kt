package com.stringpage.app

import kotlin.math.abs

internal data class MidiAttack(val pitch: Int, val velocity: Int)

/** Frames MIDI 1.0 bytes across packets, including running status and realtime interleaving. */
internal class MidiByteStream {
    data class Note(val channel: Int, val pitch: Int, val velocity: Int)
    private var status = 0
    private var firstByte = -1
    private var systemExclusive = false

    fun read(bytes: ByteArray, offset: Int, count: Int, note: (Note) -> Unit, clear: (Int) -> Unit) {
        for (index in offset until offset + count) {
            val byte = bytes[index].toInt() and 255
            if (byte >= 0xf8) {
                if (byte == 0xff) { reset(); clear(-1) }
                continue
            }
            if (byte >= 0x80) {
                status = if (byte < 0xf0) byte else 0
                firstByte = -1
                systemExclusive = byte == 0xf0 || (systemExclusive && byte in 0xf1..0xf6)
                continue
            }
            if (systemExclusive || status == 0) continue
            val type = status and 0xf0
            if (type == 0xc0 || type == 0xd0) continue
            if (firstByte < 0) firstByte = byte
            else {
                val channel = status and 15
                when (type) {
                    0x80 -> note(Note(channel, firstByte, 0))
                    0x90 -> note(Note(channel, firstByte, byte))
                    0xb0 -> if (firstByte == 120 || firstByte == 123) clear(channel)
                }
                firstByte = -1
            }
        }
    }

    fun reset() { status = 0; firstByte = -1; systemExclusive = false }
}

internal object MidiFingering {
    data class Position(val string: Int, val fret: Int)

    /**
     * Searches playable fret windows first. Matching may move an earlier pitch to
     * a different string, so repeated candidate strings never cause a false failure.
     */
    fun assign(pitches: List<Int>, open: List<Int>, preferredString: Int, preferredFret: Int?): List<Position> {
        require(pitches.isNotEmpty() && pitches.size <= open.size) { "当前调弦无法同时演奏这么多音符。" }
        val options = pitches.map { pitch ->
            open.mapIndexedNotNull { i, tuning ->
                (pitch - tuning).takeIf { it in 0..BasicScoreEditor.MAX_FRET }?.let { Position(i + 1, it) }
            }
        }
        require(options.all { it.isNotEmpty() }) { "音高超出当前调弦和品位范围。" }
        val anchor = preferredFret?.coerceIn(0, BasicScoreEditor.MAX_FRET) ?: 0
        fun preference(position: Position): Int = abs(position.fret - anchor) * 2 + position.fret +
            if (pitches.size == 1 && preferredString > 0 && position.string != preferredString) 160 else 0

        var best: List<Position>? = null
        var bestCost = Int.MAX_VALUE
        fun tryWindow(low: Int, high: Int) {
            val candidates = options.map { positions ->
                positions.filter { it.fret == 0 || it.fret in low..high }.sortedBy(::preference)
            }
            if (candidates.any { it.isEmpty() }) return
            val owner = IntArray(open.size + 1) { -1 }
            val assigned = arrayOfNulls<Position>(pitches.size)
            fun match(note: Int, seen: BooleanArray): Boolean {
                for (position in candidates[note]) {
                    val string = position.string
                    if (seen[string]) continue
                    seen[string] = true
                    val occupant = owner[string]
                    if (occupant == -1 || match(occupant, seen)) {
                        owner[string] = note
                        assigned[note] = position
                        return true
                    }
                }
                return false
            }
            for (i in pitches.indices.sortedBy { candidates[it].size }) {
                if (!match(i, BooleanArray(open.size + 1))) return
            }
            val result = assigned.map { checkNotNull(it) }
            val fretted = result.filter { it.fret > 0 }
            val spread = (fretted.maxOfOrNull { it.fret } ?: 0) - (fretted.minOfOrNull { it.fret } ?: 0)
            val cost = result.sumOf(::preference) + spread * 4 + (spread - 4).coerceAtLeast(0) * 80
            if (cost < bestCost) { bestCost = cost; best = result }
        }

        for (start in 1..BasicScoreEditor.MAX_FRET) tryWindow(start, start + 4)
        if (best == null) tryWindow(1, BasicScoreEditor.MAX_FRET)
        return requireNotNull(best) { "这组音符无法分配到不同的琴弦。" }
    }
}
