package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.roundToInt

/** A scanned upward bend needs an amount, an arrowhead, and a trace back to its fret. */
internal object PdfRasterBends {
    private val amounts = mapOf("¼" to 1, "½" to 2, "¾" to 3, "full" to 4, "1½" to 6, "2" to 8, "2½" to 10, "3" to 12)

    data class Reading(val bend: PdfBend?)

    fun upward(image: PdfRasterImage, staff: RasterTabStaff, glyphs: PdfRasterGlyphs,
               onset: Double, line: Int, next: Double): Reading? {
        val g = staff.gap
        val left = (onset + g * 0.78).roundToInt()
        val right = minOf((onset + g * 2.52).roundToInt(), (next - g * 0.20).roundToInt())
        if (right - left < g * 0.40) return null
        val area = RasterBox(left, (staff.top - g * 4.15).roundToInt(), right, (staff.top - g * 0.12).roundToInt())
        val marks = image.filter(area, 92).components(105)
        val readings = marks.mapNotNull { arrow ->
            if (arrow.width / g !in 0.22..0.69 || arrow.height / g !in 0.72..3.9 ||
                (staff.top - arrow.top) / g !in 0.78..4.05) return@mapNotNull null
            val head = readArrowhead(image, arrow, g) ?: return@mapNotNull null
            val amount = readAmount(image, glyphs, head, arrow.top, g)
            val reachesFret = reachesFret(image, staff, head, arrow.top, onset, line)
            if (amount == null && !reachesFret) return@mapNotNull null
            Reading(amount?.takeIf { reachesFret }?.let(PdfBend::up))
        }
        return if (readings.isEmpty()) null else readings.singleOrNull() ?: Reading(null)
    }

    private fun readArrowhead(image: PdfRasterImage, arrow: RasterBox, g: Double): Int? {
        fun columns(y: Int) = (arrow.left until arrow.right).filter { x -> image[x, y] >= 105 }
        val wide = (arrow.top..arrow.top + (g * 0.48).roundToInt()).map(::columns).maxBy { it.size }
        if (wide.size / g !in 0.24..0.61 || columns(arrow.top).size > wide.size * 0.65) return null
        val sample = (arrow.top + g * 0.57).roundToInt()..(arrow.top + g * 0.89).roundToInt()
        val shaft = sample.mapNotNull { y ->
            val ink = columns(y)
            ink.takeIf { it.isNotEmpty() && it.last() - it.first() + 1 <= g * 0.25 }?.average()
        }
        if (shaft.size < sample.count() * 0.78) return null
        val x = shaft.average().roundToInt()
        return x.takeIf { it - wide.first() >= g * 0.07 && wide.last() - it >= g * 0.07 }
    }

    private fun readAmount(image: PdfRasterImage, glyphs: PdfRasterGlyphs, center: Int, tip: Int, g: Double): Int? {
        val area = RasterBox((center - g * 0.98).roundToInt(), (tip - g * 1.46).roundToInt(),
            (center + g * 0.98).roundToInt(), (tip - g * 0.17).roundToInt())
        val patch = image.filter(area)
        val shape = patch.bounds(105) ?: return null
        if (shape.height / g !in 0.39..0.88 || shape.width / g !in 0.19..1.8 ||
            abs(shape.midX - center) > g * 0.30 || (tip - shape.bottom) / g !in 0.22..0.88) return null
        val match = glyphs.match(RasterAlphabet.BEND_LABEL, patch, shape.height / g) ?: return null
        return amounts[match.value]?.takeIf {
            match.distance <= 0.49 && match.separation >= 0.065 ||
                match.distance <= 0.57 && match.separation >= 0.16
        }
    }

    private fun reachesFret(image: PdfRasterImage, staff: RasterTabStaff, head: Int, tip: Int,
                            onset: Double, line: Int): Boolean {
        val g = staff.gap
        var x = head
        var missed = 0
        val maximumGap = (g * 0.17).roundToInt().coerceAtLeast(2)
        val leftStep = (g * 0.38).roundToInt().coerceAtLeast(2)
        val rightStep = (g * 0.14).roundToInt().coerceAtLeast(1)
        val first = (tip + g * 0.55).roundToInt()
        val last = (line - g * 0.09).roundToInt()
        for (y in first..last) {
            if (staff.rows.any { abs(y - it) <= g * 0.10 }) continue
            val ink = (x - leftStep..x + rightStep).filter { px -> image[px, y] >= 100 }
            if (ink.isEmpty()) {
                if (++missed > maximumGap) return false
                continue
            }
            val parts = ink.fold(mutableListOf<MutableList<Int>>()) { runs, px ->
                if (runs.isEmpty() || px - runs.last().last() > 1) runs.add(mutableListOf())
                runs.last().add(px)
                runs
            }
            val nearest = parts.minBy { abs(it.average() - x) }
            if (nearest.size > g * 0.47) return false
            x = nearest.average().roundToInt()
            missed = 0
            if (line - y <= g * 0.31 && (x - onset) / g in 0.34..1.04 && head - x >= g * 0.52) return true
        }
        return false
    }
}
