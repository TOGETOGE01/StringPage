package com.stringpage.app.pdf

import kotlin.math.abs

internal fun pdfBeatsFit(beats: List<PdfTabBeat>, meter: PdfMeter): Boolean {
    val single = beats.singleOrNull { it.grace == null }
    if (single != null && single.notes.isEmpty() && single.denominator == 1 && single.dots == 0 && !single.triplet) return true
    return abs(pdfBeatLength(beats) - meter.length) < 0.0001
}

internal fun pdfBeatLength(beats: List<PdfTabBeat>) = beats.sumOf { beat ->
    if (beat.grace != null) 0.0
    else (2.0 - 1.0 / (1 shl beat.dots)) / beat.denominator * if (beat.triplet) 2.0 / 3 else 1.0
}

internal fun pdfTrailingRests(beats: List<PdfTabBeat>, meter: PdfMeter): List<PdfTabBeat> {
    if (beats.isEmpty() || beats.last().grace != null || pdfBeatsFit(beats, meter)) return emptyList()
    var remaining = meter.length - pdfBeatLength(beats)
    if (remaining <= 0.0) return emptyList()
    val rests = ArrayList<PdfTabBeat>()
    for (denominator in listOf(1, 2, 4, 8, 16, 32, 64)) {
        val count = ((remaining + 0.0001) * denominator).toInt()
        if (beats.size + rests.size + count > 64) return emptyList()
        repeat(count) { rests.add(PdfTabBeat(denominator, emptyList())) }
        remaining -= count.toDouble() / denominator
    }
    return rests.takeIf { pdfBeatsFit(beats + it, meter) }.orEmpty()
}

internal fun pdfLongestReadyRange(bars: List<PdfTabBar>): IntRange? {
    var best: IntRange? = null
    var start = 0
    for (index in bars.indices) {
        if (bars[index].issues.isNotEmpty()) start = index + 1
        else if (index - start + 1 > (best?.count() ?: 0)) best = start..index
    }
    return best
}

/** A compact textual form for correcting the rhythm and fingering of one recognized measure. */
internal object PdfTabCorrection {
    private val pitch = """-?\d(?:\.(?:25|5|75))?"""
    private val duration = Regex("""(1|2|4|8|16|32|64)(\.{0,2})(t?)(g!|g)?(?:r(8|16|32))?(?:w($pitch(?:>$pitch){1,2}))?""")
    private val tremoloDivisions = listOf(8, 16, 32)
    private val fingering = Regex(
        """([1-9])-(\d{1,2}|X)(m?)(?:\^([0-6](?:\.5)?(?:>[0-6](?:\.5)?){0,2}))?(?:@((?:NH|AH|PH|TH|SH|FB)\d{1,2}(?:\.\d{1,2})?|0))?""",
        RegexOption.IGNORE_CASE
    )

    fun format(beats: List<PdfTabBeat>, strings: Int): String = beats.joinToString("\n") { beat ->
        val value = buildString {
            append(beat.denominator)
            repeat(beat.dots) { append('.') }
            if (beat.triplet) append('t')
            if (beat.grace != null) append(if (beat.grace == PdfGrace.ON_BEAT) "g!" else "g")
            beat.tremoloMarks?.let { append('r').append(tremoloDivisions[it - 1]) }
            beat.whammy?.let {
                append('w')
                append(listOfNotNull(it.start, it.middle, it.end).joinToString(">") { value ->
                    (value / 4.0).toString().removeSuffix(".0")
                })
            }
        }
        val tokens = beat.notes.sortedByDescending { it.string }.joinToString(" ") { note ->
            buildString {
                append(strings - note.string + 1); append('-'); append(if (note.dead) "X" else note.fret.toString())
                if (note.palmMute) append('m')
                note.bend?.let { bend ->
                    append('^')
                    val values = if (bend.start == 0 && bend.middle == null) listOf(bend.end) else bend.levels
                    append(values.joinToString(">") { if (it % 2 == 0) (it / 2).toString() else (it / 2.0).toString() })
                }
                note.harmonic?.let {
                    append('@')
                    append(it.kind.code)
                    append(it.node.toString().removeSuffix(".0"))
                }
            }
        }
        "$value ${tokens.ifEmpty { "R" }}"
    }

