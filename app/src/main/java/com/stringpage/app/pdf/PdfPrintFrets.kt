package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Read the independent digit outlines in a web print, even when nearby ink joins them in the raster. */
internal class PdfPrintFrets(private val image: PdfRasterImage, private val gap: Double, private val outlines: List<PdfBox>,
                            private val glyphs: PdfRasterGlyphs, private val rasterDigits: Boolean = false,
                            private val digitAlphabet: RasterAlphabet = RasterAlphabet.PRINT_DIGIT) {
    data class Fingering(val fret: Int, val ink: PdfBox, val parenthesized: Boolean)
    private val scoreDigits get() = digitAlphabet == RasterAlphabet.SCORE_DIGIT

    fun centers(): List<Double> {
        val digits = outlines.filter(::isDigitBox).sortedBy { it.left }
        return digits.map { (it.left + it.right) / 2 } + digits.zipWithNext().mapNotNull { (a, b) ->
            ((a.left + b.right) / 2).takeIf { (b.left - a.right) / gap in 0.02..0.38 }
        }
    }

    fun hasDigitNear(x: Double, y: Double): Boolean = outlines.any {
        isDigitBox(it) && abs((it.left + it.right) / 2 - x) <= gap * 0.68 &&
            abs((it.top + it.bottom) / 2 - y) < gap * 0.22
    }

    fun chordCenter(left: Int, right: Int, rows: List<Int>): Double? {
        val digits = outlines.filter { box ->
            box.left >= left - gap * 0.13 && box.right <= right + gap * 0.13 && isDigitBox(box) &&
                rows.any { abs((box.top + box.bottom) / 2 - it) < gap * 0.20 }
        }
        val first = digits.minOfOrNull { it.left } ?: return null
        val last = digits.maxOf { it.right }
        if ((last - first) / gap !in 0.22..1.80) return null
        val x = (first + last) / 2
        return x.takeIf { rows.any { at(x, it.toDouble()) != null } }
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
        val space = if (scoreDigits) 0.02..0.56 else 0.02..0.38
        if (digits.size == 2 && (digits[1].left - digits[0].right) / gap !in space) return null
        val serifPair = scoreDigits && digits.size == 2 &&
            abs((left + right) / 2 - x) <= gap * 0.16 &&
            abs(digits[0].top - digits[1].top) <= gap * 0.10 &&
            abs(digits[0].bottom - digits[1].bottom) <= gap * 0.10 &&
            isScannedSerifOne(digits[0])
        var fret = 0
        for (digit in digits) {
            val pad = gap * 0.05
            var clipLeft = floor(digit.left - pad).toInt()
            var clipRight = ceil(digit.right + pad).toInt()
            if (rasterDigits) {
                // Preserve the fringe of the digit without sampling a neighboring digit or parenthesis.
                clipLeft = nearby.filter { it.right <= digit.left }.maxOfOrNull { it.right.toInt() }
                    ?.let { maxOf(clipLeft, it) } ?: clipLeft
                clipRight = nearby.filter { it.left >= digit.right }.minOfOrNull { it.left.toInt() }
                    ?.let { minOf(clipRight, it) } ?: clipRight
            }
            val padded = RasterBox(clipLeft, floor(digit.top - pad).toInt(), clipRight, ceil(digit.bottom + pad).toInt())
            val tight = digit.rasterBox()
            val patch = image.filter(if (rasterDigits) tight else padded)
            val match = glyphs.match(digitAlphabet, patch, digit.height / gap)
            val matched = confidentDigit(match)
            val value = when {
                scoreDigits && (matched == null || matched == 1) && isScannedSerifOne(digit) -> 1
                rasterDigits && (matched == null || matched == 6) && isScannedZero(patch) -> 0
                rasterDigits && matched == null && match?.value == "9" && match.distance <= 0.38 && isScannedNine(patch) -> 9
                matched != null -> matched
                serifPair && digit == digits.last() && match != null && match.distance <= 0.51 && match.separation >= 0.105 ->
                    match.value.toIntOrNull() ?: return null
                serifPair && digit == digits.last() && match?.value == "5" && match.distance <= 0.55 &&
                    match.separation >= 0.055 && isScannedFive(patch) -> 5
                isZeroOutline(digit, nearby) -> 0
                rasterDigits && match != null -> confidentDigit(glyphs.match(digitAlphabet,
                    image.filter(padded), digit.height / gap))?.takeIf { it.toString() == match.value } ?: return null
                else -> return null
            }
            if (digits.size == 2 && digit == digits.first() && value == 0) return null
            fret = fret * 10 + value
        }
        if (fret !in 0..36) return null
        val brackets = nearby.filter { it.height / gap in 0.96..1.35 && it.width / gap in 0.16..0.43 }
        val open = brackets.singleOrNull { (left - it.right) / gap in 0.0..0.34 }
        val close = brackets.singleOrNull { (it.left - right) / gap in 0.0..0.34 }
        if ((open == null) != (close == null)) return null
        val shapes = digits + listOfNotNull(open, close)
        return Fingering(fret, PdfBox(shapes.minOf { it.left }, shapes.minOf { it.top },
            shapes.maxOf { it.right }, shapes.maxOf { it.bottom }), open != null)
    }

    private fun isScannedSerifOne(digit: PdfBox): Boolean {
        if (digit.width / gap >= 0.29) return false
        val box = digit.rasterBox()
        val fringe = (gap * 0.055).roundToInt().coerceAtLeast(1)
        // The stem of a thin 1 can scan darker than either serif, leaving the serifs outside the digit's hard outline.
        val ink = image.filter(box.copy(left = box.left - fringe, right = box.right + fringe)).bounds(72) ?: return false
        if (ink.width / gap !in 0.10..0.29 || ink.height / gap !in 0.56..0.89 ||
            ink.width.toDouble() / ink.height > 0.43) return false
        fun rowWidth(y: Int) = (ink.left until ink.right).count { x -> image[x, y] >= 72 }
        val middle = (ink.top + ink.height / 3..ink.bottom - 1 - ink.height / 4).map(::rowWidth).average()
        val overhang = (ink.top until ink.top + ink.height / 3).maxOf(::rowWidth) > middle + 0.25 ||
            (ink.bottom - ink.height / 4 until ink.bottom).maxOf(::rowWidth) > middle + 0.25
        if (!overhang) return false
        return (ink.left until ink.right).any { x ->
            (ink.top + 1 until ink.bottom - 1).count { y -> image[x, y] >= 100 } >= (ink.height - 2) * 0.85
        }
    }

    private fun confidentDigit(match: PdfRasterGlyphs.Match?): Int? =
        match?.takeIf { it.distance <= 0.44 && it.separation >= 0.045 ||
            scoreDigits && it.distance <= 0.55 && it.separation >= 0.13 }?.value?.toIntOrNull()

    private fun isZeroOutline(digit: PdfBox, nearby: List<PdfBox>): Boolean {
        if (digit.width / digit.height !in 0.51..0.75 || digit.height / gap !in 0.74..0.98) return false
        val inside = nearby.filter { it != digit && digit.contains(it) }
        val hole = inside.singleOrNull() ?: return false
        if (hole.width / digit.width !in 0.24..0.52 || hole.height / digit.height !in 0.58..0.78 ||
            abs((hole.left + hole.right) - (digit.left + digit.right)) > digit.width * 0.13 ||
            abs((hole.top + hole.bottom) - (digit.top + digit.bottom)) > digit.height * 0.13) return false
        val y = ((hole.top + hole.bottom) / 2).roundToInt()
        val center = ((hole.left + hole.right) / 2).roundToInt()
        val left = ((digit.left + hole.left) / 2).roundToInt()
        val right = ((hole.right + digit.right) / 2).roundToInt()
        return image[center, y] < 80 && image[left, y] >= 105 && image[right, y] >= 105
    }

    private fun isScannedZero(patch: RasterPatch): Boolean {
        val ink = patch.bounds(105) ?: return false
        if (ink.width.toDouble() / ink.height !in 0.50..0.77 || ink.height / gap !in 0.70..1.02) return false
        val x = ink.midX.roundToInt()
        val sides = (ink.width * 0.32).roundToInt().coerceAtLeast(1)
        val hole = (ink.top until ink.bottom).filter { y ->
            image[x, y] < 80 && image[x - sides, y] >= 105 && image[x + sides, y] >= 105
        }
        if (hole.size.toDouble() / ink.height !in 0.53..0.79 || hole.last() - hole.first() + 1 != hole.size) return false
        val middle = ((hole.first() + hole.last()) / 2.0 - ink.top) / ink.height
        return middle in 0.40..0.57 &&
            (ink.top until hole.first()).any { image[x, it] >= 105 } &&
            (hole.last() + 1 until ink.bottom).any { image[x, it] >= 105 }
    }

    private fun isScannedNine(patch: RasterPatch): Boolean {
        val ink = patch.bounds(105) ?: return false
        if (ink.width.toDouble() / ink.height !in 0.51..0.81 || ink.height / gap !in 0.70..1.02) return false
        // One enclosed upper bowl separates 9 from the bottom bowl of 6 and the two bowls of 8.
        return listOf(90, 125).all { threshold ->
            val hole = patch.components(threshold, foreground = false).singleOrNull { box ->
                box.left > patch.box.left && box.right < patch.box.right && box.top > patch.box.top &&
                    box.bottom < patch.box.bottom && box.width >= ink.width * 0.15 && box.height >= ink.height * 0.13
            } ?: return@all false
            (hole.midY - ink.top) / ink.height in 0.20..0.44 && hole.height.toDouble() / ink.height in 0.13..0.44
        }
    }

    private fun isScannedFive(patch: RasterPatch): Boolean {
        val ink = patch.bounds(105) ?: return false
        if (ink.width.toDouble() / ink.height !in 0.44..0.76 || ink.height / gap !in 0.61..0.96) return false
        fun span(from: Double, to: Double) =
            (ink.top + (ink.height * from).roundToInt()) until (ink.top + (ink.height * to).roundToInt())
        val left = ink.left until ink.left + (ink.width * 0.39).roundToInt()
        val right = ink.right - (ink.width * 0.34).roundToInt() until ink.right
        val topBar = span(0.0, 0.14).any { y ->
            (ink.left until ink.right).count { x -> image[x, y] >= 105 } >= ink.width * 0.60
        }
        fun passes(rows: IntRange, stroke: IntRange, empty: IntRange) =
            rows.count { y -> stroke.any { x -> image[x, y] >= 105 } && empty.none { x -> image[x, y] >= 105 } } >= rows.count() * 0.65
        return topBar && passes(span(0.14, 0.39), left, right) && passes(span(0.53, 0.81), right, left)
    }

    private fun isDigitBox(box: PdfBox) =
        box.height / gap in 0.59..1.02 && box.width / gap in (if (scoreDigits) 0.10..0.77 else 0.22..0.77)

    private val PdfBox.width get() = right - left
    private val PdfBox.height get() = bottom - top

    private fun PdfBox.rasterBox() = RasterBox(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    private fun PdfBox.contains(other: PdfBox) =
        left <= other.left && right >= other.right && top <= other.top && bottom >= other.bottom
}
