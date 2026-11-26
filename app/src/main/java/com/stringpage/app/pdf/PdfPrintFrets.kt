package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Read the independent digit outlines in a web print, even when nearby ink joins them in the raster. */
internal class PdfPrintFrets(private val image: PdfRasterImage, private val gap: Double, private val outlines: List<PdfBox>,
                            private val glyphs: PdfRasterGlyphs) {
    data class Fingering(val fret: Int, val parenthesized: Boolean)

    fun hasDigitNear(x: Double, y: Double): Boolean = outlines.any {
        isDigitBox(it) && abs((it.left + it.right) / 2 - x) <= gap * 0.68 &&
            abs((it.top + it.bottom) / 2 - y) < gap * 0.22
    }

    fun at(x: Double, y: Double): Fingering? {
        val nearby = outlines.filter { abs((it.top + it.bottom) / 2 - y) < gap * 0.22 &&
            abs((it.left + it.right) / 2 - x) < gap * 1.12 }.distinct()
        val digits = nearby.filter {
            isDigitBox(it) &&
                abs((it.left + it.right) / 2 - x) < gap * 0.75
        }.filterNot { box -> nearby.any { other -> other != box && other.contains(box) } }
            .distinct().sortedBy { it.left }
        if (digits.size !in 1..2) return null
        val left = digits.first().left
        val right = digits.last().right
        if (abs((left + right) / 2 - x) > gap * 0.32) return null
        if (digits.size == 2 && (digits[1].left - digits[0].right) / gap !in 0.02..0.38) return null
        var fret = 0
        for (digit in digits) {
            val pad = gap * 0.05
            val box = RasterBox(floor(digit.left - pad).toInt(), floor(digit.top - pad).toInt(),
                ceil(digit.right + pad).toInt(), ceil(digit.bottom + pad).toInt())
            val patch = image.filter(box)
            val match = glyphs.match(RasterAlphabet.PRINT_DIGIT, patch, digit.height / gap) ?: return null
            val value = match.value.toIntOrNull() ?: return null
            if (match.distance > 0.44 || match.separation < 0.045 || (digits.size == 2 && digit == digits.first() && value == 0)) return null
            fret = fret * 10 + value
        }
        if (fret !in 0..36) return null
        val brackets = nearby.filter { it.height / gap in 0.96..1.35 && it.width / gap in 0.16..0.43 }
        val open = brackets.singleOrNull { (left - it.right) / gap in 0.0..0.34 }
        val close = brackets.singleOrNull { (it.left - right) / gap in 0.0..0.34 }
        if ((open == null) != (close == null)) return null
        return Fingering(fret, open != null)
    }

    private fun isDigitBox(box: PdfBox) = box.height / gap in 0.59..1.02 && box.width / gap in 0.22..0.77

    private val PdfBox.width get() = right - left
    private val PdfBox.height get() = bottom - top

    private fun PdfBox.contains(other: PdfBox) =
        left <= other.left && right >= other.right && top <= other.top && bottom >= other.bottom
}
