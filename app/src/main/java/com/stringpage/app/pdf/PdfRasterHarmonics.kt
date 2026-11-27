package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.roundToInt

internal object PdfRasterHarmonics {
    data class Mark(val x: Double, val end: Double, val kind: PdfHarmonicKind) {
        fun covers(onset: Double, gap: Double) = onset in x - gap * 0.54..end
    }

    fun labels(image: PdfRasterImage, staff: RasterTabStaff, glyphs: PdfRasterGlyphs): List<Mark> {
        val gap = staff.gap
        val zone = image.filter(RasterBox(staff.edges.first(), (staff.top - 4.9 * gap).roundToInt(),
            staff.edges.last(), (staff.top - 0.23 * gap).roundToInt()), 80)
        val padding = (gap * 0.11).roundToInt().coerceAtLeast(1)
        val found = ArrayList<Pair<RasterBox, Mark>>()
        for (first in zone.components(95).sortedBy { it.left }) {
            if (first.height / gap !in 0.53..0.98 || first.width / gap !in 0.42..1.05 ||
                found.any { (box, _) -> first.left in box.left until box.right }) continue
            val area = RasterBox(first.left, first.top - padding, first.left + (gap * 3.7).roundToInt(),
                first.bottom + padding)
            val band = zone.crop(area) ?: continue
            val columns = band.columns((gap * 0.045).roundToInt().coerceAtLeast(1), 140)
            val firstColumn = columns.firstOrNull() ?: continue
            if (abs(firstColumn.left - first.left) > padding) continue
            val seen = columns.take(5).mapNotNull { end ->
                val patch = band.crop(band.box.copy(right = end.right)) ?: return@mapNotNull null
                val ink = patch.bounds(105) ?: return@mapNotNull null
                if (ink.width / gap !in 1.08..3.58 || ink.height / gap !in 0.55..1.02) return@mapNotNull null
                val match = glyphs.match(RasterAlphabet.HARMONIC_LABEL, patch, ink.height / gap) ?: return@mapNotNull null
                val kind = PdfHarmonicKind.fromCode(match.value) ?: return@mapNotNull null
                val confident = match.distance <= 0.52 && match.separation >= 0.095 ||
                    match.distance <= 0.56 && match.separation >= 0.13
                if (!confident) return@mapNotNull null
                match.distance to (ink to kind)
            }.minByOrNull { it.first }?.second ?: continue
            val (ink, kind) = seen
            val directEnd = ink.midX + gap * 0.54
            val end = dashEnd(image, ink, staff.edges.last(), gap)?.let { maxOf(directEnd, it + gap * 0.16) } ?: directEnd
            found.add(ink to Mark(ink.midX, end, kind))
        }
        return found.map { it.second }
    }

    private fun dashEnd(image: PdfRasterImage, label: RasterBox, right: Int, gap: Double): Int? {
        val initial = label.right + (gap * 0.08).roundToInt()
        var furthest = label.right
        for (y in label.top until label.bottom) {
            var x = initial
            var last = label.right
            var count = 0
            while (x < right) {
                val limit = if (count == 0) 0.64 * gap else 0.52 * gap
                while (x < right && image[x, y] < 90 && x - last <= limit) x++
                if (x == right || x - last > limit || count > 0 && x - last < gap * 0.13) break
                val begin = x
                while (x < right && image[x, y] >= 90) x++
                if ((x - begin) / gap !in 0.15..0.53) break
                count++
                last = x
            }
            if (count >= 2) furthest = maxOf(furthest, last)
        }
        return furthest.takeIf { it > label.right }
    }

    fun node(patch: RasterPatch, x: Double, row: Int, gap: Double, glyphs: PdfRasterGlyphs): Double? {
        val pieces = patch.columns(0).mapNotNull { column -> patch.crop(column)?.bounds(105) }
            .filter { it.height >= gap * 0.075 }
        val openingIndex = pieces.indexOfFirst {
            (it.left - x) / gap in 0.44..1.16 && angle(patch, it, row, gap, opening = true)
        }
        if (openingIndex < 0) return null
        val opening = pieces[openingIndex]
        val closing = pieces.drop(openingIndex + 2).take(5).firstOrNull { candidate ->
            candidate.left - opening.right < gap * 2.05 && angle(patch, candidate, row, gap, opening = false)
        } ?: return null
        val middle = pieces.filter { it.left in opening.right until closing.left }
        if (middle.size !in 1..5) return null
        val digits = StringBuilder()
        for (box in middle) {
            if (box.height / gap in 0.08..0.23 && box.width / gap in 0.07..0.24 && (box.midY - row) / gap in 0.08..0.32) {
                digits.append('.')
                continue
            }
            if (box.height / gap !in 0.42..0.73 || box.width / gap !in 0.10..0.58 ||
                abs(box.midY - row) > gap * 0.14) return null
            val digit = patch.crop(box) ?: return null
            val match = glyphs.match(RasterAlphabet.HARMONIC_DIGIT, digit, box.height / gap) ?: return null
            val value = when {
                match.value in listOf("0", "6") && centeredHole(digit) -> "0"
                match.distance <= 0.51 && match.separation >= 0.048 -> match.value
                match.value == "8" && match.distance <= 0.58 && match.separation >= 0.025 && holes(digit, 90).size == 2 -> "8"
                else -> return null
            }
            digits.append(value)
        }
        return digits.toString().toDoubleOrNull()?.takeIf { it in 1.0..72.0 }
    }

    private fun holes(patch: RasterPatch, threshold: Int): List<RasterBox> = patch.components(threshold, foreground = false).filter {
        it.left > patch.box.left && it.right < patch.box.right && it.top > patch.box.top && it.bottom < patch.box.bottom &&
            it.width >= patch.box.width * 0.16 && it.height >= patch.box.height * 0.12
    }

    private fun centeredHole(patch: RasterPatch): Boolean = listOf(90, 125).all { threshold ->
        val hole = holes(patch, threshold).singleOrNull() ?: return@all false
        (hole.midY - patch.box.top + 0.5) / patch.box.height in 0.39..0.58 &&
            hole.height.toDouble() / patch.box.height in 0.44..0.83
    }

    private fun angle(patch: RasterPatch, ink: RasterBox, row: Int, gap: Double, opening: Boolean): Boolean {
        if (ink.width / gap !in 0.16..0.49 || ink.height / gap !in 0.23..0.51 || abs(ink.midY - row) > gap * 0.14) return false
        val local = patch.crop(ink)?.pixels ?: return false
        val band = (local.height * 0.25).roundToInt().coerceAtLeast(1)
        fun center(rows: IntRange): Double =
            rows.flatMap { y -> (0 until local.width).filter { x -> local[x, y] >= 105 } }.average()
        val tip = center((local.height - band) / 2 until (local.height + band + 1) / 2)
        val top = center(0 until band)
        val bottom = center(local.height - band until local.height)
        val direction = if (opening) 1 else -1
        return (top - tip) * direction >= gap * 0.065 && (bottom - tip) * direction >= gap * 0.065
    }
}
