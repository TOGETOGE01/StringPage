package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.log10

internal object PdfTabWhammy {
    private val pitchLabel = Regex("""([-−]?)(\d{0,2})([¼½¾]?)""")
    private const val reviewMessage = "颤音杆曲线有未能确认的部分，请对照原谱"
    private const val maxPitch = 24

    private data class Mark(val text: PdfText, val pitch: Int) {
        val x get() = (text.x + text.right) / 2
    }

    fun read(geometry: PdfGeometry, staff: PdfTabStaff, bar: PdfTabBar, onsets: List<Double>): PdfTabBar {
        val notation = staff.notation
        val tab = readStaff(geometry, staff.tab, bar, onsets)
        return if (tab == bar && notation != null) readStaff(geometry, notation, bar, onsets) else tab
    }

    private fun readStaff(geometry: PdfGeometry, staff: PdfStaff, bar: PdfTabBar, onsets: List<Double>): PdfTabBar {
        val gap = staff.gap
        val bounds = bar.bounds
        val above = (staff.top - gap * 6)..(staff.top - gap * 0.3)
        val labels = geometry.text.asSequence().filter {
            it.x in (bounds.left - gap * 0.5)..(bounds.right + gap) && it.y in above &&
                it.font.startsWith("TimesNewRoman", ignoreCase = true) && it.size in (gap * 0.98)..(gap * 1.22)
        }.mapNotNull { text -> parsePitch(text.text)?.let { Mark(text, it) } }.toList()
        if (labels.isEmpty()) return bar
        fun eligible(line: PdfLine) = line.from.y in above && line.to.y in above &&
            minOf(line.from.x, line.to.x) >= bounds.left - gap &&
            maxOf(line.from.x, line.to.x) <= bounds.right + gap
        val leads = geometry.dashes.filter { eligible(it) && abs(it.from.x - it.to.x) < gap * 0.04 }
        val slopes = geometry.lines.filter { abs(it.to.y - it.from.y) > gap * 0.22 }
        val holds = geometry.dashes.filter { abs(it.to.y - it.from.y) < gap * 0.04 }
        val lines = (slopes.asSequence() + holds.asSequence()).filter { eligible(it) && abs(it.to.x - it.from.x) >= gap * 0.55 }
            .map { if (it.from.x < it.to.x) it else PdfLine(it.to, it.from) }.distinct()
            .sortedBy { it.from.x }.toMutableList()

        val corrections = bar.beats.toMutableList()
        var review = false
        var top = bounds.top
        fun same(a: PdfPoint, b: PdfPoint) = abs(a.x - b.x) <= gap * 0.045 && abs(a.y - b.y) <= gap * 0.045
        while (lines.isNotEmpty()) {
            val start = lines.removeAt(0)
            val vertices = arrayListOf(start.from, start.to)
            while (true) {
                val next = lines.filter { same(it.from, vertices.last()) }.singleOrNull() ?: break
                vertices.add(next.to)
                lines.remove(next)
            }
            val lead = leads.singleOrNull { same(it.to, vertices.first()) }
            val anchors = labels.mapNotNull { label ->
                val vertex = vertices.filter {
                    val guideY = if (lead != null && it == vertices.first()) minOf(it.y, lead.from.y) else it.y
                    abs(it.x - label.x) <= gap * 0.85 && guideY - label.text.y in (gap * 0.28)..(gap * 1.3)
                }.minByOrNull { abs(it.x - label.x) } ?: return@mapNotNull null
                label to vertex
            }
            if (anchors.isEmpty()) continue
            top = minOf(top, (minOf(anchors.minOf { it.first.text.y }, vertices.minOf { it.y }) - gap).coerceAtLeast(0.0))
            // Native GP vector PDF uses logarithmic spacing in quarter tones.
            val zeros = anchors.map { (mark, point) -> point.y + height(mark.pitch, gap) }
            val zero = lead?.from?.y ?: zeros.average()
            if (bar.issues.isNotEmpty() || onsets.size != bar.beats.size ||
                zero !in (staff.top - gap * 5)..(staff.top - gap) || zeros.any { abs(it - zero) > gap * 0.015 }) {
                review = true
                continue
            }
            val pitches = vertices.map { point ->
                (-maxPitch..maxPitch).singleOrNull { abs(point.y + height(it, gap) - zero) <= gap * 0.009 }
            }
            for ((index, from, to) in beatSections(vertices, onsets, bounds.right, gap)) {
                if (from == null || to == null) {
                    review = true
                    continue
                }
                val beat = corrections[index]
                val shape = pitches.subList(from, to + 1)
                val first = shape.first()
                val last = shape.last()
                val middle = shape.getOrNull(1).takeIf { shape.size == 3 }
                val midpoint = (vertices[from].x + vertices[to].x) / 2
                val supported = shape.size == 2 || (shape.size == 3 && abs(midpoint - vertices[from + 1].x) <= gap * 0.12)
                if (first == null || last == null || shape.any { it == null } || !supported ||
                    (first != 0 && (from != 0 || lead == null)) ||
                    beat.notes.none { !it.dead } || beat.grace != null || beat.whammy != null) {
                    review = true
                } else if (shape.any { it != 0 }) {
                    corrections[index] = beat.copy(whammy = PdfWhammy(first, last, middle))
                    // A connected stroke can hide the authored starting pitch of the next beat.
                    if (from != 0) review = true
                }
            }
            if (onsets.none { abs(it - vertices.first().x) <= gap * 0.19 }) review = true
        }
        val messages = if (review) bar.review + reviewMessage else bar.review
        return bar.copy(beats = corrections, bounds = bounds.copy(top = top), review = messages)
    }

    private data class Section(val index: Int, val from: Int?, val to: Int?)

    private fun beatSections(vertices: List<PdfPoint>, onsets: List<Double>, right: Double, gap: Double): List<Section> =
        onsets.mapIndexedNotNull { index, onset ->
            if (onset < vertices.first().x - gap * 0.19 || onset >= vertices.last().x - gap * 0.19) return@mapIndexedNotNull null
            val next = onsets.getOrNull(index + 1)
            val from = vertices.indices.singleOrNull { abs(vertices[it].x - onset) <= gap * 0.19 }
            val to = if (next == null) {
                vertices.lastIndex.takeIf { right - vertices.last().x in (-gap * 0.15)..(gap * 1.5) }
            } else if (vertices.last().x <= next + gap * 0.19) {
                vertices.lastIndex.takeIf { next - vertices.last().x <= gap * 0.95 }
            } else {
                vertices.indices.singleOrNull { abs(vertices[it].x - next) <= gap * 0.19 }
            }
            Section(index, from, to?.takeIf { from != null && it > from })
        }

    private fun parsePitch(value: String): Int? {
        val match = pitchLabel.matchEntire(value.trim()) ?: return null
        val (sign, whole, fraction) = match.destructured
        val quarters = (whole.toIntOrNull() ?: 0) * 4 + when (fraction) {
            "¼" -> 1
            "½" -> 2
            "¾" -> 3
            else -> 0
        }
        return quarters.takeIf { it in 1..maxPitch }?.let { if (sign.isEmpty()) it else -it }
    }

    private fun height(pitch: Int, gap: Double): Double =
        when {
            pitch < 0 -> -height(-pitch, gap)
            pitch > 0 -> gap * (1.1 + log10(pitch.toDouble()))
            else -> 0.0
        }
}
