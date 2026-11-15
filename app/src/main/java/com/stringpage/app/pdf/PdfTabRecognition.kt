package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.round

internal data class PdfTabResult(
    val bpm: Int?,
    val tracks: List<PdfTabTrack>,
    val pageWidth: Double,
    val scanned: Boolean = false,
    val meterNeedsConfirmation: Boolean = false
) {
    val bars get() = tracks.first().bars
    val standardTuning get() = tracks.first().tuning == PdfTabTuning.standard(6)
}

internal data class PdfTabTrack(val name: String, val strings: Int, val tuning: List<Int>?, val bars: List<PdfTabBar>)

internal data class PdfTabBar(
    val number: Int,
    val page: Int,
    val bounds: PdfBox,
    val beats: List<PdfTabBeat>,
    val issues: List<String>,
    val review: List<String>,
    val meter: PdfMeter = PdfMeter(4, 4),
    val bpm: Int? = null
)

internal data class PdfTabBeat(
    val denominator: Int,
    val notes: List<PdfTabNote>,
    val triplet: Boolean = false,
    val dots: Int = 0,
    val grace: PdfGrace? = null
)

internal data class PdfTabNote(
    val string: Int,
    val fret: Int,
    val dead: Boolean = false,
    val palmMute: Boolean = false,
    val harmonic: Double? = null,
    val artificialHarmonic: Boolean = false,
    val bend: PdfBend? = null,
    val tied: Boolean = false,
    val ghost: Boolean = false,
    val slideIn: PdfSlideIn? = null,
    val slideOut: PdfSlideOut? = null,
    val vibrato: PdfVibrato? = null,
    val hammerPull: Boolean = false,
    val letRing: Boolean = false
)

/** Geometry-based rules for vector six-string TAB produced with GPBravura. */
internal object PdfTabRecognition {
    private val rests = PdfNotationRhythm.rests
    private val flags = mapOf("\ue241" to 1, "\ue243" to 2, "\ue245" to 3, "\ue247" to 4)
    private val notePattern = Regex("""(\d{1,2})|<(\d{1,2}(?:\.\d+)?)>|([xX])|\((\d{1,2})\)""")
    private val harmonicPattern = Regex("""<(\d{1,2}(?:\.\d+)?)>""")
    private val tempoPattern = Regex("""=\s*(\d{2,3})(?!\d)""")
    private val doubleBars = setOf("\ue040", "\ue041", "\ue044", "\ue046")
    private data class Event(val x: Double, val beat: PdfTabBeat)

    fun read(document: VectorPdf, page: Int): PdfTabResult = Document(document).read(page)

    class Document(private val pdf: VectorPdf, private var firstPageGeometry: PdfGeometry? = null) {
        private val pages = ArrayList<PdfTabResult>()
        private var terminalMeter: PdfMeter? = null
        private var nextBar = 1
        private var currentTempo: Int? = null
        val pageCount get() = pdf.pageCount

        fun read(page: Int): PdfTabResult {
            pdfRequire(page in 1..pageCount, "请选择有效页码。")
            while (pages.size < page) pages.add(readPage(pages.size + 1))
            return pages[page - 1]
        }

