package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reads the printed TAB directly from ink. Vector PDF objects are not required. */
internal class PdfRasterTab(private val glyphs: PdfRasterGlyphs) {
    private data class Stem(val x: Int, val start: Int, val end: Int)
    private data class Onset(val x: Double, val beat: PdfTabBeat)
    private data class DottedRest(val onset: Onset, val ink: List<RasterBox>)
    private data class FretInk(val x: Double, val row: Int, val bounds: RasterBox)
    private data class BarReading(val bar: PdfTabBar, val outgoingTies: Set<Pair<Int, Int>> = emptySet())
    private data class Curve(val start: Double, val middle: Double, val end: Double)
    private data class PrintedMeter(val value: PdfMeter, val end: Int)
    internal data class MeterResult(val meter: PdfMeter, val needsConfirmation: Boolean)
    private val fretToken = Regex("""\d{1,2}|X|<\d{1,2}(?:\.\d{1,2})?>|\(\d{1,2}\)""")
    private val unreadSymbol = "扫描图中有未识别的音符或休止符"

    fun recognize(source: PdfRasterImage, page: Int, start: Int, inherited: MeterResult? = null,
                  printLayout: PdfRasterPrintLayout? = null, preceding: List<PdfTabNote> = emptyList()): Pair<PdfTabResult, MeterResult> {
        val slope = source.detectSlope()
        val straight = source.deskew(slope)
        val detected = PdfRasterLayout.staves(straight)
        pdfRequire(detected.isNotEmpty(), "这一页没有找到成组的六根弦线。请选用清晰、平直的六线谱扫描件。")
        val layout = printLayout?.takeIf { slope == 0.0 && it.matches(detected, straight.width) }
        val systems = layout?.recoverStaves(detected, straight.width) ?: detected
        val strips = systems.mapIndexed { index, original ->
            val aligned = layout?.align(original, straight.width)
            val staff = aligned ?: original
            val outlined = aligned != null
            val boxes = if (outlined) layout.outlinesIn(staff, straight.width) else emptyList()
            val frets = boxes.takeIf { it.isNotEmpty() }?.let { PdfPrintFrets(straight, staff.gap, it, glyphs) }
            val rhythm = if (outlined) layout.outlinesIn(staff, straight.width, belowStaff = true) else emptyList()
            val next = systems.getOrNull(index + 1)
            val maxRhythmY = staff.bottom + staff.gap * 4.2
            // The next system's printed measure numbers can sit immediately below this rhythm lane.
            val rhythmEnd = next?.let { (it.top - it.gap * 1.6).coerceIn(staff.bottom + staff.gap * 3.1, maxRhythmY) }
                ?: maxRhythmY
            Strip(straight, staff, outlined, frets, boxes, rhythm, rhythmEnd.roundToInt())
        }
        val outlinedFirst = strips.first().outlined && inherited == null
        val printedMeter = if (outlinedFirst) readPrintMeter(strips.first()) else null
        val meter = inherited ?: (printedMeter?.value ?: readMeter(strips.first()))?.let { MeterResult(it, false) }
            ?: MeterResult(PdfMeter(4, 4), true)
        val bpm = if (outlinedFirst) readPrintTempo(strips.first()) else null
        val bars = ArrayList<PdfTabBar>()
        var pendingTies = emptySet<Pair<Int, Int>>()
        for (strip in strips) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val mutes = palmMutes(strip, strip.staff.edges.first(), strip.staff.edges.last())
            for ((index, pair) in strip.staff.edges.zipWithNext().withIndex()) {
                val (left, right) = pair
                val hasSignature = inherited == null && bars.isEmpty() && !meter.needsConfirmation
                val previous = bars.lastOrNull()?.beats?.lastOrNull()?.notes ?: preceding.takeIf { bars.isEmpty() }.orEmpty()
                val reading = readBar(strip, left, right, index == 0, hasSignature, start + bars.size, page, meter.meter, mutes,
                    strip.outlined, printedMeter?.end?.takeIf { hasSignature }, previous)
                val bar = reading.bar
                if (pendingTies.isNotEmpty()) {
                    val destinations = bar.beats.firstOrNull()?.notes.orEmpty().filter { it.tied && !it.dead }
                    if (pendingTies.all { (string, fret) -> destinations.any { it.string == string && it.fret == fret } }) {
                        val origin = bars.last()
                        bars[bars.lastIndex] = origin.copy(issues = origin.issues - unreadSymbol)
                    }
                }
                pendingTies = reading.outgoingTies
                bars.add(bar.copy(provisionalMeter = meter.needsConfirmation, scanned = true))
            }
        }
        pdfRequire(bars.isNotEmpty(), "扫描页上没有可识别的小节。")
        val track = PdfTabTrack("扫描吉他谱", 6, null, bars)
        return PdfTabResult(bpm, listOf(track), source.width.toDouble(), true) to meter
    }

    private fun readPrintMeter(strip: Strip): PrintedMeter? {
        val staff = strip.staff
        val g = staff.gap
        val left = staff.edges.first() + (g * 0.4).roundToInt()
        val right = minOf(staff.edges[1] - 1, staff.edges.first() + (g * 4.2).roundToInt())
        if (right <= left) return null
        val middle = (staff.top + g * 2.5).roundToInt()
        fun number(top: Int, bottom: Int): Pair<Int, Int>? {
            val patch = strip.cleaned(RasterBox(left, top, right, bottom))
            val bounds = patch.bounds() ?: return null
            if (bounds.height / g !in 1.50..2.50) return null
            return printDigit(patch, bounds.height / g)?.let { it to bounds.right }
        }
        val (numerator, upperEnd) = number(staff.top, middle) ?: return null
        val (denominator, lowerEnd) = number(middle, staff.bottom) ?: return null
        if (numerator !in 1..9 || denominator !in listOf(1, 2, 4, 8)) return null
        return PrintedMeter(PdfMeter(numerator, denominator), maxOf(upperEnd, lowerEnd) + 1)
    }

    private fun readPrintTempo(strip: Strip): Int? {
        val staff = strip.staff
        val g = staff.gap
        val left = staff.edges.first()
        val region = RasterBox(left, (staff.top - g * 4.5).roundToInt(), left + (g * 13).roundToInt(),
            (staff.top - g * 1.70).roundToInt())
        val field = strip.image.filter(region)
        val parts = field.columns(1).mapNotNull { field.crop(it) }
        if (parts.size !in 4..5) return null
        val note = parts[0].bounds() ?: return null
        val equal = parts[1].bounds() ?: return null
        if (note.height / g !in 1.1..1.85 || note.width / g !in 0.28..0.78 || equal.width / g !in 0.35..0.85) return null
        val rows = DoubleArray(equal.height) { y ->
            (equal.left until equal.right).sumOf { x -> strip.image[x, equal.top + y] } / (equal.width * 255.0)
        }
        if (PdfRasterLayout.peaks(rows, 0.47, 1).size != 2) return null
        var bpm = 0
        for (part in parts.drop(2)) {
            val size = (part.bounds() ?: return null).height / g
            if (size !in 0.55..1.0) return null
            bpm = bpm * 10 + (printDigit(part, size) ?: return null)
        }
        return bpm.takeIf { it in 20..400 }
    }

    private fun printDigit(patch: RasterPatch, height: Double): Int? =
        glyphs.match(RasterAlphabet.PRINT_DIGIT, patch, height)
            ?.takeIf { it.distance <= 0.49 && it.separation >= 0.035 }?.value?.toIntOrNull()

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
        meter: PdfMeter, mutes: List<ClosedFloatingPointRange<Double>>, outlined: Boolean, signatureEnd: Int?,
        preceding: List<PdfTabNote>
    ): BarReading {
        val g = strip.staff.gap
        val innerLeft = signatureEnd ?: (left + (g * when {
            hasSignature && outlined -> 4.2
            hasSignature -> 3.70
            startsSystem && !outlined -> 2.55
            else -> 0.20
        }).roundToInt())
        val innerRight = right - (g * 0.18).roundToInt()
        val bounds = PdfBox(left.toDouble(), (strip.staff.top - g * 4).coerceAtLeast(0.0), right.toDouble(), strip.staff.bottom + g * 4)
        val issues = linkedSetOf<String>()
        val review = linkedSetOf<String>()
        val uncertainFrets = ArrayList<FretInk>()
        val unmatched = ArrayList<RasterPatch>()
        val onsets = ArrayList<Onset>()
        if (innerRight - innerLeft < g * 1.4) {
            return BarReading(PdfTabBar(number, page, bounds, emptyList(), listOf("小节宽度不足，无法确认扫描内容"), emptyList(), meter))
        }
        val low = (strip.staff.top - 0.58 * g).roundToInt()
        val high = (strip.staff.bottom + 0.58 * g).roundToInt() + 1
        val stems = stems(strip, innerLeft, innerRight)
        val dottedRests = findDottedRests(strip, innerLeft, innerRight, stems)
        val restInk = dottedRests.flatMap { it.ink }
        val field = strip.cleaned(RasterBox(innerLeft, low, innerRight, high)) { x, y ->
            restInk.any { x in it.left until it.right && y in it.top until it.bottom }
        }
        onsets.addAll(dottedRests.map { it.onset })
        val patches = field.columns((g * 0.32).roundToInt().coerceAtLeast(2)).mapNotNull { group ->
            val patch = field.crop(group) ?: return@mapNotNull null
            val box = patch.bounds() ?: return@mapNotNull null
            val outsideStrings = box.bottom < strip.staff.top - g * 0.10
            val boundaryStroke = box.height > 4.2 * g && box.width < 0.67 * g &&
                (box.left - left < g * 0.72 || right - box.right < g * 0.72)
            patch.takeUnless { outsideStrings || boundaryStroke || box.height < 0.22 * g || box.width < 0.08 * g }
        }
        val withoutRhythm = outlined && onsets.isEmpty() && stems.isEmpty() &&
            strip.image.filter(RasterBox(innerLeft, high, innerRight, strip.rhythmEnd), 105).bounds() == null
        val wholeRest = PdfTabBeat(1, emptyList())
        if (withoutRhythm && patches.isEmpty()) {
            return BarReading(PdfTabBar(number, page, bounds, listOf(wholeRest), emptyList(), emptyList(), meter))
        }
        val mayBeWhole = withoutRhythm && pdfBeatsFit(listOf(wholeRest), meter)
        val used = HashSet<Stem>()
        fun addFretted(patch: RasterPatch, stem: Stem?, center: Double? = null) {
            val x = center ?: stem?.x?.toDouble() ?: patch.bounds()?.midX ?: return
            val previous = onsets.asSequence().filter { it.x < x }.maxByOrNull { it.x }
            val beat = fingeredBeat(strip, patch, stem, mutes, issues, review, uncertainFrets, outlined,
                previous?.beat?.notes ?: preceding, previous?.x, center) ?: return
            onsets.add(beat)
            if (stem != null) used.add(stem)
        }
        for (patch in patches) {
            val box = patch.bounds() ?: continue
            val inGroup = stems.filter { it.x.toDouble() in box.left - g * 0.17..box.right + g * 0.17 }
            if (inGroup.size > 1) {
                // A fast passage can put adjacent fret numbers in a single column group.
                for ((index, stem) in inGroup.withIndex()) {
                    val a = if (index == 0) box.left else (inGroup[index - 1].x + stem.x) / 2
                    val b = if (index == inGroup.lastIndex) box.right else (inGroup[index + 1].x + stem.x) / 2
                    val subsection = field.crop(RasterBox(a, box.top, b, box.bottom)) ?: continue
                    addFretted(subsection, stem)
                }
            } else if (inGroup.size == 1) {
                val stem = inGroup.single()
                addFretted(patch, stem)
            } else {
                val rest = readRest(strip.staff, patch)
                // This print draws a whole note as a fret number with the rhythm lane left empty.
                val whole = if (mayBeWhole) strip.frets?.chordCenter(box.left, box.right, strip.staff.rows) else null
                if (rest != null) onsets.add(Onset(box.midX, PdfTabBeat(rest, emptyList())))
                else if (whole != null || isOval(strip, box)) addFretted(patch, null, whole)
                else unmatched.add(patch)
            }
        }
        if (unmatched.isNotEmpty()) issues.add(unreadSymbol)
        if (stems.any { it !in used }) issues.add("存在无法配对的符干")
        val sorted = onsets.sortedBy { it.x }
        val beats = applyPrintedLegato(strip, sorted, applyTupletsAndDots(strip, sorted, left, right, issues), uncertainFrets)
        for (unread in uncertainFrets) issues.add("第 ${unread.row + 1} 弦品位需确认")
        if (!pdfBeatsFit(beats, meter)) issues.add("识别出的节奏未填满 $meter 小节")
        val outgoing = if (outlined) unmatched.map { outgoingTie(strip, it, sorted.lastOrNull(), right) } else emptyList()
        val pending = if (outgoing.any { it == null }) emptySet() else outgoing.filterNotNull().toSet()
        return BarReading(PdfTabBar(number, page, bounds, beats, issues.toList(), review.toList(), meter), pending)
    }

    private fun outgoingTie(strip: Strip, patch: RasterPatch, origin: Onset?, right: Int): Pair<Int, Int>? {
        val onset = origin ?: return null
        val g = strip.staff.gap
        val box = patch.bounds(105) ?: return null
        if ((right - box.right) / g !in 0.0..0.52 || (box.left - onset.x) / g !in 0.30..1.35 ||
            box.width / g !in 1.15..5.0 || box.height / g !in 0.20..0.83) return null
        val note = onset.beat.notes.singleOrNull { note ->
            val row = strip.staff.rows[strip.staff.rows.size - note.string]
            !note.dead && (box.top - row) / g in -0.10..0.34 && (box.bottom - row) / g in 0.20..0.94
        } ?: return null
        val curve = thinCurve(patch.crop(box) ?: return null, g) ?: return null
        if (curve.middle - curve.start < g * 0.13 || curve.end < curve.start - g * 0.10) return null
        return note.string to note.fret
    }

    private fun applyPrintedLegato(strip: Strip, onsets: List<Onset>, beats: List<PdfTabBeat>,
                                   uncertain: MutableList<FretInk>): List<PdfTabBeat> {
        val frets = strip.frets ?: return beats
        if (beats.size < 3) return beats
        val result = beats.toMutableList()
        val g = strip.staff.gap
        for (i in 0..beats.size - 3) {
            val first = beats[i].notes.singleOrNull() ?: continue
            val middle = beats[i + 1].notes.singleOrNull() ?: continue
            val last = beats[i + 2].notes.singleOrNull() ?: continue
            if (first.string != middle.string || first.string != last.string || first.fret != last.fret ||
                middle.fret <= first.fret || listOf(first, middle, last).any { it.dead || it.tied || it.bend != null }) continue
            val x0 = onsets[i].x
            val x1 = onsets[i + 1].x
            val x2 = onsets[i + 2].x
            if ((x1 - x0) / g !in 0.8..3.2 || (x2 - x1) / g !in 0.8..3.2) continue
            val row = strip.staff.rows.size - first.string
            val y = strip.staff.rows[row]
            val area = RasterBox(x0.roundToInt(), (y - 1.30 * g).roundToInt(),
                x2.roundToInt() + 1, (y - g * 0.47).roundToInt())
            val neighboringString = strip.staff.rows.getOrNull(row - 1)
            val curve = thinCurve(strip.image.filter(area), g, neighboringString) ?: continue
            if (minOf(curve.start, curve.end) - curve.middle < g * 0.17 ||
                abs(curve.start - curve.end) > g * 0.26 || curve.middle > y - g * 0.83) continue
            if (frets.at(x0, y.toDouble())?.fret != first.fret || frets.at(x1, y.toDouble())?.fret != middle.fret ||
                frets.at(x2, y.toDouble())?.fret != last.fret) continue
            for (index in i..i + 1) {
                result[index] = result[index].copy(notes = listOf(result[index].notes.single().copy(hammerPull = true)))
            }
            uncertain.removeAll { ink ->
                ink.row == row - 1 && ink.x in x0..x2 && ink.bounds.left >= area.left && ink.bounds.right <= area.right &&
                    ink.bounds.top >= area.top && ink.bounds.bottom <= area.bottom &&
                    !frets.hasDigitNear(ink.x, strip.staff.rows[ink.row].toDouble())
            }
        }
        return result
    }

    /** Keep the curve where it crosses a string, and discard a separately visible string stroke. */
    private fun thinCurve(patch: RasterPatch, gap: Double, crossingString: Int? = null): Curve? {
        val points = ArrayList<Pair<Int, Double>>()
        for (x in 0 until patch.pixels.width) {
            var ink = (0 until patch.pixels.height).filter { y -> patch.pixels[x, y] >= 105 }
            if (ink.isEmpty()) continue
            if (ink.last() - ink.first() + 1 > gap * 0.29 && crossingString != null) {
                val split = (1 until ink.size).singleOrNull { ink[it] - ink[it - 1] > 1 } ?: return null
                val upper = ink.subList(0, split)
                val lower = ink.subList(split, ink.size)
                fun onString(run: List<Int>) = run.all { abs(it + patch.box.top - crossingString) <= gap * 0.12 }
                ink = when {
                    onString(upper) && !onString(lower) -> lower
                    onString(lower) && !onString(upper) -> upper
                    else -> return null
                }
            }
            if (ink.last() - ink.first() + 1 > gap * 0.29) return null
            points.add(x to patch.box.top + ink.average())
        }
        if (points.isEmpty() || points.size < patch.pixels.width * 0.77 ||
            points.zipWithNext().any { (a, b) -> b.first - a.first > gap * 0.40 }) return null
        val n = (points.size * 0.20).toInt().coerceAtLeast(1)
        fun mean(from: Int) = points.subList(from, from + n).map { it.second }.average()
        return Curve(mean(0), mean((points.size - n) / 2), mean(points.size - n))
    }

    private fun findDottedRests(strip: Strip, left: Int, right: Int, stems: List<Stem>): List<DottedRest> {
        val staff = strip.staff
        val g = staff.gap
        val shapes = strip.artwork.filter { it.left >= left && it.right <= right }
        if (shapes.isEmpty()) return emptyList()
        val dots = shapes.filter { box ->
            val w = box.right - box.left
            val h = box.bottom - box.top
            w / g in 0.11..0.32 && h / g in 0.11..0.32 && abs(w - h) <= g * 0.10 &&
                strip.image[((box.left + box.right) / 2).roundToInt(), ((box.top + box.bottom) / 2).roundToInt()] >= 105
        }.distinct().sortedBy { it.left }
        if (dots.isEmpty()) return emptyList()
        return shapes.distinct().mapNotNull { body ->
            val x = (body.left + body.right) / 2
            val y = (body.top + body.bottom) / 2
            if ((body.bottom - body.top) / g !in 1.0..2.5 || (body.right - body.left) / g !in 0.28..1.1 ||
                y !in staff.top + g * 0.9..staff.top + g * 3.0 || stems.any { abs(it.x - x) <= g * 0.7 }) return@mapNotNull null
            val first = dots.firstOrNull { (it.left - body.right) / g in 0.08..0.43 && abs((it.top + it.bottom) / 2 - y) <= g * 0.36 }
                ?: return@mapNotNull null
            val second = dots.firstOrNull { (it.left - first.right) / g in 0.06..0.35 && abs(it.top - first.top) <= g * 0.14 }
            val bodyBox = body.rasterBox(g * 0.045)
            val patch = strip.cleaned(bodyBox)
            val ink = patch.bounds() ?: return@mapNotNull null
            val match = glyphs.match(RasterAlphabet.REST, patch, ink.height / g) ?: return@mapNotNull null
            val denominator = match.value.toIntOrNull() ?: return@mapNotNull null
            if (denominator < 4 || match.distance > 0.51 || match.separation < 0.055) return@mapNotNull null
            val masks = listOfNotNull(body.rasterBox(g * 0.12), first.rasterBox(g * 0.1), second?.rasterBox(g * 0.1))
            DottedRest(Onset(x, PdfTabBeat(denominator, emptyList(), dots = masks.size - 1)), masks)
        }
    }

    private fun PdfBox.rasterBox(padding: Double) = RasterBox(floor(left - padding).toInt(), floor(top - padding).toInt(),
        ceil(right + padding).toInt(), ceil(bottom + padding).toInt())

    private fun fingeredBeat(
        strip: Strip, patch: RasterPatch, stem: Stem?, mutes: List<ClosedFloatingPointRange<Double>>,
        issues: MutableSet<String>, review: MutableSet<String>, uncertainFrets: MutableList<FretInk>, outlined: Boolean,
        preceding: List<PdfTabNote>, previousX: Double?,
        center: Double?
    ): Onset? {
        val staff = strip.staff
        val g = staff.gap
        val box = patch.bounds() ?: return null
        val oval = isOval(strip, box)
        val x = center ?: stem?.x?.toDouble() ?: box.midX
        val notes = ArrayList<PdfTabNote>()
        for ((index, line) in staff.rows.withIndex()) {
            val y = line - g * 0.02
            val string = staff.rows.size - index
            val printed = strip.frets?.at(x, y)
            if (printed != null) {
                val tied = printed.parenthesized && preceding.any { it.string == string && it.fret == printed.fret && !it.dead }
                if (printed.parenthesized && !tied) issues.add("第 ${index + 1} 弦的括号音缺少明确的前音")
                notes.add(PdfTabNote(string, printed.fret, palmMute = mutes.any { x in it }, tied = tied))
                continue
            }
            if (stem != null && previousX != null && strip.frets?.hasDigitNear(x, y) == false) {
                val origin = preceding.singleOrNull { it.string == string && !it.dead }
                if (origin != null && hasPrintedTie(strip, previousX, x, y)) {
                    notes.add(origin.copy(tied = true, ghost = false, bend = null, slideIn = null, slideOut = null, hammerPull = false))
                    continue
                }
            }
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
            val candidate = options.minByOrNull { abs(it.first.midX - x) }
            if (candidate == null) {
                val unread = unresolvedFretInk(outside, x, y, g)
                if (unread != null) uncertainFrets.add(FretInk(x, index, unread))
                continue
            }
            val (ink, suitable) = candidate
            val matched = glyphs.match(RasterAlphabet.FRET, suitable, ink.height / g)
            val recognized = when {
                matched != null && matched.distance <= 0.58 && matched.separation >= 0.025 && fretToken.matches(matched.value) -> matched.value
                isCross(suitable) -> "X"
                else -> null
            }
            if (recognized == null) {
                uncertainFrets.add(FretInk(x, index, ink))
                continue
            }
            val token = recognized
            val fretNumber = token.trim('(', ')', '<', '>').toDoubleOrNull() ?: 0.0
            val fret = fretNumber.toInt()
            val harmonic = fretNumber.takeIf { token.startsWith('<') }?.let { PdfHarmonic(PdfHarmonicKind.NATURAL, it) }
            val note = PdfTabNote(string, fret, dead = token == "X", palmMute = mutes.any { x in it },
                harmonic = harmonic, ghost = token.startsWith('('))
            notes.add(note)
            if (token.startsWith('(')) {
                if (outlined) issues.add("括号音需要核对是否为前一拍的延音")
                else review.add("括号音需要核对延音或轻触奏法")
            }
        }
        if (notes.isEmpty()) {
            issues.add("扫描图中有符干但品位无法读出")
            return null
        }
        val duration = when {
            stem == null -> 1
            oval || stem.end - stem.start < g * 1.18 -> 2
            else -> 4 shl flagOrBeam(strip, stem)
        }
        return Onset(x, PdfTabBeat(duration, notes.sortedBy { it.string }))
    }

    /** The print omits the repeated fret only when the rhythm stem is joined from the same string by a tie. */
    private fun hasPrintedTie(strip: Strip, from: Double, to: Double, row: Double): Boolean {
        val gap = strip.staff.gap
        if ((to - from) / gap !in 1.4..6.0) return false
        val box = RasterBox((from + gap * 0.52).roundToInt(), (row + gap * 0.06).roundToInt(),
            (to + gap * 0.14).roundToInt(), (row + gap * 0.92).roundToInt())
        val pixels = strip.cleaned(box).pixels
        val arc = (0 until pixels.width).mapNotNull { x ->
            var count = 0
            var sum = 0
            for (y in 0 until pixels.height) if (pixels[x, y] >= 105) { count++; sum += y }
            if (count == 0 || count > gap * 0.31) null else x to sum.toDouble() / count
        }
        if (arc.size < pixels.width * 0.70 || arc.first().first > gap * 0.48 ||
            pixels.width - 1 - arc.last().first > gap * 0.45) return false
        val width = (arc.size * 0.22).toInt().coerceAtLeast(1)
        val start = arc.take(width).map { it.second }.average()
        val middle = arc.drop((arc.size - width) / 2).take(width).map { it.second }.average()
        val end = arc.takeLast(width).map { it.second }.average()
        return middle - maxOf(start, end) >= gap * 0.12 && abs(start - end) <= gap * 0.26
    }

    private fun unresolvedFretInk(row: RasterPatch, x: Double, y: Double, gap: Double): RasterBox? {
        val nearStem = RasterBox((x - gap * 0.52).roundToInt(), row.box.top,
            (x + gap * 0.52).roundToInt() + 1, row.box.bottom)
        val ink = row.crop(nearStem)?.bounds(115) ?: return null
        return ink.takeIf { it.height >= gap * 0.34 && it.width >= gap * 0.13 && abs(it.midY - y) <= gap * 0.27 }
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

    private fun stems(strip: Strip, left: Int, right: Int): List<Stem> {
        val image = strip.image
        val staff = strip.staff
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
                if (a < 0) return
                val length = (b - a + 1) / gap
                val regular = length >= 0.76 && a <= staff.bottom + gap * 1.60 && b >= staff.bottom + gap * 0.96
                // In these prints a half-note stem occupies only the bottom section of the usual stem lane.
                val short = !regular && strip.frets != null && length in 0.62..1.03 &&
                    (a - staff.bottom) / gap in 1.71..2.17 && (b - staff.bottom) / gap in 2.48..2.90 &&
                    staff.rows.any { row -> strip.frets.at(x.toDouble(), row.toDouble()) != null }
                if (!regular && !short) return
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

    private fun flagOrBeam(strip: Strip, stem: Stem): Int {
        val image = strip.image
        val g = strip.staff.gap
        val near = (stem.end - 2.15 * g).roundToInt()..(stem.end + g * 0.24).roundToInt()
        val step = (g * 0.16).roundToInt().coerceAtLeast(2)
        val distance = (g * 0.68).roundToInt().coerceAtLeast(step + 4)
        fun stripe(y: Int, direction: Int) = (step..distance).sumOf { image[stem.x + direction * it, y] } / (255.0 * (distance - step + 1))
        // The rounded tip and hook of a single printed flag can look like two beams at the stem.
        val printed = strip.rhythm.distinct().singleOrNull { shape ->
            abs(shape.left - stem.x) <= g * 0.12 && (shape.right - shape.left) / g in 0.20..0.45 &&
                (shape.bottom - shape.top) / g in 1.15..1.95 && abs(shape.top - stem.start) <= g * 0.14 &&
                abs(shape.bottom - stem.end) <= g * 0.16
        }
        if (printed != null && near.none { y ->
                (-1..1 step 2).any { direction ->
                    val from = (g * 0.50).roundToInt()
                    (from..distance).all { image[stem.x + direction * it, y] >= 90 }
                }
            }) {
            val from = (printed.left + (printed.right - printed.left) * 0.46).roundToInt()
            val to = ceil(printed.right).toInt() - 1
            val profile = DoubleArray(ceil(printed.bottom - printed.top).toInt()) { row ->
                (from..to).maxOfOrNull { x -> image[x, printed.top.roundToInt() + row] / 255.0 } ?: 0.0
            }
            val lobes = PdfRasterLayout.peaks(profile, 0.30, (g * 0.19).roundToInt().coerceAtLeast(1))
            if (lobes.isNotEmpty() && lobes.size <= 3) return lobes.size
        }
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
        findOutlinedTriplets(strip, onsets, left, right).forEach { triplets.addAll(it) }
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
            if ((middle - 1..middle + 1).none { it in triplets } && groupBeats.map { it.beat.denominator }.distinct().size == 1 &&
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

    private fun findOutlinedTriplets(strip: Strip, onsets: List<Onset>, left: Int, right: Int): List<IntRange> {
        if (strip.rhythm.isEmpty() || onsets.size < 3) return emptyList()
        val g = strip.staff.gap
        val result = ArrayList<IntRange>()
        for (mark in strip.rhythm.distinct()) {
            val x = (mark.left + mark.right) / 2
            if (mark.left < left || mark.right > right || (mark.top - strip.staff.bottom) / g !in 2.9..4.05 ||
                (mark.bottom - mark.top) / g !in 0.36..0.75 || (mark.right - mark.left) / g !in 0.22..0.56) continue
            val middle = onsets.indices.minBy { abs(onsets[it].x - x) }
            if (middle !in 1 until onsets.lastIndex || abs(onsets[middle].x - x) > g * 0.4) continue
            val members = middle - 1..middle + 1
            if (result.any { members.first <= it.last && it.first <= members.last }) continue
            val first = onsets[members.first]
            val last = onsets[members.last]
            val a = onsets[middle].x - first.x
            val b = last.x - onsets[middle].x
            if (first.beat.denominator != onsets[middle].beat.denominator || first.beat.denominator != last.beat.denominator ||
                minOf(a, b) < g * 0.60 || maxOf(a, b) > g * 4 || maxOf(a, b) / minOf(a, b) > 1.55) continue
            val patch = strip.image.filter(mark.rasterBox(g * 0.035))
            val match = glyphs.match(RasterAlphabet.PRINT_TUPLET, patch, (mark.bottom - mark.top) / g) ?: continue
            if (match.value != "3" || match.distance > 0.43 || match.separation < 0.055) continue
            val start = (first.x + g * 0.12).roundToInt()
            val end = (last.x - g * 0.12).roundToInt()
            val before = start until (mark.left - g * 0.06).roundToInt()
            val after = (mark.right + g * 0.06).roundToInt()..end
            if (before.count() < g * 0.28 || after.count() < g * 0.28) continue
            val bracket = ((mark.top - g * 0.14).roundToInt()..(mark.bottom + g * 0.2).roundToInt()).any { y ->
                before.count { strip.image[it, y] >= 95 } >= before.count() * 0.72 &&
                    after.count { strip.image[it, y] >= 95 } >= after.count() * 0.72
            }
            if (bracket) result.add(members)
        }
        return result
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


    private class Strip(val image: PdfRasterImage, val staff: RasterTabStaff, val outlined: Boolean, val frets: PdfPrintFrets?,
                        val artwork: List<PdfBox>, val rhythm: List<PdfBox>, val rhythmEnd: Int) {
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
