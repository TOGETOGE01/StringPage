package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class RasterTabStaff(val rows: List<Int>, val edges: List<Int>) {
    val top get() = rows.first()
    val bottom get() = rows.last()
    val gap get() = (bottom - top) / (rows.size - 1).toDouble()
}

/** In outlined web prints the barlines remain strokes, even though note labels are artwork. */
internal class PdfRasterPrintLayout(private val pageWidth: Double, private val lines: List<PdfLine>,
                                   private val outlines: List<PdfBox> = emptyList(), previouslyConfirmed: Boolean = true) {
    var confirmed = previouslyConfirmed
        private set

    fun matches(staves: List<RasterTabStaff>, imageWidth: Int): Boolean {
        if (confirmed) return true
        val scale = imageWidth / pageWidth
        confirmed = staves.any { detected ->
            val staff = align(detected, imageWidth) ?: return@any false
            val g = staff.gap
            val digits = outlinesIn(staff, imageWidth).filter { box ->
                (box.bottom - box.top) / g in 0.62..1.04 && (box.right - box.left) / g in 0.23..0.81 &&
                    staff.rows.any { y -> abs((box.bottom + box.top) / 2 - y) <= g * 0.22 }
            }
            if (digits.size < 4) return@any false
            // Browser prints use the same stem lane below all six strings, independent of fret position.
            lines.asSequence().filter { line ->
                val x = line.from.x * scale
                val top = minOf(line.from.y, line.to.y) * scale
                val bottom = maxOf(line.from.y, line.to.y) * scale
                abs(line.to.x - line.from.x) * scale < 0.5 && x > staff.edges.first() && x < staff.edges.last() &&
                    (top - staff.bottom) / g in 0.65..2.15 && (bottom - staff.bottom) / g in 2.13..3.05 &&
                    (bottom - top) / g in 0.65..2.20 &&
                    digits.any { box -> abs((box.left + box.right) / 2 - x) <= g * 0.70 }
            }.map { (it.from.x * scale).roundToInt() }.distinct().take(4).count() == 4
        }
        return confirmed
    }

    fun recoverStaves(detected: List<RasterTabStaff>, imageWidth: Int): List<RasterTabStaff> {
        val frame = detected.maxByOrNull { it.edges.last() - it.edges.first() } ?: return detected
        val scale = imageWidth / pageWidth
        val left = frame.edges.first()
        val right = frame.edges.last()
        val margin = frame.gap * 0.21
        val horizontal = lines.filter { abs(it.from.y - it.to.y) * scale < 0.5 }
        val extra = lines.mapNotNull { edge ->
            if (abs(edge.from.x - edge.to.x) * scale >= 0.5 || abs(edge.from.x * scale - left) > margin) return@mapNotNull null
            val top = minOf(edge.from.y, edge.to.y) * scale
            val bottom = maxOf(edge.from.y, edge.to.y) * scale
            if ((bottom - top) / frame.gap !in 4.86..5.14 ||
                detected.any { top <= it.bottom && bottom >= it.top }) return@mapNotNull null
            // When digits cover a string, the line may survive only as short strokes between adjacent frets.
            val gap = (bottom - top) / 5
            val rows = (0..5).map { (top + gap * it).roundToInt() }
            val coverage = rows.map { y ->
                horizontal.filter { abs(it.from.y * scale - y) <= margin }.sumOf {
                    val a = minOf(it.from.x, it.to.x) * scale
                    val b = maxOf(it.from.x, it.to.x) * scale
                    (minOf(b, right.toDouble()) - maxOf(a, left.toDouble())).coerceAtLeast(0.0)
                } / (right - left)
            }
            if (coverage.any { it < 0.035 } || coverage.count { it >= 0.72 } < 4) return@mapNotNull null
            align(RasterTabStaff(rows, listOf(left, right)), imageWidth)
        }.distinctBy { it.top }
        return (detected + extra).sortedBy { it.top }
    }

    fun outlinesIn(staff: RasterTabStaff, imageWidth: Int, belowStaff: Boolean = false): List<PdfBox> {
        val scale = imageWidth / pageWidth
        val margin = staff.gap * 0.7
        val top = if (belowStaff) staff.bottom + margin else staff.top - margin
        val bottom = staff.bottom + if (belowStaff) staff.gap * 4.2 else margin
        return outlines.asSequence().filter { box ->
            box.left * scale > staff.edges.first() && box.right * scale < staff.edges.last() &&
                box.top * scale >= top && box.bottom * scale <= bottom
        }.map { PdfBox(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }.toList()
    }
    fun align(staff: RasterTabStaff, imageWidth: Int): RasterTabStaff? {
        val scale = imageWidth / pageWidth
        val margin = staff.gap * 0.20
        val left = staff.edges.first()
        val right = staff.edges.last()
        val endMargin = staff.gap * 0.78
        val edges = lines.mapNotNull { line ->
            val x = line.from.x * scale
            val top = minOf(line.from.y, line.to.y) * scale
            val bottom = maxOf(line.from.y, line.to.y) * scale
            x.roundToInt().takeIf {
                abs(line.to.x - line.from.x) * scale < 0.5 && x in (left - margin)..(right + endMargin) &&
                    abs(top - staff.top) <= margin && abs(bottom - staff.bottom) <= margin
            }
        }.distinct().sorted()
        // At a final double bar, raster detection may choose the inside stroke.
        val outside = edges.lastOrNull()?.takeIf { it > right + margin }
        val confirmedEnd = outside?.takeIf { x ->
            staff.rows.count { y ->
                lines.any { line ->
                    abs(line.from.y - line.to.y) * scale < 0.5 && abs(line.from.y * scale - y) <= margin &&
                        abs(maxOf(line.from.x, line.to.x) * scale - x) <= margin &&
                        abs(line.to.x - line.from.x) * scale >= staff.gap * 2
                }
            } >= 4
        }
        val end = confirmedEnd ?: edges.lastOrNull { abs(it - right) <= margin } ?: return null
        val start = edges.first().takeIf { abs(it - left) <= margin } ?: left
        val aligned = listOf(start) + edges.filter { it > start + margin && it < end - endMargin } + end
        return staff.copy(edges = aligned)
    }
}

internal object PdfRasterLayout {
    private data class Grid(val rows: List<Int>, val strength: Double)

    fun staves(image: PdfRasterImage): List<RasterTabStaff> {
        val contrast = DoubleArray(image.height) { y -> (0 until image.width).sumOf { x -> image[x, y] } / 255.0 }
        val peaks = peaks(contrast, image.width * 0.17, 3)
        val grids = ArrayList<Grid>()
        val minGap = (image.width * 0.0055).coerceAtLeast(7.0)
        val maxGap = (image.width * 0.045).coerceAtMost(110.0)
        for ((i, top) in peaks.withIndex()) {
            for (j in i + 1 until peaks.size) {
                val gap = peaks[j] - top
                if (gap > maxGap) break
                if (gap < minGap) continue
                // Use the first and last row to estimate the spacing at subpixel precision.
                for (last in peaks.filter { abs(it - top - gap * 5) <= 8 }) {
                    val step = (last - top) / 5.0
                    val rows = (0..5).map { row ->
                        val target = top + step * row
                        peaks.minBy { abs(it - target) }
                    }
                    val errors = rows.mapIndexed { row, y -> abs(y - top - step * row) }
                    if (rows.distinct().size != 6 || errors.any { it > maxOf(1.75, step * 0.1) }) continue
                    if (peaks.any { abs(it - (top - step)) < step * 0.13 || abs(it - (last + step)) < step * 0.13 }) continue
                    val strength = rows.sumOf { contrast[it] / image.width } - errors.sum() * 0.15
                    grids.add(Grid(rows, strength))
                }
            }
        }
        val accepted = ArrayList<RasterTabStaff>()
        for (grid in grids.sortedByDescending { it.strength }) {
            if (accepted.any { it.top <= grid.rows.last() && grid.rows.first() <= it.bottom }) continue
            val edges = edges(image, grid.rows) ?: continue
            accepted.add(RasterTabStaff(grid.rows, edges))
        }
        return accepted.sortedBy { it.top }
    }

    private fun edges(image: PdfRasterImage, grid: List<Int>): List<Int>? {
        val top = grid.first()
        val bottom = grid.last()
        val gap = (bottom - top) / 5.0
        val offLine = (top + 2 until bottom - 1).filter { y -> grid.none { abs(y - it) <= 1 } }
        if (offLine.size < 15) return null
        val thirds = offLine.chunked((offLine.size + 2) / 3)
        val darkness = DoubleArray(image.width) { x ->
            if (thirds.any { band -> band.sumOf { image[x, it] } < 255 * band.size * 0.21 }) 0.0
            else offLine.sumOf { image[x, it] } / (255.0 * offLine.size)
        }
        val vertical = peaks(darkness, 0.33, (gap * 0.48).roundToInt().coerceAtLeast(3))
        val minimum = (gap * 0.9).roundToInt().coerceAtLeast(8)
        val support = BooleanArray(image.width) { x -> grid.sumOf { y -> maxOf(image[x, y - 1], image[x, y], image[x, y + 1]) } > 250 }
        val staffStart = (0..image.width - minimum).firstOrNull { x ->
            (x until x + minimum).count { support[it] } >= minimum * 0.87
        } ?: return null
        val staffEnd = (image.width - 1 downTo minimum).firstOrNull { x ->
            (x - minimum + 1..x).count { support[it] } >= minimum * 0.87
        } ?: return null
        val left = vertical.minByOrNull { abs(it - staffStart) }?.takeIf { abs(it - staffStart) < gap } ?: staffStart
        val right = vertical.minByOrNull { abs(it - staffEnd) }?.takeIf { abs(it - staffEnd) < gap } ?: staffEnd
        if (right - left < image.width * 0.26) return null
        return listOf(left) + vertical.filter { it >= left + gap * 3.3 && it < right - gap * 0.55 } + right
    }

    fun peaks(values: DoubleArray, threshold: Double, separation: Int): List<Int> {
        val result = ArrayList<Int>()
        var x = 0
        while (x < values.size) {
            if (values[x] < threshold) { x++; continue }
            var best = x
            while (++x < values.size && values[x] >= threshold) if (values[x] > values[best]) best = x
            val last = result.lastOrNull()
            if (last != null && best - last < separation) {
                if (values[best] > values[last]) result[result.lastIndex] = best
            } else result.add(best)
        }
        return result
    }
}