        private fun readPage(page: Int): PdfTabResult {
            val geometry = firstPageGeometry?.also { firstPageGeometry = null } ?: pdf.geometry(page - 1)
            pdfRequire(!geometry.containsExternalDrawing, "这页含有当前无法识别的图像或绘图。")
            val formScale = geometry.normalizeFormScale()
            val systems = PdfTabLayout.systems(geometry)
            val first = systems.first()
            val trackDetails = pages.firstOrNull()?.tracks ?: PdfTabTuning.read(geometry, first)
            pdfRequire(systems.all { system -> system.map { it.tab.rows.size } == trackDetails.map { it.strings } },
                "PDF 的声部数量或弦数在页面中改变，无法对齐整首谱。")
            val firstTop = first.first().let { it.notation?.top ?: it.tab.top }
            fun tempo(span: PdfText): Int? = tempoPattern.find(span.text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 20..400 }
            val tempoMarks = geometry.text.filter { span -> tempo(span) != null && geometry.text.any {
                it.text in setOf("\ue1d5", "\ue1d7", "\ueca5", "\ueca7") && abs(span.x - it.right) < 9 && abs(span.y - it.y) < 1
            } }
            val startTempo = currentTempo ?: geometry.text.firstNotNullOfOrNull { span -> tempo(span).takeIf { span.y < firstTop } }
            var activeTempo = startTempo
            var meter = terminalMeter
            var barNumber = nextBar
            val recognized = trackDetails.map { ArrayList<PdfTabBar>() }
            for (system in systems) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val drawings = system.map { localTabGeometry(geometry, it.tab) }
                val mutes = system.zip(drawings) { staff, g -> palmMuteRanges(g, staff.tab.top, staff.tab.gap) }
                for ((column, measure) in system.first().edges.zipWithNext().withIndex()) {
                    val (left, right) = measure
                    val printed = system.flatMap { row -> listOfNotNull(
                        PdfTabLayout.printedMeter(geometry, row.tab, left, right),
                        row.notation?.let { PdfTabLayout.printedMeter(geometry, it, left, right) }
                    ) }.distinct()
                    pdfRequire(printed.size <= 1, "第 $barNumber 小节的声部拍号不一致。")
                    meter = printed.singleOrNull() ?: meter
                    val time = meter ?: throw PdfConversionException("未找到明确的起始拍号。")
                    val top = system.first().let { it.notation?.top ?: it.tab.top }
                    val mark = tempoMarks.filter { it.x in left..(right + 1) && it.y in (top - 33)..top }
                    val newTempo = mark.singleOrNull()?.let(::tempo)
                    if (newTempo != null) activeTempo = newTempo
                    for ((track, staff) in system.withIndex()) {
                        val previous = recognized[track].lastOrNull() ?: pages.lastOrNull()?.tracks?.get(track)?.bars?.lastOrNull()
                        val precedingNotes = previous?.beats?.lastOrNull()?.notes.orEmpty()
                        val bar = readBar(drawings[track], staff.tab.rows, staff.tab.gap, staff.edges[column], staff.edges[column + 1],
                            barNumber, page, mutes[track], time, newTempo, precedingNotes)
                        val notation = staff.notation
                        val timed = if (bar.issues.isNotEmpty() && notation != null) {
                            readWithStandardRhythm(geometry, drawings[track], staff.tab, notation, bar, mutes[track], precedingNotes)
                        } else null
                        val sustained = if (timed == null && previous != null && notation == null) {
                            readSustainedMeasure(drawings[track], staff.tab, bar, previous)
                        } else null
                        recognized[track].add(timed ?: sustained ?: if (system.size > 1 && isBlankStaffBar(geometry, staff, bar)) {
                            bar.copy(beats = listOf(PdfTabBeat(1, emptyList())), issues = emptyList())
                        } else bar)
                    }
                    barNumber++
                }
            }
            val tracks = trackDetails.zip(recognized) { detail, bars -> detail.copy(bars = bars) }
            pdfRequire(tracks.first().bars.isNotEmpty(), "没有找到完整的小节。")
            terminalMeter = meter
            nextBar = barNumber
            currentTempo = activeTempo
            return PdfTabResult(startTempo, tracks, pdf.pageWidth(page - 1) / formScale)
        }
    }

    private fun isBlankStaffBar(geometry: PdfGeometry, staff: PdfTabStaff, bar: PdfTabBar): Boolean {
        if (bar.beats.isNotEmpty() || bar.issues != listOf("音符与休止符不足或超过 ${bar.meter} 小节")) return false
        val top = staff.notation?.let { it.top - it.gap * 4 } ?: staff.tab.top - staff.tab.gap
        val bottom = staff.tab.bottom + staff.tab.gap * 0.7
        return geometry.text.none { span ->
            span.x > bar.bounds.left && span.x < bar.bounds.right && span.y in top..bottom &&
                (fretLabel(span, staff.tab.gap) || span.text.any { it in '\ue0a0'..'\ue0ff' || it in '\ue4e0'..'\ue50f' })
        }
    }

    private fun localTabGeometry(geometry: PdfGeometry, staff: PdfStaff): PdfGeometry {
        val top = staff.top
        val bottom = staff.bottom
        val gap = staff.gap
        return PdfGeometry().apply {
            lines.addAll(geometry.lines.filter { line -> line.from.y in (top - gap)..(bottom + gap * 5) })
            dashes.addAll(geometry.dashes.filter { line -> line.from.y in (top - gap * 6)..top })
            curves.addAll(geometry.curves.filter { curve -> curve.from.y in (top - gap * 7)..bottom ||
                curve.to.y in (top - gap * 7)..bottom })
            filledArcs.addAll(geometry.filledArcs.filter { it.from.y in (top - gap)..(bottom + gap) })
            closedCurves.addAll(geometry.closedCurves.filter { it.top >= top - gap && it.bottom <= bottom + gap })
            fills.addAll(geometry.fills.filter { box -> box.top in top..(bottom + gap * 5) })
            text.addAll(geometry.text.filter { it.y in (top - gap * 7)..(bottom + gap * 6) })
        }
    }

    private fun palmMuteRanges(g: PdfGeometry, top: Double, gap: Double): List<ClosedFloatingPointRange<Double>> =
        g.text.filter { it.text.trim().replace(" ", "") == "P.M." && it.y in (top - 5 * gap)..top }.mapNotNull { text ->
            val end = g.dashes.filter { line ->
                abs(line.from.y - line.to.y) < 0.1 && abs(line.from.y - text.y) < gap / 2 &&
                    line.from.x in text.right..(text.right + gap / 2) && line.from.x < line.to.x
            }
            val center = (text.x + text.right) / 2
            when (end.size) {
                0 -> (center - gap / 2)..(center + gap / 2)
                1 -> (center - gap / 2)..(end.single().to.x + gap / 3)
                else -> null
            }
        }

    private fun readBar(
        g: PdfGeometry, staff: List<Double>, gap: Double, left: Double, right: Double, number: Int, page: Int,
        muteRanges: List<ClosedFloatingPointRange<Double>>, meter: PdfMeter, bpm: Int?,
        precedingNotes: List<PdfTabNote>
    ): PdfTabBar {
        val top = staff.first()
        val bottom = staff.last()
        val spans = g.text.filter { it.x > left && it.x < right }
        val issues = LinkedHashSet<String>()
        val review = LinkedHashSet<String>()
        val stems = g.lines.mapNotNull { line ->
            val start = minOf(line.from.y, line.to.y)
            val end = maxOf(line.from.y, line.to.y)
            val x = line.from.x
            if (x <= left || x >= right || abs(x - line.to.x) >= 0.05 ||
                start !in (top - gap * 0.12)..(bottom + gap * 1.52) ||
                end !in (bottom + gap)..(bottom + gap * 4) || end - start < gap * 0.85) null
            else PdfTabStem(x, end, short = end - start <= gap * 1.1 && start >= bottom + gap * 0.85)
        }.distinct().sortedBy { it.x }
        val beams = g.fills.filter {
            it.right > left && it.left < right && it.top in (bottom + gap)..(bottom + gap * 4) &&
                it.bottom - it.top in (gap * 0.15)..(gap / 2) && it.right - it.left > gap * 0.38
        }
        val events = ArrayList<Event>()
        val anchors = ArrayList<PdfTabAnchor>()
        val ties = g.filledArcs.filter { abs(it.from.y - it.to.y) < gap * 0.08 }
            .map { if (it.from.x < it.to.x) it.from to it.to else it.to to it.from }
        val longNotes = g.closedCurves.filter { it.left > left && it.right < right &&
            it.right - it.left in (gap * 0.7)..(gap * 3.4) && it.bottom - it.top in (gap * 0.7)..(gap * 6) }
        for (span in spans) {
            val token = span.text.trim()
            if (span.y !in (top - gap * 0.07)..(bottom + gap * 0.5)) continue
            val rest = rests[token]
            if (rest != null) {
                events.add(Event((span.x + span.right) / 2, PdfTabBeat(rest, emptyList())))
                continue
            }
            if (token in doubleBars) issues.add("反复或结束线需校对")
            if (!fretLabel(span, gap)) continue
            val stringIndex = staff.indices.minBy { abs(staff[it] + gap * 0.367 - span.y) }
            val center = (span.x + span.right) / 2
            val circle = longNotes.singleOrNull { span.x >= it.left - gap * 0.06 && span.right <= it.right + gap * 0.06 &&
                staff[stringIndex] in it.top..it.bottom }
            val stem = stems.minByOrNull { abs(center - it.x) }?.takeIf { abs(center - it.x) <= gap / 2 }
                ?: circle?.let { PdfTabStem((it.left + it.right) / 2, null) }
            if (abs(staff[stringIndex] + gap * 0.367 - span.y) > gap * 0.105 ||
                stem == null || abs(center - stem.x) > gap / 2) {
                issues.add("品位或时值无法配对")
                continue
            }
            val note = readFret(span, staff, stringIndex, gap, spans, ties, muteRanges.any { stem.x in it })
            if (note == null) issues.add("品位超出支持范围或无法确认")
            else anchors.add(PdfTabAnchor(stem, span.right, staff[stringIndex], note, span.x))
        }
        for (stem in stems.filter { candidate -> anchors.none { it.stem == candidate } }) {
            for ((start, end) in ties.filter { (_, end) -> stem.x - end.x in (gap * 0.16)..(gap * 1.1) }) {
                val origin = anchors.filter {
                    val reachesFret = tieReachesAnchor(start, it, gap)
                    val reachesHarmonic = it.note.artificialHarmonic && spans.any { label ->
                        label.x - it.right in 0.0..gap && abs(start.x - label.right) < gap * 0.4 &&
                            abs(label.y - it.line) < gap / 2 && harmonicPattern.matches(label.text.trim())
                    }
                    (reachesFret || reachesHarmonic) && abs(it.line - start.y) < gap * 0.6
                }.singleOrNull() ?: continue
                anchors.add(PdfTabAnchor(stem, stem.x, origin.line, origin.note.copy(tied = true, ghost = false, bend = null)))
            }
        }
        PdfTabBends.apply(g, staff, gap, left..right, anchors, precedingNotes, issues, review)
        PdfTabTechniques.apply(g, spans, staff, gap, anchors, review)
        for ((stem, notes) in anchors.groupBy { it.stem }) {
            if (notes.map { it.note.string }.distinct().size != notes.size) issues.add("同一拍的弦号有冲突")
            val end = stem.end
            val circled = longNotes.any { stem.x in it.left..it.right && notes.all { note -> note.line in it.top..it.bottom } }
            if (end == null || circled || (stem.short && notes.all { it.note.tied })) {
                events.add(Event(stem.x, PdfTabBeat(if (end == null) 1 else 2, notes.map { it.note }.sortedBy { it.string })))
                continue
            }
            val counted = beams.count { stem.x in (it.left - gap * 0.06)..(it.right + gap * 0.06) &&
                it.bottom in (end - gap * 1.8)..(end + gap * 0.08) }
            val flag = if (counted == 0) spans.filter { flags.containsKey(it.text.trim()) &&
                abs(it.x - stem.x) < gap / 2 && abs(it.y - end) < gap * 2 }
                .maxOfOrNull { flags.getValue(it.text.trim()) } ?: 0 else counted
            if (flag > 4) issues.add("节奏线无法解析")
            events.add(Event(stem.x, PdfTabBeat(4 shl flag.coerceAtMost(4), notes.map { it.note }.sortedBy { it.string })))
        }
        val graceStems = addTabGrace(g, spans, staff, gap, stems, events, issues)
        val assigned = anchors.map { it.stem }.filter { it.end != null }.toSet() + graceStems
        if (stems.any { it !in assigned }) issues.add("存在未匹配的节奏符干")
        val sorted = events.sortedBy { it.x }
        val triplets = spans.filter { it.text.trim() == "3" && it.font.contains("Italic", true) &&
            it.y in (bottom + gap * 2)..(bottom + gap * 6) }
        val tripletMembers = HashSet<Int>()
        for (mark in triplets) {
            val center = (mark.x + mark.right) / 2
            val middle = sorted.indices.minByOrNull { abs(sorted[it].x - center) } ?: -1
            if (middle !in 1 until sorted.lastIndex || abs(sorted[middle].x - center) > gap ||
                sorted.subList(middle - 1, middle + 2).any { it.beat.grace != null } ||
                sorted.subList(middle - 1, middle + 2).map { it.beat.denominator }.distinct().size != 1) {
                issues.add("三连音范围待确认")
            } else {
                tripletMembers.addAll(listOf(middle - 1, middle, middle + 1))
            }
        }
        val dots = spans.filter { it.text.trim() == "\ue1e7" && it.y in (top - gap)..(bottom + gap * 4) }
        val dotsByBeat = HashMap<Int, Int>()
        for (dot in dots) {
            val previous = sorted.indexOfLast { it.x < dot.x }
            if (previous >= 0 && dot.x - sorted[previous].x <= gap * 1.5) {
                dotsByBeat[previous] = (dotsByBeat[previous] ?: 0) + 1
            } else issues.add("附点位置待确认")
        }
        val beats = sorted.mapIndexed { index, event ->
            event.beat.copy(triplet = index in tripletMembers, dots = (dotsByBeat[index] ?: 0).coerceAtMost(2))
        }
        if (!pdfBeatsFit(beats, meter)) issues.add("音符与休止符不足或超过 $meter 小节")
        return PdfTabBar(number, page, PdfBox(left, (top - gap * 4).coerceAtLeast(0.0), right, bottom + gap * 4),
            beats, issues.toList(), review.toList(), meter, bpm)
    }

    private fun tieReachesAnchor(start: PdfPoint, anchor: PdfTabAnchor, gap: Double): Boolean {
        val hiddenFret = anchor.note.tied && anchor.left == anchor.right
        val reach = gap * if (hiddenFret) 1.05 else 0.6
        return start.x - anchor.right in 0.0..reach
    }

    private fun addTabGrace(
        g: PdfGeometry, spans: List<PdfText>, staff: List<Double>, gap: Double, stems: List<PdfTabStem>,
        events: MutableList<Event>, issues: MutableSet<String>
    ): Set<PdfTabStem> {
        val usedStems = HashSet<PdfTabStem>()
        for (fretText in spans.filter { isSmallFret(it, staff, gap) }) {
            val x = (fretText.x + fretText.right) / 2
            val slashed = spans.any { it.text in setOf("\ue564", "\ue565") && abs(it.x - x) < gap * 0.65 }
            val strand = staff.indices.minBy { abs(staff[it] + gap * 0.275 - fretText.y) }
            val string = staff.size - strand
            val next = events.filter { it.beat.grace == null && it.x > x }.minByOrNull { it.x }
            val stem = stems.singleOrNull { abs(it.x - x) < gap * 0.32 }
            val tinyStem = g.lines.any {
                abs(it.from.x - x) < gap * 0.32 && abs(it.from.x - it.to.x) < 0.06 &&
                    abs(it.from.y - it.to.y) in gap * 0.45..gap * 2.3 && maxOf(it.from.y, it.to.y) >= staff.last()
            }
            if (next == null || next.x - x > gap * 3.7 || next.beat.notes.none { it.string == string } || (!slashed && !tinyStem)) {
                issues.add("装饰音的时机或指法待确认")
                continue
            }
            val note = PdfTabNote(string, fretText.text.trim().toInt())
            events.add(Event(x, PdfTabBeat(16, listOf(note), grace = if (slashed) PdfGrace.BEFORE_BEAT else PdfGrace.ON_BEAT)))
            if (stem != null) usedStems.add(stem)
        }
        return usedStems
    }

    private fun readSustainedMeasure(g: PdfGeometry, tab: PdfStaff, bar: PdfTabBar, previous: PdfTabBar): PdfTabBar? {
        if (bar.beats.isNotEmpty() || previous.issues.isNotEmpty() || bar.meter != PdfMeter(4, 4) ||
            bar.issues.any { it != "品位或时值无法配对" && it != "音符与休止符不足或超过 ${bar.meter} 小节" }) return null
        val prior = previous.beats.lastOrNull()?.notes?.takeIf { it.isNotEmpty() && it.none(PdfTabNote::dead) } ?: return null
        val spans = g.text.filter { it.x in bar.bounds.left..bar.bounds.right && it.y in tab.top..(tab.bottom + tab.gap * 0.5) }
        val frets = spans.filter { fretLabel(it, tab.gap) }
        if (frets.size != prior.size || frets.any { !it.text.trim().startsWith('(') || it.x > bar.bounds.left + tab.gap * 4 }) return null
        val strokes = g.text.filter { '\ueab2' in it.text && it.y in (tab.top - tab.gap * 6)..tab.top }
        if (strokes.none { it.x in (bar.bounds.left - tab.gap)..(bar.bounds.left + tab.gap * 4) } ||
            strokes.none { it.right >= bar.bounds.right - tab.gap * 2 && it.x < bar.bounds.right }) return null
        val candidate = frets.map { span ->
            val string = tab.rows.indices.minBy { abs(tab.rows[it] + tab.gap * 0.367 - span.y) }
            val fret = span.text.trim().removeSurrounding("(", ")").toIntOrNull() ?: return null
            prior.singleOrNull { it.string == tab.rows.size - string && it.fret == fret } ?: return null
        }
        if (candidate.distinctBy { it.string }.size != prior.size) return null
        val continued = candidate.map { it.copy(tied = true, ghost = false, bend = it.bend?.end?.takeIf { level -> level > 0 }?.let(PdfBend::hold),
            slideIn = null, slideOut = null, hammerPull = false) }
        return bar.copy(beats = listOf(PdfTabBeat(1, continued)), issues = emptyList(),
            review = (bar.review + "跨小节延音的时值按小节长度还原").distinct())
    }

    private fun readWithStandardRhythm(
        g: PdfGeometry, tabGeometry: PdfGeometry, tab: PdfStaff, notation: PdfStaff, bar: PdfTabBar,
        muteRanges: List<ClosedFloatingPointRange<Double>>, precedingNotes: List<PdfTabNote>
    ): PdfTabBar? {
        val spans = tabGeometry.text.filter { it.x > bar.bounds.left && it.x < bar.bounds.right }
        val smallFrets = spans.filter { isSmallFret(it, tab.rows, tab.gap) }
        val bendArrows = spans.filter { PdfTabBends.isArrow(it, tab.rows, tab.gap) }.map { (it.x + it.right) / 2 }
        val rhythm = PdfNotationRhythm.read(g, notation, bar, smallFrets.map { (it.x + it.right) / 2 }, bendArrows) ?: return null
        val ties = tabGeometry.filledArcs.filter { abs(it.from.y - it.to.y) < tab.gap * 0.08 }
            .map { if (it.from.x < it.to.x) it.from to it.to else it.to to it.from }
        val anchors = ArrayList<PdfTabAnchor>()
        for (span in spans.filter { (fretLabel(it, tab.gap) && it.y in tab.top..(tab.bottom + tab.gap * 0.5)) || it in smallFrets }) {
            val small = span in smallFrets
            val offset = tab.gap * if (small) 0.275 else 0.367
            val string = tab.rows.indices.minBy { abs(tab.rows[it] + offset - span.y) }
            if (abs(tab.rows[string] + offset - span.y) > tab.gap * 0.105) return null
            val center = (span.x + span.right) / 2
            val onset = rhythm.filter { (it.grace != null) == small }.minByOrNull { abs(it.x - center) } ?: return null
            if (onset.rest || abs(onset.x - center) > tab.gap * 0.9) return null
            val note = readFret(span, tab.rows, string, tab.gap, spans, ties, muteRanges.any { onset.x in it }) ?: return null
            anchors.add(PdfTabAnchor(PdfTabStem(onset.x, null), span.right, tab.rows[string], note, span.x))
        }
        for ((index, event) in rhythm.withIndex()) {
            if (event.rest || anchors.any { it.stem.x == event.x }) continue
            for ((start, end) in ties.filter { (_, end) -> event.x - end.x in (tab.gap * 0.16)..(tab.gap * 1.1) }) {
                val origin = anchors.filter { tieReachesAnchor(start, it, tab.gap) &&
                    abs(it.line - start.y) < tab.gap * 0.6 }.singleOrNull() ?: continue
                anchors.add(PdfTabAnchor(PdfTabStem(event.x, null), event.x, origin.line, origin.note.copy(tied = true, ghost = false, bend = null)))
            }
            if (anchors.none { it.stem.x == event.x } && event.tiesFromPrevious > 0) {
                val previous = rhythm.getOrNull(index - 1)?.let { prior -> anchors.filter { it.stem.x == prior.x } }.orEmpty()
                if (previous.size == event.tiesFromPrevious) for (origin in previous) {
                    anchors.add(PdfTabAnchor(PdfTabStem(event.x, null), event.x, origin.line, origin.note.copy(tied = true, ghost = false, bend = null)))
                }
            }
        }
        val issues = linkedSetOf<String>()
        val review = linkedSetOf<String>()
        PdfTabBends.apply(tabGeometry, tab.rows, tab.gap, bar.bounds.left..bar.bounds.right, anchors, precedingNotes, issues, review)
        PdfTabTechniques.apply(tabGeometry, spans, tab.rows, tab.gap, anchors, review)
        if (bar.issues.any { it == "反复或结束线需校对" }) issues.add("反复或结束线需校对")
        val byOnset = anchors.groupBy { it.stem.x }
        val beats = rhythm.map { event ->
            val notes = byOnset[event.x].orEmpty().map { it.note }.sortedBy { it.string }
            if (event.rest != notes.isEmpty() || notes.distinctBy { it.string }.size != notes.size) return null
            PdfTabBeat(event.denominator, notes, event.triplet, event.dots, event.grace)
        }
        return bar.copy(beats = beats, issues = issues.toList(), review = review.toList())
            .takeIf { pdfBeatsFit(it.beats, it.meter) }
    }


    private fun fretLabel(span: PdfText, gap: Double) =
        span.font.startsWith("Arial", true) && span.size in (gap * 1.02)..(gap * 1.17)

    private fun isSmallFret(span: PdfText, rows: List<Double>, gap: Double) =
        span.font.startsWith("Arial", true) && span.size in (gap * 0.64)..(gap * 0.96) &&
            span.text.trim().toIntOrNull()?.let { it in 0..36 } == true && rows.any { abs(it + gap * 0.275 - span.y) < gap * 0.105 }

    private fun readFret(
        span: PdfText, staff: List<Double>, stringIndex: Int, gap: Double, spans: List<PdfText>,
        ties: List<Pair<PdfPoint, PdfPoint>>, muted: Boolean
    ): PdfTabNote? {
        val match = notePattern.matchEntire(span.text.trim()) ?: return null
        val harmonic = match.groups[2]?.value?.toDoubleOrNull()
        val parenthesized = match.groups[4]?.value?.toIntOrNull()
        val fret = match.groups[1]?.value?.toIntOrNull() ?: harmonic?.toInt() ?: parenthesized ?: 0
        if (fret !in 0..36) return null
        val artificial = if (harmonic == null) spans.asSequence().filter {
            it.font.startsWith("Arial", true) && it.size < gap && it.x - span.right in 0.01..gap &&
                abs(it.y - staff[stringIndex]) < gap / 2
        }.mapNotNull { harmonicPattern.matchEntire(it.text.trim())?.groupValues?.get(1)?.toDoubleOrNull() }.singleOrNull() else null
        val tied = parenthesized != null && ties.any { (_, end) -> span.x - end.x in (-gap * 0.1)..(gap * 0.55) &&
            abs(end.y - staff[stringIndex]) < gap * 0.58 }
        return PdfTabNote(staff.size - stringIndex, fret, match.groups[3] != null, muted,
            harmonic ?: artificial?.let { round((it - fret) * 100) / 100 }, artificial != null,
            tied = tied, ghost = parenthesized != null && !tied)
    }
}
