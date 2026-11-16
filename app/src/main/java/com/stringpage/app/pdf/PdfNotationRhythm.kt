package com.stringpage.app.pdf

import kotlin.math.abs

internal object PdfNotationRhythm {
    val rests = ('\ue4e3'..'\ue4e9').mapIndexed { index, char -> char.toString() to (1 shl index) }.toMap()
    private val heads = mapOf(
        '\ue0a2' to 1, '\ue0a3' to 2, '\ue0a4' to 4,
        '\ue0d8' to 1, '\ue0d9' to 2, '\ue0db' to 4, // Diamond harmonics.
        '\ue0a7' to 1, '\ue0a8' to 2, '\ue0a9' to 4 // X noteheads.
    )
    private val flags = ('\ue240'..'\ue247').mapIndexed { index, char -> char.toString() to (1 + index / 2) }.toMap()

    internal data class Event(
        val x: Double, val rest: Boolean, val denominator: Int, val dots: Int = 0,
        val triplet: Boolean = false, val tiesFromPrevious: Int = 0, val grace: PdfGrace? = null
    )
    private data class Stem(val line: PdfLine, val end: Double, val up: Boolean)
    private data class Notehead(val span: PdfText, val kind: Int, val stem: Stem?, val grace: PdfGrace? = null)
    private data class Positioned(val event: Event, val baselines: List<Double>, val right: Double)

