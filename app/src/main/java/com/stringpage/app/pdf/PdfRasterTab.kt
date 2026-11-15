package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reads the printed TAB directly from ink. Vector PDF objects are not required. */
internal class PdfRasterTab(private val glyphs: PdfRasterGlyphs) {
    private data class Stem(val x: Int, val start: Int, val end: Int)
    private data class Onset(val x: Double, val beat: PdfTabBeat)
    internal data class MeterResult(val meter: PdfMeter, val needsConfirmation: Boolean)
    private val fretToken = Regex("""\d{1,2}|X|<\d{1,2}(?:\.\d{1,2})?>|\(\d{1,2}\)""")

    fun recognize(source: PdfRasterImage, page: Int, start: Int, inherited: MeterResult? = null): Pair<PdfTabResult, MeterResult> {
        val straight = source.deskew(source.detectSlope())
        val systems = PdfRasterLayout.staves(straight)
        pdfRequire(systems.isNotEmpty(), "这一页没有找到成组的六根弦线。请选用清晰、平直的六线谱扫描件。")
        val strips = systems.map { Strip(straight, it) }
        val meter = inherited ?: readMeter(strips.first())?.let { MeterResult(it, false) } ?: MeterResult(PdfMeter(4, 4), true)
        val bars = ArrayList<PdfTabBar>()
        for (strip in strips) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val mutes = palmMutes(strip, strip.staff.edges.first(), strip.staff.edges.last())
            for ((index, pair) in strip.staff.edges.zipWithNext().withIndex()) {
                val (left, right) = pair
                val hasSignature = page == 1 && bars.isEmpty() && !meter.needsConfirmation
                bars.add(readBar(strip, left, right, index == 0, hasSignature, start + bars.size, page, meter.meter, mutes))
            }
        }
        pdfRequire(bars.isNotEmpty(), "扫描页上没有可识别的小节。")
        val track = PdfTabTrack("扫描吉他谱", 6, null, bars)
        return PdfTabResult(null, listOf(track), source.width.toDouble(), true, meter.needsConfirmation) to meter
    }

    private fun readMeter(strip: Strip): PdfMeter? {
        val staff = strip.staff
        val gap = staff.gap
        val left = staff.edges.first()
        val digits = ArrayList<Int>()
        for (baseline in listOf(1.83, 3.17)) {
            val y = staff.top + baseline * gap
            val region = RasterBox((left + gap * 1.75).roundToInt(), (y - gap * 0.67).roundToInt(),
                (left + gap * 3.64).roundToInt(), (y + gap * 0.67).roundToInt() + 1)
            val patch = strip.cleaned(region)
            val bounds = patch.bounds() ?: return null
            val match = glyphs.match(RasterAlphabet.METER, patch, bounds.height / gap) ?: return null
            if (match.distance > 0.49 || match.separation < 0.045) return null
            digits.add(match.value.toInt())
        }
        return if (digits[0] in 1..9 && digits[1] in listOf(1, 2, 4, 8)) PdfMeter(digits[0], digits[1]) else null
    }

    private fun readBar(
        strip: Strip, left: Int, right: Int, startsSystem: Boolean, hasSignature: Boolean, number: Int, page: Int,
        meter: PdfMeter, mutes: List<ClosedFloatingPointRange<Double>>
    ): PdfTabBar {
        val g = strip.staff.gap
        val innerLeft = left + (g * when { hasSignature -> 3.70; startsSystem -> 2.55; else -> 0.20 }).roundToInt()
        val innerRight = right - (g * 0.18).roundToInt()
        val bounds = PdfBox(left.toDouble(), (strip.staff.top - g * 4).coerceAtLeast(0.0), right.toDouble(), strip.staff.bottom + g * 4)
        val issues = linkedSetOf<String>()
        val review = linkedSetOf<String>()
        val onsets = ArrayList<Onset>()
        if (innerRight - innerLeft < g * 1.4) return PdfTabBar(number, page, bounds, emptyList(), listOf("小节宽度不足，无法确认扫描内容"), emptyList(), meter)
        val low = (strip.staff.top - 0.58 * g).roundToInt()
        val high = (strip.staff.bottom + 0.58 * g).roundToInt() + 1
        val field = strip.cleaned(RasterBox(innerLeft, low, innerRight, high))
        val stems = stems(strip.image, strip.staff, innerLeft, innerRight)
        val used = HashSet<Stem>()
        val groups = field.columns((g * 0.32).roundToInt().coerceAtLeast(2))
        for (group in groups) {
            val patch = field.crop(group) ?: continue
            val box = patch.bounds() ?: continue
            val outsideStrings = box.bottom < strip.staff.top - g * 0.10
            val boundaryStroke = box.height > 4.2 * g && box.width < 0.67 * g &&
                (box.left - left < g * 0.72 || right - box.right < g * 0.72)
            if (outsideStrings || boundaryStroke || box.height < 0.22 * g || box.width < 0.08 * g) continue
            val inGroup = stems.filter { it.x.toDouble() in box.left - g * 0.17..box.right + g * 0.17 }
            if (inGroup.size > 1) {
                // A fast passage can put adjacent fret numbers in a single column group.
                for ((index, stem) in inGroup.withIndex()) {
                    val a = if (index == 0) box.left else (inGroup[index - 1].x + stem.x) / 2
                    val b = if (index == inGroup.lastIndex) box.right else (inGroup[index + 1].x + stem.x) / 2
                    val subsection = field.crop(RasterBox(a, box.top, b, box.bottom)) ?: continue
                    val note = fingeredBeat(strip, subsection, stem, mutes, issues, review)
                    if (note != null) { onsets.add(note); used.add(stem) }
                }
            } else if (inGroup.size == 1) {
                val stem = inGroup.single()
                val beat = fingeredBeat(strip, patch, stem, mutes, issues, review)
                if (beat != null) { onsets.add(beat); used.add(stem) }
            } else {
                val rest = readRest(strip.staff, patch)
                if (rest != null) onsets.add(Onset(box.midX, PdfTabBeat(rest, emptyList())))
                else if (isOval(strip, box)) {
                    val beat = fingeredBeat(strip, patch, null, mutes, issues, review)
                    if (beat != null) onsets.add(beat)
                } else issues.add("扫描图中有未识别的音符或休止符")
            }
        }
        if (stems.any { it !in used }) issues.add("存在无法配对的符干")
        val sorted = onsets.sortedBy { it.x }
        val beats = applyTupletsAndDots(strip, sorted, left, right, issues)
        if (!pdfBeatsFit(beats, meter)) issues.add("识别出的节奏未填满 $meter 小节")
        return PdfTabBar(number, page, bounds, beats, issues.toList(), review.toList(), meter)
    }

    private fun fingeredBeat(
        strip: Strip, patch: RasterPatch, stem: Stem?, mutes: List<ClosedFloatingPointRange<Double>>,
        issues: MutableSet<String>, review: MutableSet<String>
    ): Onset? {
        val staff = strip.staff
        val g = staff.gap
        val box = patch.bounds() ?: return null
        val oval = isOval(strip, box)
        val x = stem?.x?.toDouble() ?: box.midX
        val notes = ArrayList<PdfTabNote>()
        for ((index, line) in staff.rows.withIndex()) {
            val y = line - g * 0.02
            val area = RasterBox(box.left, (y - g * 0.48).roundToInt(), box.right, (y + g * 0.49).roundToInt() + 1)
            val outside = if (oval) strip.cleaned(area) { px, py -> isOvalBoundary(box, px, py, g * 0.10) }
                else patch.crop(area) ?: continue
            val segments = outside.columns((g * 0.075).roundToInt().coerceAtLeast(1))
            val candidates = outside.columns((g * 0.24).roundToInt().coerceAtLeast(2)).mapNotNull { cut ->
                outside.crop(cut)?.let { part -> part.bounds()?.let { it to part } }
            }.filter { (ink, _) -> abs(ink.midX - x) <= g * 0.62 && ink.height / g in 0.43..0.99 }
            val options = if (candidates.isNotEmpty()) candidates else segments.mapNotNull { cut ->
                outside.crop(cut)?.let { part -> part.bounds()?.let { it to part } }
            }.filter { (ink, _) -> abs(ink.midX - x) <= g * 0.43 && ink.height / g in 0.43..0.99 }
            val (ink, suitable) = options.minByOrNull { abs(it.first.midX - x) } ?: continue
            val matched = glyphs.match(RasterAlphabet.FRET, suitable, ink.height / g)
            val recognized = when {
                matched != null && matched.distance <= 0.58 && matched.separation >= 0.025 && fretToken.matches(matched.value) -> matched.value
                isCross(suitable) -> "X"
                else -> null
            }
            if (recognized == null) {
                issues.add("第 ${index + 1} 弦品位需确认")
                continue
            }
            val token = recognized
            val fretNumber = token.trim('(', ')', '<', '>').toDoubleOrNull() ?: 0.0
            val fret = fretNumber.toInt()
            val harmonic = fretNumber.takeIf { token.startsWith('<') }
            val note = PdfTabNote(staff.rows.size - index, fret, dead = token == "X", palmMute = mutes.any { x in it },
                harmonic = harmonic, ghost = token.startsWith('('))
            notes.add(note)
            if (token.startsWith('(')) review.add("括号音需要核对延音或轻触奏法")
        }
        if (notes.isEmpty()) {
            issues.add("扫描图中有符干但品位无法读出")
            return null
        }
        val duration = when {
            stem == null -> 1
            oval || stem.end - stem.start < g * 1.18 -> 2
            else -> 4 shl flagOrBeam(strip.image, staff, stem)
        }
        return Onset(x, PdfTabBeat(duration, notes.sortedBy { it.string }))
    }

    private fun isCross(patch: RasterPatch): Boolean {
        val shape = patch.shape() ?: return false
        if (shape.aspect !in 0.60..1.20) return false
        val w = RasterShape.COLUMNS
        val h = RasterShape.ROWS
        fun strength(x: Double, y: Int): Float {
            val mid = (x * (w - 1)).roundToInt()
            return (maxOf(0, mid - 2)..minOf(w - 1, mid + 2)).maxOf { shape.samples[y * w + it] }
        }
        val rows = listOf(2, 5, 7, 11, 13, 16)
        val covered = rows.count { y ->
            val position = 0.11 + 0.78 * y / (h - 1)
            strength(position, y) > 0.50f && strength(1 - position, y) > 0.50f
        }
        return covered == rows.size && strength(0.5, h / 2) > 0.66f && strength(0.0, h / 2) < 0.35f && strength(1.0, h / 2) < 0.35f
    }

    private fun stems(image: PdfRasterImage, staff: RasterTabStaff, left: Int, right: Int): List<Stem> {
        val gap = staff.gap
        val low = staff.bottom + (gap * 0.32).roundToInt()
        val high = (staff.bottom + gap * 4.15).roundToInt().coerceAtMost(image.height - 1)
        val strokes = HashMap<Int, Stem>()
        val confidence = DoubleArray(right - left)
        for (x in left until right) {
            var start = -1
            var end = -1
            var bestScore = 0.0
            fun accept(a: Int, b: Int) {
                if (a < 0 || b - a + 1 < gap * 0.76 || a > staff.bottom + gap * 1.60 || b < staff.bottom + gap * 0.96) return
                val merit = (a..b).sumOf { image[x, it] } / 255.0
                if (merit > bestScore) { strokes[x] = Stem(x, a, b); bestScore = merit }
            }
            for (y in low..high) {
                if (image[x, y] >= 58) {
                    if (start < 0) start = y
                    end = y
                } else if (start >= 0 && y - end > 1) { accept(start, end); start = -1 }
            }
            accept(start, end)
            confidence[x - left] = bestScore
        }
        return PdfRasterLayout.peaks(confidence, gap * 0.43, (gap * 0.26).roundToInt())
            .mapNotNull { strokes[it + left] }
    }

    private fun readRest(staff: RasterTabStaff, patch: RasterPatch): Int? {
        val box = patch.bounds() ?: return null
        val g = staff.gap
        val center = staff.rows[2]
        if (box.midY !in staff.top + g * 0.5..center + g * 1.12 || box.height > g * 2.95) return null
        if (box.width / g in 0.45..1.13 && box.height / g in 0.12..0.56 && box.width.toDouble() / box.height > 1.35) {
            val pad = patch.crop(box)?.pixels ?: return null
            val filled = (0 until pad.height).sumOf { y -> (0 until pad.width).count { x -> pad[x, y] > 105 } }
            if (filled >= pad.width * pad.height * 0.60) {
                val near = staff.rows.minBy { abs(it - box.midY) }
                return if (box.midY >= near) 1 else 2
            }
        }
        val pitch = glyphs.match(RasterAlphabet.REST, patch, box.height / g) ?: return null
        if (pitch.distance > 0.54) return null
        return if (pitch.value == "1" || pitch.value == "2") {
            if (box.midY >= staff.rows.minBy { abs(it - box.midY) }) 1 else 2
        } else pitch.value.toIntOrNull()?.takeIf { pitch.separation > 0.035 }
    }

    private fun isOval(strip: Strip, box: RasterBox): Boolean {
        val g = strip.staff.gap
        if (box.height < g * 0.87 || box.width / g !in 0.72..3.5 || box.width / box.height.toDouble() > 1.6) return false
        var count = 0
        for (i in 0 until 40) {
            val theta = i * Math.PI / 20
            val x = (box.midX + (box.width - 1) * cos(theta) / 2).roundToInt()
            val y = (box.midY + (box.height - 1) * sin(theta) / 2).roundToInt()
            if (maxOf(strip.image[x, y], strip.image[x - 1, y], strip.image[x + 1, y], strip.image[x, y - 1], strip.image[x, y + 1]) > 80) count++
        }
        return count >= 33
    }

    private fun isOvalBoundary(box: RasterBox, x: Int, y: Int, padding: Double): Boolean {
        val a = (box.width - 1) / 2.0
        val b = (box.height - 1) / 2.0
        val dx = (x - box.midX) / a
        val dy = (y - box.midY) / b
        return abs(sqrt(dx * dx + dy * dy) - 1) * minOf(a, b) <= padding
    }

    private fun flagOrBeam(image: PdfRasterImage, staff: RasterTabStaff, stem: Stem): Int {
        val g = staff.gap
        val near = (stem.end - 2.15 * g).roundToInt()..(stem.end + g * 0.24).roundToInt()
        val step = (g * 0.16).roundToInt().coerceAtLeast(2)
        val distance = (g * 0.68).roundToInt().coerceAtLeast(step + 4)
        fun stripe(y: Int, direction: Int) = (step..distance).sumOf { image[stem.x + direction * it, y] } / (255.0 * (distance - step + 1))
        val strengths = near.map { y -> maxOf(stripe(y, -1), stripe(y, 1)) }.toDoubleArray()
        val bands = PdfRasterLayout.peaks(strengths, 0.53, (g * 0.23).roundToInt())
        if (bands.isNotEmpty() && bands.any { abs(near.first + it - stem.end) < g * 0.38 }) return bands.size.coerceAtMost(4)
        val x = stem.x + (g * 0.15).roundToInt()
        val patch = image.filter(RasterBox(x, (stem.end - g * 2.50).roundToInt(), (stem.x + g * 0.92).roundToInt(),
            (stem.end + g * 0.34).roundToInt() + 1))
        val box = patch.bounds() ?: return 0
        val match = glyphs.match(RasterAlphabet.FLAG, patch, box.height / g) ?: return 0
        return match.value.toInt().takeIf { match.distance < 0.57 && match.separation > 0.025 } ?: 0
    }

    private fun applyTupletsAndDots(strip: Strip, onsets: List<Onset>, left: Int, right: Int, issues: MutableSet<String>): List<PdfTabBeat> {
        if (onsets.isEmpty()) return emptyList()
        // The numeral is printed below the stem group. Require a consistent group of three onsets.
        val g = strip.staff.gap
        val bottom = strip.staff.bottom
        val low = (bottom + 1.35 * g).roundToInt()
        val high = (bottom + 4.0 * g).roundToInt()
        val triplets = HashSet<Int>()
        val bracket = strip.image.filter(RasterBox(left, (bottom + 3.08 * g).roundToInt(), right, (bottom + 5.75 * g).roundToInt()), 90)
        for (group in bracket.columns((g * 0.07).roundToInt())) {
            val patch = bracket.crop(group) ?: continue
            val box = patch.bounds() ?: continue
            if (box.height / g !in 0.62..1.27 || box.width / g !in 0.33..1.04) continue
            val match = glyphs.match(RasterAlphabet.TUPLET, patch, box.height / g) ?: continue
            if (match.distance > 0.37) continue
            val middle = onsets.indices.minBy { abs(onsets[it].x - box.midX) }
            if (middle !in 1 until onsets.lastIndex || abs(onsets[middle].x - box.midX) > g * 0.75) continue
            val groupBeats = onsets.subList(middle - 1, middle + 2)
            if (groupBeats.map { it.beat.denominator }.distinct().size == 1 &&
                groupBeats.zipWithNext().all { (a, b) -> b.x - a.x in g * 0.55..g * 5.5 }) triplets.addAll(middle - 1..middle + 1)
        }
        return onsets.mapIndexed { index, event ->
            val boundary = onsets.getOrNull(index + 1)?.x ?: right.toDouble()
            val start = (event.x + g * 0.18).roundToInt().coerceAtLeast(left)
            val end = minOf((event.x + g * 0.88).roundToInt(), (boundary - g * 0.4).roundToInt())
            if (end <= start || event.beat.notes.isEmpty()) return@mapIndexed event.beat.copy(triplet = index in triplets)
            val area = RasterBox(start, low, end, high)
            val points = strip.image.filter(area, 110)
            val dots = points.columns(0).mapNotNull { columns -> points.crop(columns)?.bounds() }
                .filter { it.height / g in 0.10..0.36 && it.width / g in 0.10..0.36 &&
                    abs(it.width - it.height) < g * 0.17 &&
                    strip.image[(it.midX).roundToInt(), (it.midY).roundToInt()] > 120 }
            if (dots.size > 2) issues.add("附点数量待校对")
            event.beat.copy(dots = dots.size.coerceAtMost(2), triplet = index in triplets)
        }
    }

    private fun palmMutes(strip: Strip, left: Int, right: Int): List<ClosedFloatingPointRange<Double>> {
        val g = strip.staff.gap
        val from = (strip.staff.top - g * 4.4).roundToInt().coerceAtLeast(0)
        val to = (strip.staff.top - g * 0.3).roundToInt()
        if (from >= to) return emptyList()
        val zone = strip.image.filter(RasterBox(left, from, right, to))
        val result = ArrayList<ClosedFloatingPointRange<Double>>()
        val pieces = zone.columns((g * 0.065).roundToInt().coerceAtLeast(1), 150)
        var next = left
        for ((i, piece) in pieces.withIndex()) {
            if (piece.left < next) continue
            val candidate = pieces.subList(i, minOf(i + 6, pieces.size)).mapNotNull { end ->
                val aperture = RasterBox(piece.left, zone.box.top, end.right, zone.box.bottom)
                if (aperture.width / g !in 1.65..3.40) return@mapNotNull null
                val patch = zone.crop(aperture) ?: return@mapNotNull null
                val box = patch.bounds() ?: return@mapNotNull null
                if (box.height / g !in 0.51..1.05) return@mapNotNull null
                val match = glyphs.match(RasterAlphabet.TECHNIQUE, patch, box.height / g) ?: return@mapNotNull null
                if (match.value != "P.M." || match.distance > 0.51) return@mapNotNull null
                match.distance to patch
            }.minByOrNull { it.first } ?: continue
            val patch = candidate.second
            val box = patch.bounds() ?: continue
            next = box.right
            val middle = box.midY.roundToInt()
            val at = ((box.right + g * 0.16).roundToInt()).coerceAtMost(right)
            val row = (middle - 3..middle + 3).maxBy { y -> (at until minOf(at + (g * 4).roundToInt(), right)).sumOf { strip.image[it, y] } }
            var last = box.right
            for (x in at until right) {
                if (strip.image[x, row] > 72) last = x
                if (x - last > g * 0.72) break
            }
            result.add((box.midX - g / 2)..maxOf(box.midX + g / 2, last + g * 0.32))
        }
        return result
    }


    private class Strip(val image: PdfRasterImage, val staff: RasterTabStaff) {
        private val longLines = HashMap<Int, List<IntRange>>()
        init {
            val minRun = ceil(staff.gap * 1.48).toInt()
            val halfStroke = ceil(staff.gap * 0.085).toInt().coerceAtLeast(2)
            for (y in staff.rows.flatMap { row -> (row - halfStroke..row + halfStroke).toList() }.distinct()) {
                val runs = ArrayList<IntRange>()
                var start = -1
                var last = -1
                for (x in staff.edges.first() until staff.edges.last()) {
                    if (image[x, y] > 27) { if (start < 0) start = x; last = x }
                    else if (start >= 0 && x - last > 1) {
                        if (last - start >= minRun) runs.add(start..last)
                        start = -1
                    }
                }
                if (start >= 0 && last - start >= minRun) runs.add(start..last)
                if (runs.isNotEmpty()) longLines[y] = runs
            }
        }

        fun cleaned(box: RasterBox, omit: (Int, Int) -> Boolean = { _, _ -> false }) = image.filter(box) { x, y ->
            longLines[y]?.any { x in it } == true || omit(x, y)
        }
    }
}
