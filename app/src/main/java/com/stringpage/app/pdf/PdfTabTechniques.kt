package com.stringpage.app.pdf

import kotlin.math.abs

internal enum class PdfSlideIn { BELOW, ABOVE }
internal enum class PdfSlideOut { SHIFT, LEGATO, UP, DOWN }
internal enum class PdfVibrato { SLIGHT, WIDE }
internal enum class PdfGrace { BEFORE_BEAT, ON_BEAT }

/** Whammy bar pitch offsets in quarter tones, independent of individual string bends. */
internal data class PdfWhammy(val start: Int, val end: Int, val middle: Int? = null)

internal data class PdfTabStem(val x: Double, val end: Double?, val short: Boolean = false)
internal data class PdfTabAnchor(
    val stem: PdfTabStem, val right: Double, val line: Double, var note: PdfTabNote,
    val left: Double = 2 * stem.x - right
)

internal object PdfTabTechniques {
    private val slideLabels = setOf("sl.", "slide")
    private val hammerLabels = setOf("H", "P")
    private data class Span(val start: Double, val end: Double, val y: Double)

    fun apply(g: PdfGeometry, spans: List<PdfText>, staff: List<Double>, gap: Double, anchors: List<PdfTabAnchor>, review: MutableSet<String>) {
        val top = staff.first()
        val annotations = spans.filter { it.y in (top - gap * 6)..top }
        slides(g, staff, gap, anchors, annotations, review)
        hammerPull(g, gap, anchors, annotations, review)
        vibrato(g.text, gap, anchors, top, review)
        val rings = annotations.filter { it.text.trim().lowercase() == "let ring" }
        for (ring in rings) {
            val center = (ring.x + ring.right) / 2
            val start = anchors.minByOrNull { abs(it.stem.x - center) }?.stem?.x
                ?.takeIf { abs(it - center) <= gap } ?: continue
            val ending = g.dashes.filter {
                it.from.x - ring.right in 0.0..gap && abs(it.from.y - it.to.y) < 0.08 && abs(it.from.y - ring.y) < gap / 2
            }.maxOfOrNull { maxOf(it.from.x, it.to.x) } ?: start
            for (anchor in anchors.filter { it.stem.x in start..(ending + gap / 3) && !it.note.dead }) {
                anchor.note = anchor.note.copy(letRing = true)
            }
        }
    }

    private fun slides(
        g: PdfGeometry, staff: List<Double>, gap: Double, anchors: List<PdfTabAnchor>, annotations: List<PdfText>, review: MutableSet<String>
    ) {
        val marked = annotations.filter { it.text.trim().lowercase() in slideLabels }.toMutableSet()
        for (stroke in g.lines) {
            val (from, to) = if (stroke.from.x <= stroke.to.x) stroke.from to stroke.to else stroke.to to stroke.from
            val slope = (to.y - from.y) / gap
            val length = (to.x - from.x) / gap
            val y = (from.y + to.y) / 2
            val row = staff.minBy { abs(it - y) }
            if (abs(row - y) > gap * 0.12) continue
            val sameString = anchors.filter { it.line == row }
            val origin = sameString.filter { (from.x - it.right) / gap in 0.06..0.83 }
                .minByOrNull { abs((from.x - it.right) / gap - 0.28) }
            val target = sameString.filter { (it.left - to.x) / gap in 0.06..0.83 }
                .minByOrNull { abs((it.left - to.x) / gap - 0.28) }
            if (length in 0.58..0.80 && abs(slope) in 0.57..0.80) {
                if (origin != null && target != null) continue
                if (origin != null) origin.note = origin.note.copy(slideOut = if (slope < 0) PdfSlideOut.UP else PdfSlideOut.DOWN)
                if (target != null) target.note = target.note.copy(slideIn = if (slope < 0) PdfSlideIn.BELOW else PdfSlideIn.ABOVE)
                continue
            }
            if (length < 0.55 || abs(slope) !in 0.40..0.55 || origin == null || target == null || origin.stem.x >= target.stem.x) continue
            val label = marked.firstOrNull { mark -> (mark.x + mark.right) / 2 in from.x..to.x }
            val slur = g.filledArcs.any { arc ->
                val a = minOf(arc.from.x, arc.to.x)
                val b = maxOf(arc.from.x, arc.to.x)
                val lift = abs(arc.control1.y - arc.from.y)
                val boundary = abs(arc.from.y - row)
                lift in (gap * 0.58)..(gap * 1.6) &&
                    ((abs(a - origin.stem.x) < gap * 0.48 && abs(b - target.stem.x) < gap * 0.48 && boundary in gap * 0.21..gap * 0.66) ||
                        (a - origin.right in 0.0..(gap * 0.65) && b < target.left && boundary < gap * 0.15))
            }
            if (label != null) marked.remove(label)
            origin.note = origin.note.copy(slideOut = if (label != null || slur) PdfSlideOut.LEGATO else PdfSlideOut.SHIFT)
        }
        if (marked.isNotEmpty()) review.add("有滑音的起止音需核对")
    }

    private fun hammerPull(g: PdfGeometry, gap: Double, anchors: List<PdfTabAnchor>, annotations: List<PdfText>, review: MutableSet<String>) {
        val pairs = anchors.groupBy { it.note.string }.values.flatMap { notes -> notes.sortedBy { it.stem.x }.zipWithNext() }
        for (mark in annotations.filter { it.text.trim() in hammerLabels }) {
            val x = (mark.x + mark.right) / 2
            val hammer = mark.text.trim() == "H"
            val pair = pairs.filter { (a, b) ->
                !a.note.dead && !b.note.dead && x in a.stem.x..b.stem.x &&
                    (if (hammer) b.note.fret > a.note.fret else b.note.fret < a.note.fret) &&
                    g.filledArcs.any { arc ->
                        val left = minOf(arc.from.x, arc.to.x)
                        val right = maxOf(arc.from.x, arc.to.x)
                        a.stem.x >= left - gap / 2 && b.stem.x <= right + gap / 2 &&
                            (a.line - arc.from.y) / gap in 0.18..0.92 && abs(arc.control1.y - mark.y) < gap * 0.85
                    }
            }.singleOrNull()
            if (pair == null) review.add("有击弦或勾弦的起止音需核对")
            else pair.first.note = pair.first.note.copy(hammerPull = true)
        }
    }

    private fun vibrato(
        all: List<PdfText>, gap: Double, anchors: List<PdfTabAnchor>, top: Double, review: MutableSet<String>
    ) {
        val kinds = mapOf('\ueab2' to PdfVibrato.SLIGHT, '\ueab3' to PdfVibrato.WIDE)
        for ((kind, effect) in kinds) {
            val marks = all.filter { it.text.isNotEmpty() && it.text.all { ch -> ch == kind } &&
                it.y in (top - gap * 6)..top }.sortedBy { it.x }
            val runs = ArrayList<Span>()
            for (mark in marks) {
                val last = runs.lastOrNull()?.takeIf { abs(it.y - mark.y) < gap * 0.15 && mark.x - it.end < gap * 0.18 }
                if (last == null) runs.add(Span(mark.x, mark.right, mark.y))
                else runs[runs.lastIndex] = last.copy(end = maxOf(last.end, mark.right))
            }
            for (notes in anchors.filter { !it.note.dead }.groupBy { it.stem }.values) {
                val x = notes.first().stem.x
                if (runs.none { x in (it.start - gap * 0.2)..(it.end - gap * 0.32) }) continue
                if (notes.size == 1) notes.first().note = notes.first().note.copy(vibrato = effect)
                else review.add("和弦中的揉弦弦号需核对")
            }
        }
    }
}
