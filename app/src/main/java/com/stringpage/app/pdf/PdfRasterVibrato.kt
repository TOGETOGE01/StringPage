package com.stringpage.app.pdf

import kotlin.math.roundToInt

internal object PdfRasterVibrato {
    data class Mark(val left: Double, val right: Double, val effect: PdfVibrato) {
        fun covers(x: Double) = x in left..right
    }

    fun find(image: PdfRasterImage, staff: RasterTabStaff): List<Mark> {
        val gap = staff.gap
        val above = RasterBox(staff.edges.first(), (staff.top - 4.6 * gap).roundToInt(), staff.edges.last(),
            (staff.top - 0.22 * gap).roundToInt())
        val zone = image.filter(above, 75)
        return zone.components(95).mapNotNull { box ->
            if (box.width < 1.58 * gap || box.top <= above.top || box.bottom >= above.bottom) return@mapNotNull null
            val height = box.height / gap
            val (effect, period) = when (height) {
                in 0.20..0.58 -> PdfVibrato.SLIGHT to 0.54..0.79
                in 0.62..1.09 -> PdfVibrato.WIDE to 0.74..1.08
                else -> return@mapNotNull null
            }
            if (!isPeriodic(image, box, gap, period)) return@mapNotNull null
            Mark(box.left - gap * 0.25, box.right - gap * 0.29, effect)
        }
    }

    private fun isPeriodic(image: PdfRasterImage, box: RasterBox, gap: Double,
                           period: ClosedFloatingPointRange<Double>): Boolean {
        val trace = DoubleArray(box.width)
        for (x in box.left until box.right) {
            var sum = 0
            var weight = 0
            for (y in box.top until box.bottom) {
                val ink = image[x, y]
                if (ink < 75) continue
                sum += ink * (y - box.top)
                weight += ink
            }
            if (weight == 0) return false
            trace[x - box.left] = sum.toDouble() / weight
        }
        val center = trace.average()
        val variance = trace.sumOf { (it - center) * (it - center) } / trace.size
        if (variance < (gap * 0.030) * (gap * 0.030)) return false
        fun difference(shift: Int): Double {
            val count = trace.size - shift
            return (0 until count).sumOf { index ->
                val delta = trace[index] - trace[index + shift]
                delta * delta
            } / count
        }
        // A flat dashed line or a slur cannot reproduce both the full and the half period of a wave.
        return ((period.start * gap).roundToInt().coerceAtLeast(2)..(period.endInclusive * gap).roundToInt()).any { lag ->
            trace.size >= 2.45 * lag && difference(lag) <= 0.57 * variance &&
                difference((lag / 2.0).roundToInt()) >= 1.45 * variance
        }
    }
}
