package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.round

internal data class PdfMeter(val beats: Int, val unit: Int) {
    val length get() = beats.toDouble() / unit
    override fun toString() = "$beats/$unit"
}

internal data class PdfStaff(val rows: List<Double>) {
    val top get() = rows.first()
    val bottom get() = rows.last()
    val gap get() = (bottom - top) / rows.lastIndex
    val center get() = (top + bottom) / 2
}

internal data class PdfTabStaff(val tab: PdfStaff, val notation: PdfStaff?, val edges: List<Double>)

internal object PdfTabLayout {
    private val notationClefs = '\ue050'..'\ue065'

    fun systems(geometry: PdfGeometry): List<List<PdfTabStaff>> {
        val marks = geometry.text.filter { it.text in PdfGeometry.TAB_CLEFS }.sortedBy { it.y }
        pdfRequire(marks.isNotEmpty(), "没有找到可识别的 Guitar Pro 电子六线谱。")
        val rows = geometry.lines.asSequence().filter {
            abs(it.from.y - it.to.y) < 0.05 && abs(it.from.x - it.to.x) > 18
        }.map { round(it.from.y * 100) / 100 }.distinct().sorted().toList()
        val connectors = geometry.lines.filter {
            abs(it.from.x - it.to.x) < 0.08 && abs(it.from.y - it.to.y) > 31
        }
        val grouped = marks.groupBy { mark ->
            connectors.filter {
                mark.x - it.from.x in 0.0..12.0 && mark.y in minOf(it.from.y, it.to.y)..maxOf(it.from.y, it.to.y)
            }.maxByOrNull { abs(it.from.y - it.to.y) } ?: mark
        }
        var previousBottom = 0.0
        return grouped.values.map { members ->
            val staves = members.map { mark ->
                val nearby = rows.filter { abs(it - mark.y) <= 50 }
                val counts = if (mark.text == "\ue06e") 4..5 else 5..9
                val tab = counts.flatMap { count -> regular(nearby, count) }
                    .filter { abs(it.center - mark.y) < it.gap * 0.3 }
                    .maxByOrNull { it.rows.size }
                    ?: throw PdfConversionException("无法确定 TAB 标记对应的弦线。")
                val edges = measureEdges(geometry, mark, tab)
                val clef = geometry.text.lastOrNull { span ->
                    span.text.length == 1 && span.text[0] in notationClefs &&
                        abs(span.x - mark.x) < 16 && span.y in (previousBottom + 2)..(tab.top - 7) && tab.top - span.y < 65
                }
                val notation = clef?.let { symbol ->
                    regular(rows.filter { it in (symbol.y - 17)..(symbol.y + 17) }, 5)
                        .filter { it.bottom < tab.top && abs(it.gap - tab.gap * 2 / 3) < 0.6 }
                        .minByOrNull { abs(it.center - symbol.y + it.gap) }
                }
                previousBottom = tab.bottom
                PdfTabStaff(tab, notation, edges)
            }
            val master = staves.first().edges
            pdfRequire(staves.all { staff -> staff.edges.size == master.size && staff.edges.zip(master).all { (a, b) -> abs(a - b) < 1.0 } },
                "同一谱表的声部小节边界无法对齐。")
            staves
        }
    }

    private fun regular(rows: List<Double>, count: Int): List<PdfStaff> = rows.windowed(count).mapNotNull { lines ->
        val staff = PdfStaff(lines)
        staff.takeIf { staff.gap in 2.0..11.0 && lines.zipWithNext().all { (a, b) -> abs(b - a - staff.gap) < 0.13 } }
    }

    private fun measureEdges(g: PdfGeometry, mark: PdfText, staff: PdfStaff): List<Double> {
        val top = staff.top
        val bottom = staff.bottom
        val gap = staff.gap
        val staffStart = g.lines.asSequence().filter { abs(it.from.y - top) < 0.08 && abs(it.to.y - top) < 0.08 }
            .map { minOf(it.from.x, it.to.x) }.filter { it in (mark.x - gap * 2.2)..mark.x }.minOrNull()
        val vertical = g.lines.filter {
            abs(it.from.x - it.to.x) < 0.08 &&
                abs(minOf(it.from.y, it.to.y) - top) < 0.22 && abs(maxOf(it.from.y, it.to.y) - bottom) < 0.22
        }.map { it.from.x }
        val rectangles = g.fills.filter {
            it.right - it.left < gap / 2 && abs(it.top - top) < 0.22 && abs(it.bottom - bottom) < 0.22
        }.map { (it.left + it.right) / 2 }
        val edges = ArrayList<Double>()
        for (x in (vertical + rectangles + listOfNotNull(staffStart)).sorted()) {
            if (edges.isNotEmpty() && x - edges.last() < gap / 2) edges[edges.lastIndex] = x
            else edges.add(x)
        }
        pdfRequire(edges.size > 1 && abs(edges.first() - mark.x) < gap * 2.2, "无法确定六线谱的小节边界。")
        return edges
    }

    fun printedMeter(g: PdfGeometry, staff: PdfStaff, left: Double, right: Double): PdfMeter? {
        val spans = g.text.filter { it.x in left..right && it.y in staff.top..staff.bottom &&
            it.text.isNotEmpty() && it.text.all { glyph -> glyph in '\ue080'..'\ue08b' } }
        if (spans.isEmpty()) return null
        if (spans.singleOrNull()?.text == "\ue08a") return PdfMeter(4, 4)
        if (spans.singleOrNull()?.text == "\ue08b") return PdfMeter(2, 2)
        // Guitar Pro places the two numbers on separate baselines. On a five-line staff the lower one sits on the middle line.
        val split = spans.minOf { it.y } + staff.gap * 0.6
        val top = spans.filter { it.y < split }.sortedBy { it.x }
        val bottom = spans.filter { it.y >= split }.sortedBy { it.x }
        fun digits(row: List<PdfText>): Int? = row.joinToString("") { span ->
            span.text.map { glyph -> '0' + (glyph - '\ue080') }.joinToString("")
        }.toIntOrNull()
        val numerator = digits(top)
        val denominator = digits(bottom)
        pdfRequire(numerator != null && numerator in 1..32 && denominator in setOf(1, 2, 4, 8, 16, 32) &&
            abs(top.first().x - bottom.first().x) <= staff.gap * 2 &&
            bottom.maxOf { it.y } - top.minOf { it.y } <= staff.gap * 2.8, "检测到无法确认的拍号。")
        return PdfMeter(numerator!!, denominator!!)
    }
}
