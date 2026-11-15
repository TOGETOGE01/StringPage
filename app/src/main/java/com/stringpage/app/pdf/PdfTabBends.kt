package com.stringpage.app.pdf

import kotlin.math.abs

/** Pitch offsets in quarter tones, ordered from the attack to the end of the note. */
internal data class PdfBend(val start: Int, val end: Int, val middle: Int? = null) {
    val levels get() = listOfNotNull(start, middle, end)
    val peak get() = maxOf(start, end, middle ?: 0)

    companion object {
        const val MAX_PITCH = 12
        fun hold(value: Int) = PdfBend(value, value)
        fun up(value: Int) = PdfBend(0, value)
    }
}

internal object PdfTabBends {
    private const val UP = "\ueb78"
    private const val DOWN = "\ueb7c"
    private val fraction = Regex("""([1-3])?\s*([¼½¾])""")

    private data class Segment(
        val arrow: PdfText, val from: PdfPoint, val to: PdfPoint,
        val amount: Int?, val vertical: Boolean
    ) {
        val up get() = arrow.text == UP
    }

    private data class Resolved(val segment: Segment, val anchor: Int)

    fun isArrow(span: PdfText, staff: List<Double>, gap: Double): Boolean {
        val top = staff.first()
        val bottom = staff.last()
        return when (span.text) {
            UP -> span.y in (top - gap * 7)..(top + gap * 0.1)
            DOWN -> span.y in (top - gap * 7)..(bottom + gap * 0.4)
            else -> false
        }
    }

    fun apply(
        g: PdfGeometry, staff: List<Double>, gap: Double, measure: ClosedFloatingPointRange<Double>,
        anchors: List<PdfTabAnchor>, precedingNotes: List<PdfTabNote>,
        issues: MutableSet<String>, review: MutableSet<String>
    ) {
        val nearby = g.text.filter { it.x in (measure.start - gap)..(measure.endInclusive + gap * 24) }
        val arrows = nearby.filter { isArrow(it, staff, gap) }
        val segments = findSegments(g, arrows, nearby, staff, gap)
        val matchedArrows = segments.mapTo(HashSet()) { it.arrow }
        val levels = HashMap<Int, MutableList<Int>>()
        val resolved = ArrayList<Resolved>()
        var unresolved = arrows.any { it.center in measure && it !in matchedArrows }

        fun inherited(index: Int): Int? {
            val anchor = anchors[index]
            val preceding = anchors.indices.filter { candidate ->
                anchors[candidate].stem.x < anchor.stem.x && anchors[candidate].note.string == anchor.note.string
            }.sortedByDescending { anchors[it].stem.x }
            for (candidate in preceding) {
                val note = anchors[candidate].note
                if (note.fret != anchor.note.fret) return null
                levels[candidate]?.lastOrNull()?.let { return it }
                if (!note.tied) return null
            }
            return precedingNotes.singleOrNull { it.string == anchor.note.string && it.fret == anchor.note.fret }?.bend?.end
        }

        for (segment in segments.filter { it.from.x in measure }) {
            val connection = resolved.lastOrNull { joins(it.segment, segment, g.dashes, gap) }
            val connectedNote = connection?.let { anchors[it.anchor].note }
            val tied = if (segment.vertical) null else anchors.indices.singleOrNull { index ->
                val anchor = anchors[index]
                anchor.note.tied && !anchor.note.dead && abs(anchor.stem.x - segment.from.x) < gap * 0.45 &&
                    (if (connectedNote != null) {
                        connectedNote.string == anchor.note.string && connectedNote.fret == anchor.note.fret
                    } else (inherited(index) ?: 0) > 0)
            }
            val index = if (segment.vertical) {
                prebendOrigin(anchors, segment.from, gap, levels)
            } else tied ?: if (segment.up) {
                frettedOrigin(anchors, segment.from, gap) ?: connection?.anchor
            } else connection?.anchor

            if (index == null) {
                unresolved = true
                continue
            }
            val note = anchors[index].note
            val curve = levels[index]
            val start = curve?.lastOrNull() ?: if (note.tied) inherited(index) ?: 0 else 0
            val target = segment.amount ?: when {
                segment.vertical && note.tied -> inherited(index)
                !segment.up && abs(segment.arrow.y - anchors[index].line) < gap * 0.75 -> 0
                else -> null
            }
            if (target == null || segment.up && !segment.vertical && target <= start || !segment.up && target >= start) {
                unresolved = true
                continue
            }
            val values = levels.getOrPut(index) { mutableListOf(if (segment.vertical) target else start) }
            if (values.last() != target) values.add(target)
            resolved.add(Resolved(segment, index))
        }

        for ((index, anchor) in anchors.withIndex()) {
            val values = levels[index]
            if (values == null) {
                val value = if (anchor.note.tied) inherited(index) else null
                val hold = value != null && value > 0 && resolved.any { bend ->
                    val origin = anchors[bend.anchor]
                    origin.stem.x < anchor.stem.x && origin.note.string == anchor.note.string &&
                        coversHold(g.dashes, bend.segment.to, anchor.stem.x, gap)
                }
                if (hold) anchor.note = anchor.note.copy(bend = PdfBend.hold(value))
                continue
            }
            val bend = when (values.size) {
                1 -> PdfBend.hold(values.single())
                2 -> PdfBend(values.first(), values.last())
                3 -> PdfBend(values.first(), values.last(), values[1])
                else -> {
                    issues.add("同一音符有多次推弦转折，请校对")
                    PdfBend(values.first(), values.last(), values.max())
                }
            }
            anchor.note = anchor.note.copy(bend = bend)
        }
        if (unresolved) issues.add("推弦归属或幅度待确认")
        if (resolved.isNotEmpty()) review.add("推弦幅度已识别，推弦时机按谱面估算")
    }