    fun read(g: PdfGeometry, staff: PdfStaff, bar: PdfTabBar, smallTabFrets: List<Double> = emptyList(), bendArrows: List<Double> = emptyList()): List<Event>? {
        val gap = staff.gap
        val spans = g.text.filter { it.x > bar.bounds.left && it.x < bar.bounds.right &&
            it.y in (staff.top - gap * 5)..(staff.bottom + gap * 5) }
        val lines = g.lines.filter { abs(it.from.x - it.to.x) < 0.08 && it.from.x in bar.bounds.left..bar.bounds.right &&
            abs(it.from.y - it.to.y) in (gap * 1.9)..(gap * 13) }
        val hasWhammyCue = spans.any { it.text.trim().equals("w/bar", ignoreCase = true) && it.y < staff.top }
        val onsets = ArrayList<Positioned>()
        val noteheads = ArrayList<Notehead>()
        for (span in spans) {
            val duration = rests[span.text]
            if (duration != null && span.y in (staff.top - gap)..(staff.bottom + gap * 1.1)) {
                onsets.add(Positioned(Event((span.x + span.right) / 2, true, duration), listOf(span.y), span.right))
            }
            val kind = span.text.mapNotNull { heads[it] }.takeIf { it.isNotEmpty() && it.size == span.text.length }?.distinct()?.singleOrNull()
                ?: continue
            val small = span.size < gap * 3.6
            val center = (span.x + span.right) / 2
            val grace = if (small && smallTabFrets.any { abs(it - center) <= gap * 1.2 }) {
                val slashed = spans.any { it.text in setOf("\ue564", "\ue565") && abs(it.x - center) < gap * 1.4 }
                if (slashed) PdfGrace.BEFORE_BEAT else PdfGrace.ON_BEAT
            } else null
            // Guitar Pro draws miniature pitch cues without TAB frets for bends and whammy effects.
            if (small && grace == null && (hasWhammyCue || bendArrows.any { abs(it - center) < gap * 6.5 })) continue
            if (kind == 1) {
                noteheads.add(Notehead(span, kind, null, grace))
                continue
            }
            val candidates = lines.mapNotNull { line ->
                val lo = minOf(line.from.y, line.to.y)
                val hi = maxOf(line.from.y, line.to.y)
                val x = line.from.x
                if (span.y !in (lo - gap * 0.7)..(hi + gap * 0.7)) return@mapNotNull null
                val up = span.y - lo >= hi - span.y
                val expected = if (up) span.right else span.x
                val distance = minOf(abs(x - expected), abs(x - span.x) + gap * 0.14, abs(x - span.right) + gap * 0.14)
                if (distance > gap * 0.58) null else distance to Stem(line, if (up) lo else hi, up)
            }.sortedBy { it.first }
            val best = candidates.firstOrNull()
            if (best == null) {
                if (span.text.all { it == '\ue0a9' } && spans.any { other ->
                        abs(other.x - span.x) < gap * 0.62 && other.text.isNotEmpty() && other.text.all { heads[it] == 1 }
                    }) {
                    noteheads.add(Notehead(span, 1, null))
                    continue
                }
                return null
            }
            if (candidates.getOrNull(1)?.let { it.first - best.first < gap * 0.18 } == true) return null
            noteheads.add(Notehead(span, kind, best.second, grace))
        }
        val chordHeads = noteheads.groupBy { it.stem?.line }
        for ((line, notes) in chordHeads) {
            if (line == null) {
                val chords = ArrayList<MutableList<Notehead>>()
                for (head in notes.sortedBy { it.span.x }) {
                    val chord = chords.lastOrNull()?.takeIf { abs(it.first().span.x - head.span.x) <= gap }
                    if (chord == null) chords.add(arrayListOf(head)) else chord.add(head)
                }
                for (chord in chords) onsets.add(positioned(chord, 1))
                continue
            }
            val outgoing = notes.map { it.stem!! }.groupingBy { it }.eachCount().maxBy { it.value }.key
            val duration = notes.map { it.kind }.distinct().singleOrNull() ?: return null
            if (duration != 4) {
                onsets.add(positioned(notes, duration))
                continue
            }
            val x = line.from.x
            fun nearBeams(stem: Stem) = g.beams.mapNotNull { beam ->
                if (x !in (beam.left - gap * 0.15)..(beam.right + gap * 0.15)) return@mapNotNull null
                val delta = beam.centerAt(x) - stem.end
                if (abs(delta) <= gap * 2.45 && (if (stem.up) delta >= -gap * 0.5 else delta <= gap * 0.5)) delta else null
            }
            val opposite = outgoing.copy(end = if (outgoing.up) maxOf(line.from.y, line.to.y) else minOf(line.from.y, line.to.y), up = !outgoing.up)
            val stem = if (nearBeams(outgoing).none { abs(it) < gap * 0.55 } && nearBeams(opposite).any { abs(it) < gap * 0.55 }) opposite else outgoing
            val nearbyBeams = nearBeams(stem)
            val count = if (nearbyBeams.any { abs(it) < gap * 0.55 }) {
                nearbyBeams.map { (it * 20).toInt() }.distinct().size
            } else spans.filter { abs(it.x - x) < gap * 0.55 && abs(it.y - stem.end) < gap * 1.1 }
                .maxOfOrNull { flags[it.text] ?: 0 } ?: 0
            if (count > 4) return null
            onsets.add(positioned(notes, 4 shl count))
        }
        val sorted = onsets.sortedBy { it.event.x }
        if (sorted.isEmpty() || sorted.zipWithNext().any { (a, b) -> b.event.x - a.event.x < gap * 0.5 }) return null
        val dots = HashMap<Int, MutableList<Double>>()
        for (dot in spans.filter { it.text == "\ue1e7" }) {
            val preceding = sorted.indexOfLast { it.event.x < dot.x }
            if (preceding < 0) continue
            val onset = sorted[preceding]
            if (dot.x - onset.right in (-gap * 0.4)..(gap * 2.1) && onset.baselines.any { abs(it - dot.y) < gap * 0.68 }) {
                val columns = dots.getOrPut(preceding) { ArrayList() }
                if (columns.none { abs(it - dot.x) < gap * 0.25 }) columns.add(dot.x)
            }
        }
        val triplets = HashSet<Int>()
        for (label in spans.filter { it.text.trim() == "3" && it.font.contains("Italic", true) &&
            (it.y < staff.top - gap || it.y > staff.bottom + gap) }) {
            val mid = (label.x + label.right) / 2
            val i = sorted.indices.minByOrNull { abs(sorted[it].event.x - mid) } ?: return null
            if (i !in 1 until sorted.lastIndex || abs(sorted[i].event.x - mid) > gap * 1.3 ||
                sorted.subList(i - 1, i + 2).any { it.event.grace != null } ||
                sorted.subList(i - 1, i + 2).map { it.event.denominator }.distinct().size != 1) return null
            triplets.addAll(i - 1..i + 1)
        }
        val arcs = g.filledArcs.filter { abs(it.from.y - it.to.y) < gap * 0.1 }
            .map { if (it.from.x < it.to.x) it.from to it.to else it.to to it.from }
        return sorted.mapIndexed { index, onset ->
            val previous = sorted.getOrNull(index - 1)
            val ties = if (previous == null || previous.event.rest || onset.event.rest) 0 else arcs.count { (a, b) ->
                a.x - previous.event.x in 0.0..(gap * 2.5) && onset.event.x - b.x in (-gap * 0.6)..(gap * 2.5) &&
                    previous.baselines.any { abs(it - a.y) < gap * 0.65 } && onset.baselines.any { abs(it - b.y) < gap * 0.65 }
            }
            onset.event.copy(dots = dots[index]?.size ?: 0, triplet = index in triplets, tiesFromPrevious = ties)
        }
            .takeIf { events -> events.all { it.dots <= 2 } }
    }

    private fun positioned(notes: List<Notehead>, duration: Int): Positioned {
        val first = notes.minBy { it.span.x }.span
        return Positioned(Event((first.x + first.right) / 2, false, duration, grace = notes.map { it.grace }.distinct().singleOrNull()),
            notes.map { it.span.y }, notes.maxOf { it.span.right })
    }
}
