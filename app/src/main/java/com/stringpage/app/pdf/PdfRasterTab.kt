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
    private data class Stem(val x: Int, val start: Int, val end: Int, val inside: RasterBox? = null)
    private data class Onset(val x: Double, val beat: PdfTabBeat)
    private data class DottedRest(val onset: Onset, val ink: List<RasterBox>)
    private data class FretInk(val x: Double, val row: Int, val bounds: RasterBox)
    private data class BarReading(val bar: PdfTabBar, val outgoingTies: Set<Pair<Int, Int>> = emptySet())
    private data class Curve(val start: Double, val middle: Double, val end: Double)
    private data class PrintedMeter(val value: PdfMeter, val end: Int)
    internal data class MeterResult(val meter: PdfMeter, val needsConfirmation: Boolean, val printedStyle: Boolean = false)
    private val fretToken = Regex("""\d{1,2}|X|<\d{1,2}(?:\.\d{1,2})?>|\(\d{1,2}\)""")
    private val unreadSymbol = "扫描图中有未识别的音符或休止符"

    fun recognize(source: PdfRasterImage, page: Int, start: Int, inherited: MeterResult? = null,
                  printLayout: PdfRasterPrintLayout? = null, preceding: List<PdfTabNote> = emptyList()): Pair<PdfTabResult, MeterResult> {
        val slope = source.detectSlope()
        // Lines outside the score can dominate the raster's estimate even on a straight digital page.
        val print = printLayout?.takeIf { hints ->
            slope == 0.0 || hints.recoverStaves(emptyList(), source.width).let { frames ->
                frames.isNotEmpty() && hints.matches(frames, source.width)
            }
        }
        val straight = if (print != null) source else source.deskew(slope)
        val detected = PdfRasterLayout.staves(straight, recoverFaintStrings = print == null)
        val reconstructed = print?.recoverStaves(detected, straight.width).orEmpty()
        val layout = print?.takeIf { it.matches(reconstructed, straight.width) }
        val systems = if (layout == null) detected else reconstructed
        pdfRequire(systems.isNotEmpty(), "这一页没有找到成组的六根弦线。请选用清晰、平直的六线谱扫描件。")
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
        val first = strips.first()
        val standardMeter = if (inherited == null && !first.outlined) readMeter(first) else null
        val printedMeter = if (inherited == null && standardMeter == null) readPrintMeter(first) else null
        val bpm = when {
            inherited != null -> null
            first.outlined || printedMeter != null -> readPrintTempo(first)
            standardMeter != null -> readScoreTempo(first)
            else -> null
        }
        val meter = inherited ?: (standardMeter ?: printedMeter?.value ?: if (first.outlined) readMeter(first) else null)
            ?.let { MeterResult(it, false, printedStyle = printedMeter != null && bpm != null) }
            ?: MeterResult(PdfMeter(4, 4), true)
        val bars = ArrayList<PdfTabBar>()
        var pendingTies = emptySet<Pair<Int, Int>>()
        for (strip in strips) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val mutes = palmMutes(strip, strip.staff.edges.first(), strip.staff.edges.last())
            val vibratos = if (strip.outlined) emptyList() else PdfRasterVibrato.find(strip.image, strip.staff)
            val harmonics = if (strip.outlined) emptyList() else PdfRasterHarmonics.labels(strip.image, strip.staff, glyphs)
            val edges = if (strip === first && !strip.outlined && printedMeter != null) {
                // The aligned downstrokes in a printed time signature can look like a barline.
                strip.staff.edges.filterIndexed { index, x -> index == 0 || x >= printedMeter.end }
            } else strip.staff.edges
            for ((index, pair) in edges.zipWithNext().withIndex()) {
                val (left, right) = pair
                val hasSignature = inherited == null && bars.isEmpty() && !meter.needsConfirmation
                val previous = bars.lastOrNull()?.beats?.lastOrNull()?.notes ?: preceding.takeIf { bars.isEmpty() }.orEmpty()
                val reading = readBar(strip, left, right, index == 0, hasSignature, start + bars.size, page, meter.meter, mutes,
                    vibratos, harmonics,
                    strip.outlined, meter.printedStyle, printedMeter?.end?.takeIf { hasSignature }, previous)
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
        val searchEnd = if (strip.outlined) staff.edges[1] else staff.edges.last()
        val right = minOf(searchEnd - 1, staff.edges.first() + (g * 4.65).roundToInt())
        if (right <= left) return null
        val middle = (staff.top + g * 2.5).roundToInt()
        fun number(top: Int, bottom: Int): Pair<Int, Int>? {
            val patch = strip.cleaned(RasterBox(left, top, right, bottom))
            val bounds = patch.bounds() ?: return null
            if (bounds.height / g !in 1.50..2.50) return null
            if ((bounds.left - staff.edges.first()) / g !in 0.6..3.6 || bounds.width / g !in 0.55..1.64) return null
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
        return readTempoMark(strip, parts[0], parts[1], parts.drop(2), scoreStyle = false)
    }

    private fun readScoreTempo(strip: Strip): Int? {
        val staff = strip.staff
        val g = staff.gap
        val left = staff.edges.first()
        val region = RasterBox(left, (staff.top - g * 4.7).roundToInt(), left + (g * 24).roundToInt(),
            (staff.top - g * 1.65).roundToInt())
        val image = strip.image.filter(region)
        val parts = image.columns(1).mapNotNull { image.crop(it) }
        val values = parts.indices.mapNotNull { i ->
            if (i + 3 >= parts.size) return@mapNotNull null
            val equals = parts[i + 1].bounds() ?: return@mapNotNull null
            if (equals.height / g !in 0.17..0.58) return@mapNotNull null
            val digits = ArrayList<RasterPatch>(3)
            var previous = equals
            for (part in parts.subList(i + 2, minOf(i + 6, parts.size))) {
                val ink = part.bounds() ?: break
                val spacing = if (digits.isEmpty()) 0.95 else 0.48
                if ((ink.left - previous.right) / g > spacing) break
                digits.add(part)
                previous = ink
            }
            if (digits.size !in 2..3) return@mapNotNull null
            readTempoMark(strip, parts[i], parts[i + 1], digits, scoreStyle = true)
        }.distinct()
        return values.singleOrNull()
    }

    private fun readTempoMark(strip: Strip, notePatch: RasterPatch, equalPatch: RasterPatch, digits: List<RasterPatch>,
                              scoreStyle: Boolean): Int? {
        val g = strip.staff.gap
        val note = notePatch.bounds() ?: return null
        val equal = equalPatch.bounds() ?: return null
        val equalWidth = if (scoreStyle) 0.35..1.03 else 0.35..0.85
        if (note.height / g !in 1.1..1.85 || note.width / g !in 0.28..0.78 || equal.width / g !in equalWidth) return null
        if (scoreStyle && (!quarterTempoHead(strip.image, note) || (equal.left - note.right) / g !in 0.22..1.34)) return null
        val rows = DoubleArray(equal.height) { y ->
            (equal.left until equal.right).sumOf { x -> strip.image[x, equal.top + y] } / (equal.width * 255.0)
        }
        if (PdfRasterLayout.peaks(rows, 0.47, 1).size != 2) return null
        var bpm = 0
        val range = if (scoreStyle) 0.54..1.22 else 0.55..1.0
        for (part in digits) {
            val size = (part.bounds() ?: return null).height / g
            if (size !in range) return null
            val value = if (scoreStyle) glyphs.match(RasterAlphabet.SCORE_DIGIT, part, size)
                ?.takeIf { it.distance <= 0.51 && it.separation >= 0.12 ||
                    it.distance <= 0.57 && it.separation >= 0.19 }?.value?.toIntOrNull()
            else printDigit(part, size)
            bpm = bpm * 10 + (value ?: return null)
        }
        return bpm.takeIf { it in 20..400 }
    }

    private fun quarterTempoHead(image: PdfRasterImage, note: RasterBox): Boolean {
        val upper = note.top until note.top + (note.height * 0.57).roundToInt()
        val left = note.left..note.left + (note.width * 0.41).roundToInt()
        if (upper.count { y -> left.any { x -> image[x, y] >= 95 } } > upper.count() * 0.13) return false
        val right = note.left + (note.width * 0.54).roundToInt() until note.right
        if (right.none { x -> upper.count { y -> image[x, y] >= 95 } >= upper.count() * 0.74 }) return false
        return (upper.last + 1 until note.bottom).any { y ->
            (note.left until note.right).count { x -> image[x, y] >= 95 } >= note.width * 0.65
        }
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
        meter: PdfMeter, mutes: List<ClosedFloatingPointRange<Double>>, vibratos: List<PdfRasterVibrato.Mark>,
        harmonics: List<PdfRasterHarmonics.Mark>, outlined: Boolean, printedStyle: Boolean, signatureEnd: Int?,
        preceding: List<PdfTabNote>
    ): BarReading {
        val g = strip.staff.gap
        val innerLeft = signatureEnd ?: (left + (g * when {
            hasSignature && outlined -> 4.2
            hasSignature -> 3.70
            startsSystem && !outlined && !printedStyle -> 2.55
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
        val stems = stems(strip, innerLeft, innerRight, printedStyle)
        val stemInk = stems.mapNotNull { it.inside }
        val dottedRests = findDottedRests(strip, innerLeft, innerRight, stems)
        val restInk = dottedRests.flatMap { it.ink }
        val borderInk = if (outlined) emptySet() else
            (innerLeft until innerRight).filter { x ->
                (x - left < g * 0.56 || right - x < g * 0.56) && hasContinuousBarline(strip, x)
            }.toSet()
        val field = strip.cleaned(RasterBox(innerLeft, low, innerRight, high)) { x, y ->
            x in borderInk || restInk.any { it.contains(x, y) } || stemInk.any { it.contains(x, y) }
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
        val withoutRhythm = onsets.isEmpty() && stems.isEmpty() &&
            strip.image.filter(RasterBox(innerLeft, high, innerRight, strip.rhythmEnd), 105).bounds() == null
        val wholeRest = PdfTabBeat(1, emptyList())
        if (withoutRhythm && patches.isEmpty() &&
            (outlined || field.bounds(105) == null && hasClosedStaffBar(strip, left, right, signatureEnd))) {
            return BarReading(PdfTabBar(number, page, bounds, listOf(wholeRest), emptyList(), emptyList(), meter))
        }
        val wholeFits = withoutRhythm && pdfBeatsFit(listOf(wholeRest), meter)
        if (wholeFits && printedStyle && !outlined) {
            scannedWholeBar(strip, field, left, right, startsSystem, preceding, mutes, vibratos,
                PdfTabBar(number, page, bounds, emptyList(), emptyList(), emptyList(), meter))?.let { return it }
        }
        val mayBeWhole = outlined && wholeFits
        val used = HashSet<Stem>()
        fun addFretted(patch: RasterPatch, stem: Stem?, center: Double? = null) {
            val x = center ?: stem?.x?.toDouble() ?: patch.bounds()?.midX ?: return
            val previous = onsets.asSequence().filter { it.x < x }.maxByOrNull { it.x }
            val beat = fingeredBeat(strip, patch, stem, mutes, issues, review, uncertainFrets, outlined, startsSystem,
                previous?.beat?.notes ?: preceding, previous?.x, center, left) ?: return
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
                val rest = readRest(strip.staff, patch, scanned = !outlined)
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
        val timed = applyTupletsAndDots(strip, sorted, left, right, printedStyle, issues)
        val articulated = applyPrintedLegato(strip, sorted, timed, uncertainFrets, printedStyle)
        val withBends = if (!outlined && !printedStyle && stems.any { it.inside != null }) {
            val legato = applyScannedLegato(strip, sorted, articulated, uncertainFrets, left, right)
            applyScannedBends(strip, sorted, legato, right, issues, review)
        } else articulated
        val beats = withBends.mapIndexed { index, beat ->
            val current = sorted[index]
            val next = sorted.getOrNull(index + 1)
            val resolved = if (!outlined && !printedStyle && next != null && next.x - current.x < g * 2.35 &&
                beat.notes.any { it.slideOut == PdfSlideOut.UP || it.slideOut == PdfSlideOut.DOWN }) {
                // A diagonal sign in a narrow gap can reach the following note on the same string.
                beat.copy(notes = beat.notes.map { note ->
                    if (note.slideOut in listOf(PdfSlideOut.UP, PdfSlideOut.DOWN) &&
                        next.beat.notes.any { it.string == note.string }) note.copy(slideOut = null) else note
                })
            } else beat
            val withHarmonic = applyScannedHarmonic(strip, resolved, current.x, next?.x ?: right.toDouble(), harmonics)
            applyScannedVibrato(withHarmonic, current.x, vibratos, review)
        }
        for (unread in uncertainFrets) issues.add("第 ${unread.row + 1} 弦品位需确认")
        if (!pdfBeatsFit(beats, meter)) issues.add("识别出的节奏未填满 $meter 小节")
        val outgoing = if (outlined || printedStyle) unmatched.map { outgoingTie(strip, it, sorted.lastOrNull(), right) } else emptyList()
        val pending = if (outgoing.any { it == null }) emptySet() else outgoing.filterNotNull().toSet()
        return BarReading(PdfTabBar(number, page, bounds, beats, issues.toList(), review.toList(), meter), pending)
    }

    private fun applyScannedHarmonic(strip: Strip, beat: PdfTabBeat, x: Double, next: Double,
                                     marks: List<PdfRasterHarmonics.Mark>): PdfTabBeat {
        val note = beat.notes.singleOrNull() ?: return beat
        if (note.dead || note.harmonic != null) return beat
        val gap = strip.staff.gap
        val matching = marks.filter { it.covers(x, gap) }
        val direct = matching.filter { abs(it.x - x) <= gap * 0.54 }
        val kind = direct.ifEmpty { matching }.map { it.kind }.distinct().singleOrNull() ?: return beat
        val row = strip.staff.rows[strip.staff.rows.size - note.string]
        val start = (x + gap * if (note.fret >= 10) 0.58 else 0.48).roundToInt()
        val end = minOf((x + gap * 2.95).roundToInt(), (next - gap * 0.30).roundToInt())
        if (end - start < gap * 0.88) return beat
        val area = RasterBox(start, (row - gap * 0.43).roundToInt(), end, (row + gap * 0.43).roundToInt() + 1)
        val position = PdfRasterHarmonics.node(strip.cleaned(area), x, row, gap, glyphs) ?: return beat
        val offset = kotlin.math.round((position - note.fret) * 100) / 100
        if (offset <= 0.0) return beat
        return beat.copy(notes = listOf(note.copy(harmonic = PdfHarmonic(kind, offset))))
    }

    private fun applyScannedVibrato(beat: PdfTabBeat, x: Double, marks: List<PdfRasterVibrato.Mark>,
                                    review: MutableSet<String>): PdfTabBeat {
        val effect = marks.filter { it.covers(x) }.map { it.effect }.distinct().singleOrNull() ?: return beat
        val notes = beat.notes.filterNot { it.dead }
        val note = notes.singleOrNull()
        if (note == null) {
            if (notes.size > 1) review.add("和弦中的揉弦弦号需核对")
            return beat
        }
        return beat.copy(notes = beat.notes.map { if (it == note) it.copy(vibrato = effect) else it })
    }

    private fun applyScannedBends(strip: Strip, onsets: List<Onset>, beats: List<PdfTabBeat>, right: Int,
                                  issues: MutableSet<String>, review: MutableSet<String>): List<PdfTabBeat> {
        return beats.mapIndexed { index, beat ->
            val note = beat.notes.singleOrNull() ?: return@mapIndexed beat
            if (note.dead || note.ghost || note.tied) return@mapIndexed beat
            val row = strip.staff.rows[strip.staff.rows.size - note.string]
            val reading = PdfRasterBends.upward(strip.image, strip.staff, glyphs, onsets[index].x, row,
                onsets.getOrNull(index + 1)?.x ?: right.toDouble()) ?: return@mapIndexed beat
            val bend = reading.bend
            if (bend == null) {
                issues.add("推弦归属或幅度待确认")
                return@mapIndexed beat
            }
            review.add("推弦幅度已识别，推弦时机按谱面估算")
            beat.copy(notes = listOf(note.copy(bend = bend)))
        }
    }

    private fun applyScannedLegato(strip: Strip, onsets: List<Onset>, beats: List<PdfTabBeat>,
                                   uncertain: MutableList<FretInk>, left: Int, right: Int): List<PdfTabBeat> {
        if (beats.size < 2) return beats
        val staff = strip.staff
        val g = staff.gap
        val image = strip.image
        val zone = RasterBox(left, (staff.top - g * 5.0).roundToInt(), right, (staff.top - g * 0.14).roundToInt())
        val labels = image.filter(zone, 95).components(105).mapNotNull { box ->
            if (box.width / g !in 0.34..0.94 || box.height / g !in 0.43..0.85) return@mapNotNull null
            val match = glyphs.match(RasterAlphabet.TECHNIQUE, image.filter(box), box.height / g) ?: return@mapNotNull null
            if (match.value !in listOf("H", "P") || match.distance > 0.60 || match.separation < 0.075) null
            else box to match.value
        }
        if (labels.isEmpty()) return beats
        val result = beats.toMutableList()
        for (index in 0 until beats.lastIndex) {
            val first = beats[index].notes.singleOrNull() ?: continue
            val second = beats[index + 1].notes.singleOrNull() ?: continue
            if (first.dead || second.dead || first.tied || second.tied || first.string != second.string || first.fret == second.fret) continue
            val a = onsets[index].x
            val b = onsets[index + 1].x
            if ((b - a) / g !in 0.7..4.2) continue
            val expected = if (second.fret > first.fret) "H" else "P"
            val label = labels.singleOrNull { (box, value) ->
                value == expected && box.midX in a + g * 0.08..b - g * 0.08
            }?.first ?: continue
            val row = staff.rows.size - first.string
            val y = staff.rows[row]
            if ((y - label.bottom) / g !in 0.65..3.5) continue
            val arc = RasterBox((a + g * 0.07).roundToInt(), (y - g * 1.67).roundToInt(),
                (b - g * 0.05).roundToInt(), (y - g * 0.20).roundToInt())
            val curve = thinCurve(strip.cleaned(arc), g) ?: continue
            val endpoints = maxOf(curve.start, curve.end)
            if ((y - endpoints) / g !in 0.21..1.14 || endpoints - minOf(curve.start, curve.middle, curve.end) < g * 0.19 ||
                curve.middle > endpoints - g * 0.07) continue
            result[index] = result[index].copy(notes = listOf(first.copy(hammerPull = true)))
            uncertain.removeAll { ink ->
                ink.row < row && ink.x in a..b && ink.bounds.left >= arc.left && ink.bounds.right <= arc.right &&
                    ink.bounds.top >= arc.top && ink.bounds.bottom <= arc.bottom &&
                    thinCurve(strip.cleaned(ink.bounds), g) != null
            }
        }
        return result
    }

    private fun scannedWholeBar(
        strip: Strip, field: RasterPatch, left: Int, right: Int, startsSystem: Boolean, preceding: List<PdfTabNote>,
        mutes: List<ClosedFloatingPointRange<Double>>, vibratos: List<PdfRasterVibrato.Mark>, bar: PdfTabBar
    ): BarReading? {
        val body = field.bounds(105) ?: return null
        val g = strip.staff.gap
        val rows = strip.staff.rows.map { it.toDouble() }
        val frets = rows.map { row ->
            scannedFrets(strip, fretWindow(body.left, body.right, row, g))
        }
        val centers = frets.flatMap { it.centers() }.distinct()
        val chords = centers.mapNotNull { center ->
            val chord = rows.mapIndexedNotNull { index, row ->
                frets[index].at(center, row)?.let { index to it }
            }
            chord.takeUnless { it.isEmpty() }
        }.distinct()
        val observedFrets = chords.flatten().distinct()
        val chord = chords.singleOrNull { candidate -> observedFrets.all { (index, fret) ->
            candidate.any { (row, covering) ->
                row == index && covering.ink.left <= fret.ink.left && covering.ink.right >= fret.ink.right &&
                    covering.ink.top <= fret.ink.top && covering.ink.bottom >= fret.ink.bottom
            }
        } } ?: return null
        val chordLeft = chord.minOf { it.second.ink.left }
        val chordRight = chord.maxOf { it.second.ink.right }
        if ((chordRight - chordLeft) / g !in 0.22..2.35) return null
        val center = (chordLeft + chordRight) / 2
        val confirmedIncoming = chord.filter { (index, fret) ->
            val string = rows.size - index
            fret.parenthesized && preceding.any { it.string == string && it.fret == fret.fret && !it.dead } &&
                hasIncomingRasterTie(strip, fret.ink.left, rows[index], left, startsSystem)
        }
        if (chord.count { it.second.parenthesized } != confirmedIncoming.size) return null
        val mask = chord.map { it.second.ink.rasterBox(g * 0.075) }
        val remaining = field.pixels.filter(RasterBox(0, 0, field.box.width, field.box.height)) { localX, localY ->
            val x = field.box.left + localX
            val y = field.box.top + localY
            mask.any { x in it.left until it.right && y in it.top until it.bottom } ||
                confirmedIncoming.any { (index, fret) ->
                    x >= fret.ink.left - g * 1.95 && x < fret.ink.left &&
                        y >= rows[index] - g * 0.02 && y < rows[index] + g * 0.85
                }
        }
        val ink = RasterPatch(field.box, remaining.pixels)
        val outgoing = ink.bounds(105)?.let { leftover ->
            val (index, fret) = chord.singleOrNull() ?: return null
            val row = rows[index]
            if ((leftover.left - chordRight) / g !in -0.05..0.65 || (right - leftover.right) / g !in 0.0..0.70 ||
                (leftover.top - row) / g !in -0.08..0.45 || (leftover.bottom - row) / g !in 0.16..0.83 ||
                leftover.width < g * 1.1) return null
            val curve = thinCurve(ink.crop(leftover) ?: return null, g) ?: return null
            if (curve.middle - curve.start < g * 0.10 || curve.end > curve.middle + g * 0.10) return null
            setOf(rows.size - index to fret.fret)
        }.orEmpty()
        val notes = chord.map { (index, fret) ->
            PdfTabNote(rows.size - index, fret.fret, palmMute = mutes.any { center in it }, tied = fret.parenthesized)
        }.sortedBy { it.string }
        val review = linkedSetOf<String>()
        val beat = applyScannedVibrato(PdfTabBeat(1, notes), center, vibratos, review)
        return BarReading(bar.copy(beats = listOf(beat), review = review.toList(),
            issues = if (outgoing.isEmpty()) emptyList() else listOf(unreadSymbol)), outgoing)
    }

    private fun hasClosedStaffBar(strip: Strip, left: Int, right: Int, signatureEnd: Int?): Boolean {
        val staff = strip.staff
        val band = (staff.gap * 0.17).roundToInt().coerceAtLeast(1)
        // A missing crop or a faint fragment is insufficient evidence for silence.
        val borders = (if (signatureEnd != null) listOf(right) else listOf(left, right)).all { edge ->
            (edge - band..edge + band).any { x -> hasContinuousBarline(strip, x) }
        }
        if (!borders) return false
        val columns = (signatureEnd ?: left + band)..right - band
        if (columns.count() < staff.gap * 1.4) return false
        return staff.rows.all { y ->
            columns.count { x -> (y - band..y + band).any { row -> strip.image[x, row] >= 82 } } >= columns.count() * 0.85
        }
    }

    private fun hasContinuousBarline(strip: Strip, x: Int): Boolean {
        val rows = strip.staff.top until strip.staff.bottom
        return rows.count { y -> strip.image[x, y] >= 82 } >= rows.count() * 0.82
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
                                   uncertain: MutableList<FretInk>, printedStyle: Boolean): List<PdfTabBeat> {
        if (beats.size < 3 || (strip.frets == null && !printedStyle)) return beats
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
            fun fretAt(x: Double) = strip.frets?.at(x, y.toDouble()) ?: scannedFingering(strip, x, y.toDouble())
            if (fretAt(x0)?.fret != first.fret || fretAt(x1)?.fret != middle.fret || fretAt(x2)?.fret != last.fret) continue
            for (index in i..i + 1) {
                result[index] = result[index].copy(notes = listOf(result[index].notes.single().copy(hammerPull = true)))
            }
            uncertain.removeAll { ink ->
                ink.row == row - 1 && ink.x in x0..x2 && ink.bounds.left >= area.left && ink.bounds.right <= area.right &&
                    ink.bounds.top >= area.top && ink.bounds.bottom <= area.bottom &&
                    (strip.frets ?: scannedFrets(strip, ink.x, strip.staff.rows[ink.row].toDouble()))
                        .hasDigitNear(ink.x, strip.staff.rows[ink.row].toDouble()).not()
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
        val shapes = if (strip.artwork.isEmpty()) {
            val area = RasterBox(left, (staff.top + g * 0.32).roundToInt(), right, (staff.top + 3.8 * g).roundToInt())
            val ink = strip.cleaned(area)
            ink.columns(0).mapNotNull { column ->
                val box = ink.crop(column)?.bounds(105) ?: return@mapNotNull null
                PdfBox(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())
            }
        } else strip.artwork.filter { it.left >= left && it.right <= right }
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
        startsSystem: Boolean,
        preceding: List<PdfTabNote>, previousX: Double?,
        center: Double?, barLeft: Int
    ): Onset? {
        val staff = strip.staff
        val g = staff.gap
        val box = patch.bounds() ?: return null
        val oval = isOval(strip, box)
        val x = center ?: stem?.x?.toDouble() ?: box.midX
        if (!outlined && stem != null && stem.end - stem.start < g * 1.18) {
            circledHalfNote(strip, x, box, mutes)?.let { return it }
        }
        val notes = ArrayList<PdfTabNote>()
        val printedFrets = if (!outlined && startsSystem && previousX == null) HashMap<Int, PdfPrintFrets.Fingering>() else null
        for ((index, line) in staff.rows.withIndex()) {
            val y = line - g * 0.02
            val string = staff.rows.size - index
            val scanned = if (strip.frets == null && stem != null) scannedFrets(strip, x, y, stem.inside) else null
            val printed = strip.frets?.at(x, y) ?: scanned?.let { scannedFingering(strip, x, y, it, stem?.inside) }
            if (printed != null) {
                printedFrets?.set(index, printed)
                val samePitch = preceding.any { it.string == string && it.fret == printed.fret && !it.dead }
                val tied = printed.parenthesized && samePitch &&
                    (strip.outlined || hasIncomingRasterTie(strip, printed.ink.left, y, barLeft, startsSystem))
                if (printed.parenthesized && !tied) {
                    if (strip.outlined) issues.add("第 ${index + 1} 弦的括号音缺少明确的前音")
                    else review.add("括号音需要核对延音或轻触奏法")
                }
                val slide = if (stem?.inside != null && !printed.parenthesized) {
                    outsideSlide(strip, printed.ink.right.roundToInt(), line, minSeparation = 0.13)
                } else null
                notes.add(PdfTabNote(string, printed.fret, palmMute = mutes.any { x in it }, tied = tied,
                    ghost = printed.parenthesized && !tied && !strip.outlined, slideOut = slide))
                continue
            }
            if (!outlined && stem != null && isScoreCross(strip, x, line)) {
                notes.add(PdfTabNote(string, 0, dead = true, palmMute = mutes.any { x in it }))
                continue
            }
            if (stem != null && previousX != null && (strip.frets ?: scanned)?.hasDigitNear(x, y) == false) {
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
        val sustained = if (printedFrets != null && printedFrets.size >= 3) {
            completePrintedChordTie(strip, notes, printedFrets, preceding, barLeft)
        } else notes
        return Onset(x, PdfTabBeat(duration, sustained.sortedBy { it.string }))
    }

    private fun circledHalfNote(strip: Strip, x: Double, ink: RasterBox,
                               mutes: List<ClosedFloatingPointRange<Double>>): Onset? {
        val g = strip.staff.gap
        val rings = strip.staff.rows.mapIndexedNotNull { index, y ->
            if (y < ink.top - g * 0.14 || y > ink.bottom + g * 0.14) return@mapIndexedNotNull null
            var best: RasterBox? = null
            var strongest = 32
            for (rx in listOf(0.63, 0.70, 0.77, 0.84, 0.91))
                for (ry in listOf(0.65, 0.72, 0.79, 0.86)) {
                    val box = RasterBox((x - g * rx).roundToInt(), (y - g * ry).roundToInt(),
                        (x + g * rx).roundToInt() + 1, (y + g * ry).roundToInt() + 1)
                    val perimeter = ovalSamples(strip, box)
                    if (perimeter > strongest) {
                        best = box
                        strongest = perimeter
                    }
                }
            best?.let { index to it }
        }
        val (index, ring) = rings.singleOrNull() ?: return null
        val row = strip.staff.rows[index]
        val window = fretWindow(ring.left, ring.right, row.toDouble(), g, scoreDigits = true)
        val omitRing = { px: Int, py: Int ->
            isOvalBoundary(ring, px, py, g * 0.13) || abs(px - x) >= (ring.width - 1) / 2.0 - g * 0.09
        }
        fun fingering(inside: RasterPatch): PdfPrintFrets.Fingering? {
            val digits = inside.columns(0).mapNotNull { part -> inside.crop(part)?.bounds(105)?.pdfBox() }
            return rasterFrets(strip, digits, scoreDigits = true).at(x, row.toDouble())
        }
        // A dark oval can bridge the interrupted string line and make cleanup erase the middle of a digit.
        val fret = fingering(strip.image.filter(window, suppress = omitRing))
            ?: fingering(strip.cleaned(window, omitRing))
            ?: return null
        val slide = outsideSlide(strip, ring.right, row)
        val note = PdfTabNote(strip.staff.rows.size - index, fret.fret, palmMute = mutes.any { x in it }, slideOut = slide)
        return Onset(x, PdfTabBeat(2, listOf(note)))
    }

    private fun outsideSlide(strip: Strip, right: Int, y: Int, minSeparation: Double = 0.0): PdfSlideOut? {
        val g = strip.staff.gap
        val area = RasterBox(right, (y - g * 0.54).roundToInt(), right + (g * 1.16).roundToInt(),
            (y + g * 0.54).roundToInt() + 1)
        val patch = strip.cleaned(area)
        // A slight rounding difference may leave a sliver of the circle in the crop.
        val candidates = (0..(g * 0.22).roundToInt()).mapNotNull { trim ->
            val segment = patch.crop(area.copy(left = right + trim)) ?: return@mapNotNull null
            val box = segment.bounds(105) ?: return@mapNotNull null
            if (box.width / g !in 0.49..0.88 || box.height / g !in 0.49..0.89 ||
                (box.left - right) / g !in minSeparation..0.38 || abs(box.midY - y) > g * 0.17) return@mapNotNull null
            val slope = thinCurve(segment.crop(box) ?: return@mapNotNull null, g) ?: return@mapNotNull null
            if (abs(slope.start - slope.end) < g * 0.32 ||
                abs(slope.middle - (slope.start + slope.end) / 2) > g * 0.14) return@mapNotNull null
            if (slope.end > slope.start) PdfSlideOut.DOWN else PdfSlideOut.UP
        }.distinct()
        return candidates.singleOrNull()
    }

    private fun completePrintedChordTie(strip: Strip, chord: List<PdfTabNote>, frets: Map<Int, PdfPrintFrets.Fingering>,
                                        preceding: List<PdfTabNote>, barLeft: Int): List<PdfTabNote> {
        val rows = strip.staff.rows
        val g = strip.staff.gap
        val tied = chord.filter { it.tied }.map { rows.size - it.string }.toSet()
        return chord.map { note ->
            val index = rows.size - note.string
            if (note.dead || note.tied || index - 1 !in tied || index + 1 !in tied ||
                preceding.none { !it.dead && it.string == note.string && it.fret == note.fret }) return@map note
            val upper = frets[index - 1]?.ink ?: return@map note
            val lower = frets[index + 1]?.ink ?: return@map note
            val digit = frets[index]?.ink ?: return@map note
            if (abs(upper.left - lower.left) > g * 0.12 || abs(upper.right - lower.right) > g * 0.12) return@map note
            val opening = (upper.left + lower.left) / 2
            val closing = (upper.right + lower.right) / 2
            if ((digit.left - opening) / g !in 0.18..0.50 || (closing - digit.right) / g !in 0.18..0.50) return@map note
            val row = rows[index] - g * 0.02
            // Adjacent parentheses can touch in a chord. Still require both side strokes and this note's own tie arc.
            fun side(from: Double, to: Double): Boolean {
                val columns = from.roundToInt()..to.roundToInt()
                val band = (row - g * 0.26).roundToInt()..(row + g * 0.26).roundToInt()
                return band.count { y -> columns.any { x -> strip.image[x, y] >= 105 } } >= band.count() * 0.76
            }
            if (side(opening, digit.left - g * 0.10) && side(digit.right + g * 0.10, closing) &&
                hasIncomingRasterTie(strip, opening, row, barLeft, startsSystem = true)) note.copy(tied = true, ghost = false)
            else note
        }
    }

    private fun scannedFrets(strip: Strip, x: Double, y: Double, inside: RasterBox? = null): PdfPrintFrets {
        val g = strip.staff.gap
        return scannedFrets(strip, fretWindow((x - g * 1.43).roundToInt(), (x + g * 1.43).roundToInt() + 1, y, g,
            scoreDigits = inside != null), inside)
    }

    private fun scannedFingering(strip: Strip, x: Double, y: Double, projected: PdfPrintFrets = scannedFrets(strip, x, y),
                                 inside: RasterBox? = null):
        PdfPrintFrets.Fingering? {
        projected.at(x, y)?.let { return it }
        val g = strip.staff.gap
        val window = fretWindow((x - g * 1.43).roundToInt(), (x + g * 1.43).roundToInt() + 1, y, g,
            scoreDigits = inside != null)
        val boxes = strip.cleaned(window) { px, py -> inside?.contains(px, py) == true }
            .components(105).map { it.pdfBox() }
        return rasterFrets(strip, boxes, inside != null).at(x, y)
    }

    private fun fretWindow(left: Int, right: Int, y: Double, g: Double, scoreDigits: Boolean = false): RasterBox {
        // The small score digits share their x position with bends and slurs above the string.
        val height = if (scoreDigits) 0.44 else 0.68
        return RasterBox(left, (y - g * height).roundToInt(), right, (y + g * (height + 0.01)).roundToInt() + 1)
    }

    private fun scannedFrets(strip: Strip, window: RasterBox, inside: RasterBox? = null): PdfPrintFrets {
        val ink = strip.cleaned(window) { x, y -> inside?.contains(x, y) == true }
        val boxes = ink.columns(0).mapNotNull { column ->
            val box = ink.crop(column)?.bounds(105) ?: return@mapNotNull null
            box.pdfBox()
        }
        return rasterFrets(strip, boxes, inside != null)
    }

    private fun rasterFrets(strip: Strip, boxes: List<PdfBox>, scoreDigits: Boolean) =
        PdfPrintFrets(strip.image, strip.staff.gap, boxes, glyphs, rasterDigits = true,
            digitAlphabet = if (scoreDigits) RasterAlphabet.SCORE_DIGIT else RasterAlphabet.PRINT_DIGIT)

    private fun RasterBox.contains(x: Int, y: Int) = x in left until right && y in top until bottom

    private fun RasterBox.pdfBox() = PdfBox(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())

    private fun hasIncomingRasterTie(strip: Strip, opening: Double, row: Double, barLeft: Int, startsSystem: Boolean): Boolean {
        val g = strip.staff.gap
        val stub = startsSystem && opening - barLeft < g * 1.8
        val minX = if (stub) barLeft + g * 0.20 else opening - g * 1.95
        val maxX = opening - g * 0.05
        if (maxX - minX < g * 0.20) return false
        val region = RasterBox(minX.roundToInt(), (row + 0.05 * g).roundToInt(), maxX.roundToInt(),
            (row + 0.90 * g).roundToInt())
        val arc = strip.cleaned(region) { px, _ -> abs(px - barLeft) <= g * 0.10 }
        val box = arc.bounds(105) ?: return false
        val minWidth = if (stub) 0.21 else 0.52
        if (box.width / g !in minWidth..2.0 || box.height / g !in 0.13..0.73 || region.right - box.right > g * 0.62) return false
        val curve = thinCurve(arc.crop(box) ?: return false, g) ?: return false
        val rise = maxOf(curve.start, curve.middle) - curve.end
        return rise > g * 0.13 && curve.end < row + g * 0.62
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

    private fun isScoreCross(strip: Strip, x: Double, row: Int): Boolean {
        val g = strip.staff.gap
        val aperture = RasterBox((x - 0.45 * g).roundToInt(), (row - 0.46 * g).roundToInt(),
            (x + 0.45 * g).roundToInt() + 1, (row + 0.46 * g).roundToInt() + 1)
        val patch = strip.cleaned(aperture)
        val ink = patch.bounds(105) ?: return false
        if (ink.width / g !in 0.48..0.85 || ink.height / g !in 0.53..0.90 ||
            abs(ink.midX - x) > g * 0.12 || abs(ink.midY - row) > g * 0.15) return false
        val body = patch.crop(ink)?.pixels ?: return false
        fun columns(y: Int) = (0 until body.width).filter { body[it, y] >= 105 }
        fun band(start: Double, end: Double) =
            ((body.height * start).roundToInt()..(body.height * end).roundToInt()).map(::columns).filter { it.isNotEmpty() }
        val joint = band(0.38, 0.61).minByOrNull { it.last() - it.first() } ?: return false
        if (joint.last() - joint.first() + 1 > body.width * 0.45 ||
            abs(joint.average() - (body.width - 1) / 2.0) > body.width * 0.15) return false
        fun hasArms(rows: List<List<Int>>) = rows.any { arm ->
            joint.first() - arm.first() >= body.width * 0.14 && arm.last() - joint.last() >= body.width * 0.14 &&
                arm.zipWithNext().any { (a, b) -> b - a >= body.width * 0.20 }
        }
        return hasArms(band(0.11, 0.28)) && hasArms(band(0.70, 0.89))
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

    private fun stems(strip: Strip, left: Int, right: Int, printedStyle: Boolean): List<Stem> {
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
                val short = !regular && length in 0.62..1.03 &&
                    (a - staff.bottom) / gap in 1.71..2.17 && (b - staff.bottom) / gap in 2.48..2.90 &&
                    (strip.frets?.let { frets -> staff.rows.any { row -> frets.at(x.toDouble(), row.toDouble()) != null } }
                        ?: (printedStyle && hasShortStemDot(image, x, b, gap)))
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
        val detected = PdfRasterLayout.peaks(confidence, gap * 0.43, (gap * 0.26).roundToInt())
            .mapNotNull { strokes[it + left] }
        if (strip.outlined || printedStyle) return detected
        val withInk = detected.map { it.copy(inside = attachedStemInk(strip, it)) }
        val attached = withInk.filter { it.inside != null }
        return withInk.filterNot { piece ->
            piece.inside == null && attached.any { stem -> connectedFlagFragment(strip, stem, piece) }
        }
    }

    private fun connectedFlagFragment(strip: Strip, stem: Stem, piece: Stem): Boolean {
        val g = strip.staff.gap
        if ((piece.x - stem.x) / g !in 0.27..1.03 || (stem.end - piece.end) / g !in 0.48..1.72 ||
            (piece.start - stem.start) / g !in -0.09..1.20) return false
        val margin = (g * 0.13).roundToInt().coerceAtLeast(1)
        val area = RasterBox(stem.x - margin, minOf(stem.start, piece.start), piece.x + margin + 1, stem.end + margin + 1)
        // A rightward flag can cast a second vertical silhouette. It must visibly rejoin the original stem.
        return strip.image.filter(area, 85).components(105).any { shape ->
            shape.left <= stem.x + 1 && shape.right > piece.x && shape.top <= piece.start + margin &&
                shape.bottom >= stem.end - margin
        }
    }

    private fun attachedStemInk(strip: Strip, stem: Stem): RasterBox? {
        val staff = strip.staff
        val g = staff.gap
        if (stem.start > staff.bottom + g * 0.49) return null
        val radius = (g * 0.12).roundToInt().coerceAtLeast(1)
        val span = stem.x - radius..stem.x + radius
        val sides = (g * 0.24).roundToInt().coerceAtLeast(radius + 1)
        var highest = stem.start
        for (y in stem.start downTo staff.top) {
            if (span.none { x -> strip.image[x, y] >= 75 }) {
                if (highest - y >= 2) break
                continue
            }
            val onString = staff.rows.any { abs(y - it) <= g * 0.11 }
            val widerInk = maxOf(strip.image[stem.x - sides, y], strip.image[stem.x + sides, y]) >= 105
            if (y < staff.bottom && !onString && widerInk) break
            highest = y
        }
        val rise = staff.bottom - highest
        if (rise < g * 0.22) return null
        if (rise < g * 0.50) {
            // A stem descending from the second-lowest string can cross only a short stretch above the bottom line.
            val aboveBottom = highest..(staff.bottom - g * 0.12).roundToInt()
            if (aboveBottom.count { y -> span.any { x -> strip.image[x, y] >= 90 } } < aboveBottom.count() * 0.82) return null
        }
        return RasterBox(span.first, highest, span.last + 1, stem.start + 1)
    }

    private fun hasShortStemDot(image: PdfRasterImage, x: Int, end: Int, g: Double): Boolean {
        val region = RasterBox((x + g * 0.17).roundToInt(), (end - g * 0.54).roundToInt(),
            (x + g * 0.78).roundToInt(), (end + g * 0.23).roundToInt())
        val ink = image.filter(region, 105)
        val dot = ink.bounds(105) ?: return false
        if (dot.left <= region.left || dot.right >= region.right || dot.top <= region.top || dot.bottom >= region.bottom ||
            dot.width / g !in 0.13..0.34 || dot.height / g !in 0.13..0.34 ||
            abs(dot.width - dot.height) > g * 0.09 || abs(dot.bottom - end) > g * 0.19) return false
        val fill = (dot.top until dot.bottom).sumOf { y -> (dot.left until dot.right).count { image[it, y] >= 105 } }
        return fill >= dot.width * dot.height * 0.65
    }

    private fun readRest(staff: RasterTabStaff, patch: RasterPatch, scanned: Boolean): Int? {
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
        if (scanned && pitch.value == "8" && pitch.distance <= 0.65 && pitch.separation >= 0.24 &&
            hasEighthRestTail(patch, box, g)) return 8
        if (pitch.distance > 0.54) return null
        return if (pitch.value == "1" || pitch.value == "2") {
            if (box.midY >= staff.rows.minBy { abs(it - box.midY) }) 1 else 2
        } else pitch.value.toIntOrNull()?.takeIf { pitch.separation > 0.035 }
    }

    private fun hasEighthRestTail(patch: RasterPatch, box: RasterBox, g: Double): Boolean {
        if (box.height / g !in 0.83..1.36 || box.width / g !in 0.56..0.93) return false
        val tailStart = box.top + (box.height * 0.54).roundToInt()
        val tailEnd = box.top + (box.height * 0.94).roundToInt()
        val tail = (tailStart..tailEnd).mapNotNull { y ->
            val ink = (box.left until box.right).filter { x ->
                patch.pixels[x - patch.box.left, y - patch.box.top] >= 105
            }
            ink.takeIf { it.isNotEmpty() && it.last() - it.first() + 1 <= g * 0.29 }?.average()
        }
        if (tail.size < (tailEnd - tailStart + 1) * 0.82) return false
        return tail.first() - tail.last() >= g * 0.09
    }

    private fun isOval(strip: Strip, box: RasterBox): Boolean {
        val g = strip.staff.gap
        if (box.height < g * 0.87 || box.width / g !in 0.72..3.5 || box.width / box.height.toDouble() > 1.6) return false
        return ovalSamples(strip, box) >= 33
    }

    private fun ovalSamples(strip: Strip, box: RasterBox): Int {
        var count = 0
        for (i in 0 until 40) {
            val theta = i * Math.PI / 20
            val x = (box.midX + (box.width - 1) * cos(theta) / 2).roundToInt()
            val y = (box.midY + (box.height - 1) * sin(theta) / 2).roundToInt()
            if (maxOf(strip.image[x, y], strip.image[x - 1, y], strip.image[x + 1, y], strip.image[x, y - 1], strip.image[x, y + 1]) > 80) count++
        }
        return count
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
        val match = patch.bounds()?.let { box -> glyphs.match(RasterAlphabet.FLAG, patch, box.height / g) }
        if (match != null && match.distance < 0.57 && match.separation > 0.025) return match.value.toInt()
        return if (!strip.outlined && hasScannedSingleFlag(image, stem, g)) 1 else 0
    }

    private fun hasScannedSingleFlag(image: PdfRasterImage, stem: Stem, g: Double): Boolean {
        val region = RasterBox((stem.x + g * 0.13).roundToInt(), stem.start,
            (stem.x + g * 0.56).roundToInt(), (stem.end + g * 0.20).roundToInt() + 1)
        val ink = image.filter(region, 105)
        val box = ink.bounds(105) ?: return false
        if ((box.left - stem.x) / g > 0.23 || (box.right - stem.x) / g !in 0.24..0.52 ||
            (stem.end - box.bottom) / g !in -0.09..0.30 || box.height / g !in 0.32..0.88) return false
        val centers = (box.left until box.right).mapNotNull { x ->
            val rows = (box.top until box.bottom).filter { y -> image[x, y] >= 105 }
            if (rows.isEmpty() || rows.last() - rows.first() + 1 > g * 0.51) null else rows.average()
        }
        return centers.size == box.width && centers.first() - centers.last() >= g * 0.21 &&
            centers.zipWithNext().all { (a, b) -> b - a <= g * 0.10 }
    }

    private fun applyTupletsAndDots(strip: Strip, onsets: List<Onset>, left: Int, right: Int, printedStyle: Boolean,
                                   issues: MutableSet<String>): List<PdfTabBeat> {
        if (onsets.isEmpty()) return emptyList()
        // The numeral is printed below the stem group. Require a consistent group of three onsets.
        val g = strip.staff.gap
        val bottom = strip.staff.bottom
        val low = (bottom + 1.35 * g).roundToInt()
        val high = (bottom + 4.0 * g).roundToInt()
        val triplets = HashSet<Int>()
        findPrintedTriplets(strip, onsets, left, right, printedStyle).forEach { triplets.addAll(it) }
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

    private fun findPrintedTriplets(strip: Strip, onsets: List<Onset>, left: Int, right: Int,
                                   printedStyle: Boolean): List<IntRange> {
        if (onsets.size < 3 || (strip.rhythm.isEmpty() && !printedStyle)) return emptyList()
        val g = strip.staff.gap
        val result = ArrayList<IntRange>()
        val marks = strip.rhythm.ifEmpty {
            val y = strip.staff.bottom
            val area = RasterBox(left, (y + g * 2.92).roundToInt(), right, (y + g * 4.85).roundToInt())
            strip.image.filter(area, 95).components(105).map { it.pdfBox() }
        }
        for (mark in marks.distinct()) {
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
            val raster = strip.rhythm.isEmpty()
            val patch = strip.image.filter(mark.rasterBox(if (raster) 0.0 else g * 0.035))
            val match = glyphs.match(RasterAlphabet.PRINT_TUPLET, patch, (mark.bottom - mark.top) / g) ?: continue
            if (match.value != "3" || match.distance > 0.43 || match.separation < 0.055) continue
            val start = (first.x + g * 0.12).roundToInt()
            val end = (last.x - g * 0.12).roundToInt()
            val before = start until (mark.left - g * 0.06).roundToInt()
            val after = (mark.right + g * 0.06).roundToInt()..end
            if (before.count() < g * 0.28 || after.count() < g * 0.28) continue
            val tolerance = if (raster) (g * 0.07).roundToInt().coerceAtLeast(1) else 0
            fun covers(columns: IntRange, y: Int) = columns.count { x ->
                (y - tolerance..y + tolerance).any { yy -> strip.image[x, yy] >= 95 }
            } >= columns.count() * 0.72
            val bracket = ((mark.top - g * 0.14).roundToInt()..(mark.bottom + g * 0.2).roundToInt()).any { y ->
                covers(before, y) && covers(after, y)
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
        val labels = ArrayList<RasterBox>()
        val letterGap = (g * 0.065).roundToInt().coerceAtLeast(1)
        fun readLabel(band: RasterPatch, pieces: List<RasterBox>, start: Int, isolated: Boolean = false): RasterBox? =
            pieces.subList(start, minOf(start + 6, pieces.size)).mapNotNull { end ->
                val window = RasterBox(pieces[start].left, band.box.top, end.right, band.box.bottom)
                if (window.width / g !in 1.65..3.40) return@mapNotNull null
                val patch = band.crop(window) ?: return@mapNotNull null
                val box = patch.bounds() ?: return@mapNotNull null
                if (box.height / g !in 0.51..1.05) return@mapNotNull null
                val match = glyphs.match(RasterAlphabet.TECHNIQUE, patch, box.height / g) ?: return@mapNotNull null
                val confident = match.distance <= 0.51 && match.separation >= 0.12 ||
                    isolated && match.distance <= 0.55 && match.separation >= 0.18
                if (match.value != "P.M." || !confident) return@mapNotNull null
                match.distance to box
            }.minByOrNull { it.first }?.second

        val pieces = zone.columns(letterGap, 150)
        for ((index, piece) in pieces.withIndex()) {
            if (labels.lastOrNull()?.let { piece.left < it.right } == true) continue
            readLabel(zone, pieces, index)?.let(labels::add)
        }
        if (!strip.outlined) {
            // Isolate a label vertically when a bar number or a previous system falls in the same columns.
            val padding = (g * 0.13).roundToInt().coerceAtLeast(1)
            for (letter in zone.components(105).sortedBy { it.left }) {
                if (letter.height / g !in 0.55..0.96 || letter.width / g !in 0.48..0.92 ||
                    labels.any { letter.left in it.left - padding until it.right }) continue
                val area = RasterBox(letter.left - padding, letter.top - padding,
                    letter.left + (g * 3.45).roundToInt(), letter.bottom + padding)
                val band = zone.crop(area) ?: continue
                val parts = band.columns(letterGap, 150)
                if (parts.isEmpty() || abs(parts.first().left - letter.left) > padding) continue
                val label = readLabel(band, parts, 0, isolated = true) ?: continue
                if (labels.none { abs(it.midX - label.midX) < g }) labels.add(label)
            }
        }
        return labels.sortedBy { it.left }.map { box ->
            val middle = box.midY.roundToInt()
            val at = ((box.right + g * 0.16).roundToInt()).coerceAtMost(right)
            val row = (middle - 3..middle + 3).maxBy { y -> (at until minOf(at + (g * 4).roundToInt(), right)).sumOf { strip.image[it, y] } }
            var last = box.right
            for (x in at until right) {
                if (strip.image[x, row] > 72) last = x
                if (x - last > g * 0.72) break
            }
            (box.midX - g / 2)..maxOf(box.midX + g / 2, last + g * 0.32)
        }
    }


    private class Strip(val image: PdfRasterImage, val staff: RasterTabStaff, val outlined: Boolean, val frets: PdfPrintFrets?,
                        val artwork: List<PdfBox>, val rhythm: List<PdfBox>, val rhythmEnd: Int) {
        private val longLines = HashMap<Int, List<IntRange>>()
        init {
            val minRun = ceil(staff.gap * 1.48).toInt()
            val shortRun = ceil(staff.gap * 0.54).toInt()
            val halfStroke = ceil(staff.gap * 0.085).toInt().coerceAtLeast(2)
            for (y in staff.rows.flatMap { row -> (row - halfStroke..row + halfStroke).toList() }.distinct()) {
                val runs = ArrayList<IntRange>()
                var start = -1
                var last = -1
                fun addRun() {
                    val length = last - start + 1
                    // A short stretch of string is also visible between a barline and its first fret.
                    if (length >= minRun || (!outlined && length >= shortRun &&
                        staff.edges.any { abs(it - start) <= 2 || abs(it - last) <= 2 })) runs.add(start..last)
                }
                for (x in staff.edges.first() until staff.edges.last()) {
                    if (image[x, y] > 27) { if (start < 0) start = x; last = x }
                    else if (start >= 0 && x - last > 1) {
                        addRun()
                        start = -1
                    }
                }
                if (start >= 0) addRun()
                if (runs.isNotEmpty()) longLines[y] = runs
            }
        }

        fun cleaned(box: RasterBox, omit: (Int, Int) -> Boolean = { _, _ -> false }) = image.filter(box) { x, y ->
            longLines[y]?.any { x in it } == true || omit(x, y)
        }
    }
}