    fun correct(original: PdfTabBar, strings: Int, text: String): PdfTabBar {
        val beats = parseBeats(original, strings, text)
        pdfRequire(pdfBeatsFit(beats, original.meter), "音符与休止符的时值需要填满 ${original.meter} 小节。")
        return original.copy(beats = beats, issues = emptyList(), review = emptyList())
    }

    fun parseBeats(original: PdfTabBar, strings: Int, text: String): List<PdfTabBeat> {
        val rows = text.lineSequence().map(String::trim).filter(String::isNotEmpty).take(65).toList()
        pdfRequire(rows.size in 1..64, "请填写 1 到 64 个音符组或休止符。")
        return rows.mapIndexed { index, line ->
            val fail = "第 ${index + 1} 行的时值或指法有误。"
            val cells = line.split(Regex("\\s+")).take(strings + 2)
            val length = duration.matchEntire(cells.first()) ?: throw PdfConversionException(fail)
            pdfRequire(cells.size in 2..strings + 1, fail)
            val grace = when (length.groupValues[4]) { "g" -> PdfGrace.BEFORE_BEAT; "g!" -> PdfGrace.ON_BEAT; else -> null }
            val notes = if (cells.size == 2 && cells[1].equals("R", true)) emptyList() else cells.drop(1).map { token ->
                val match = fingering.matchEntire(token) ?: throw PdfConversionException(fail)
                val conventionalString = match.groupValues[1].toInt()
                val dead = match.groupValues[2].equals("X", true)
                val fret = if (dead) 0 else match.groupValues[2].toInt()
                val muted = match.groupValues[3].isNotEmpty()
                val values = match.groupValues[4].takeIf(String::isNotEmpty)?.split('>')
                    ?.map { (it.toDouble() * 2).toInt() }.orEmpty()
                pdfRequire(conventionalString in 1..strings && fret in 0..36 && (values.isEmpty() ||
                    !dead && values.any { it > 0 } && values.all { it in 0..PdfBend.MAX_PITCH }), fail)
                val string = strings - conventionalString + 1
                val prior = original.beats.getOrNull(index)?.notes?.singleOrNull { it.string == string && it.fret == fret && it.dead == dead }
                val harmonic = when (val mark = match.groupValues[5]) {
                    "" -> prior?.harmonic
                    "0" -> null
                    else -> {
                        val kind = PdfHarmonicKind.fromCode(mark.take(2)) ?: throw PdfConversionException(fail)
                        val node = mark.drop(2).toDouble()
                        pdfRequire(!dead && node > 0 && node <= 36, fail)
                        PdfHarmonic(kind, node)
                    }
                }
                val bend = when (values.size) {
                    1 -> prior?.bend?.takeIf { it.peak == values[0] } ?: PdfBend.up(values[0])
                    2 -> PdfBend(values[0], values[1])
                    3 -> PdfBend(values[0], values[2], values[1])
                    else -> null
                }
                (prior ?: PdfTabNote(string, fret, dead)).copy(palmMute = muted, bend = bend, harmonic = harmonic)
            }
            pdfRequire(notes.distinctBy { it.string }.size == notes.size, "第 ${index + 1} 行的一根弦上有多个音。")
            pdfRequire(grace == null || notes.isNotEmpty() && index < rows.lastIndex, "装饰音需接到后面的主音。")
            val tremolo = length.groupValues[5].toIntOrNull()?.let { tremoloDivisions.indexOf(it) + 1 }
            pdfRequire(tremolo == null || notes.isNotEmpty() && grace == null, "快速拨弦只能加在主音或和弦上。")
            val points = length.groupValues[6].takeIf(String::isNotEmpty)?.split('>')
                ?.map { (it.toDouble() * 4).toInt() }.orEmpty()
            val whammy = points.takeIf { it.isNotEmpty() }?.let {
                pdfRequire(notes.any { !it.dead } && grace == null && it.any { point -> point != 0 } &&
                    it.all { point -> point in -24..24 }, "颤音杆需加在主音或和弦上，音高偏移范围为 −6 到 6 个全音。")
                PdfWhammy(it.first(), it.last(), if (it.size == 3) it[1] else null)
            }
            PdfTabBeat(length.groupValues[1].toInt(), notes.sortedBy { it.string },
                length.groupValues[3].isNotEmpty(), length.groupValues[2].length, grace, tremolo, whammy)
        }
    }
}