    private fun findSegments(
        g: PdfGeometry, arrows: List<PdfText>, spans: List<PdfText>, staff: List<Double>, gap: Double
    ): List<Segment> {
        val labels = HashMap<PdfText, Pair<Double, Int>>()
        for (label in spans) {
            val amount = amount(label.text) ?: continue
            val arrow = arrows.asSequence().filter {
                abs(it.center - label.center) < gap && it.y - label.y in 0.01..(gap * 1.85)
            }.minByOrNull { abs(it.center - label.center) + abs(it.y - label.y - gap * 0.96) } ?: continue
            val distance = abs(arrow.center - label.center) + abs(arrow.y - label.y - gap * 0.96)
            if (distance < (labels[arrow]?.first ?: Double.POSITIVE_INFINITY)) labels[arrow] = distance to amount
        }
        val usedCurves = HashSet<PdfCurve>()
        return arrows.mapNotNull { arrow ->
            val up = arrow.text == UP
            fun tipDistance(end: PdfPoint): Double {
                val difference = arrow.y - end.y
                return if (up) abs(difference) else minOf(abs(difference - gap * 0.5), abs(difference))
            }
            val curve = g.curves.asSequence().filter {
                it !in usedCurves && abs(it.to.x - arrow.center) < gap * 0.55 &&
                    tipDistance(it.to) < gap * 0.40 && it.to.x - it.from.x > gap * 0.2 &&
                    (if (up) it.from.y > it.to.y else it.from.y < it.to.y)
            }.minByOrNull { abs(it.to.x - arrow.center) + tipDistance(it.to) }
            if (curve != null) {
                usedCurves.add(curve)
                return@mapNotNull Segment(arrow, curve.from, curve.to, labels[arrow]?.second, vertical = false)
            }
            if (!up) return@mapNotNull null
            val shaft = g.lines.asSequence().filter {
                abs(it.from.x - it.to.x) < 0.05 && abs(it.from.x - arrow.center) < gap * 0.42 &&
                    abs(minOf(it.from.y, it.to.y) - arrow.y) < gap * 0.30 &&
                    maxOf(it.from.y, it.to.y) in (staff.first() - gap)..(staff.last() + gap * 0.5) &&
                    abs(it.from.y - it.to.y) > gap * 0.7
            }.minByOrNull { abs(it.from.x - arrow.center) } ?: return@mapNotNull null
            val (from, to) = if (shaft.from.y > shaft.to.y) shaft.from to shaft.to else shaft.to to shaft.from
            Segment(arrow, from, to, labels[arrow]?.second, vertical = true)
        }.sortedWith(compareBy({ it.from.x }, { !it.vertical }))
    }

    private fun frettedOrigin(anchors: List<PdfTabAnchor>, start: PdfPoint, gap: Double): Int? =
        anchors.indices.filter { index ->
            val anchor = anchors[index]
            !anchor.note.dead && abs(anchor.right - start.x) < gap * 0.92 &&
                abs(anchor.line - start.y) < gap * 0.45
        }.minByOrNull { index ->
            val anchor = anchors[index]
            abs(anchor.right - start.x) + abs(anchor.line - start.y)
        }

    private fun prebendOrigin(
        anchors: List<PdfTabAnchor>, start: PdfPoint, gap: Double, levels: Map<Int, List<Int>>
    ): Int? {
        val group = anchors.indices.filter { !anchors[it].note.dead && abs(anchors[it].stem.x - start.x) < gap * 0.5 }
        if (group.size <= 1) return group.singleOrNull()
        // For centered arrows over a chord, use the previous bend on the same fingering.
        return group.singleOrNull { index ->
            val current = anchors[index]
            val earlier = anchors.indices.filter { candidate ->
                val anchor = anchors[candidate]
                anchor.stem.x < current.stem.x && anchor.note.string == current.note.string
            }.sortedByDescending { anchors[it].stem.x }
            for (candidate in earlier) {
                val previous = anchors[candidate].note
                if (previous.fret != current.note.fret) break
                if (levels[candidate]?.isNotEmpty() == true) return@singleOrNull true
                if (!previous.tied) break
            }
            false
        }
    }

    private fun joins(previous: Segment, next: Segment, dashes: List<PdfLine>, gap: Double): Boolean {
        val joinedTip = abs(previous.to.x - next.from.x) < gap * 0.4 &&
            abs(previous.to.y - next.from.y) < gap * 0.7
        return joinedTip || abs(previous.to.y - next.from.y) < gap * 0.7 &&
            coversHold(dashes, previous.to, next.from.x, gap)
    }

    private fun coversHold(dashes: List<PdfLine>, start: PdfPoint, x: Double, gap: Double): Boolean =
        dashes.any {
            val left = minOf(it.from.x, it.to.x)
            val right = maxOf(it.from.x, it.to.x)
            abs(it.from.y - it.to.y) < 0.12 && abs(start.y - it.from.y) < gap * 0.7 &&
                abs(left - start.x) < gap * 0.4 && x in (left - gap * 0.15)..(right + gap * 0.35)
        }

    private val PdfText.center get() = (x + right) / 2

    private fun amount(text: String): Int? {
        val value = text.trim().lowercase()
        if (value == "full") return 4
        val match = fraction.matchEntire(value)
        if (match != null) {
            val whole = match.groupValues[1].toIntOrNull() ?: 0
            val part = when (match.groupValues[2]) { "¼" -> 1; "½" -> 2; else -> 3 }
            return (whole * 4 + part).takeIf { it <= PdfBend.MAX_PITCH }
        }
        return when (value) {
            "1/4" -> 1
            "1/2" -> 2
            "3/4" -> 3
            "2" -> 8
            "3" -> 12
            else -> null
        }
    }
}
