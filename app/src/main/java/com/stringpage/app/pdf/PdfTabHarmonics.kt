package com.stringpage.app.pdf

import kotlin.math.abs

internal enum class PdfHarmonicKind(val code: String) {
    NATURAL("NH"), ARTIFICIAL("AH"), PINCH("PH"), TAP("TH"), SEMI("SH"), FEEDBACK("FB");

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code.uppercase() }
    }
}

/** For a fretted harmonic, node is measured from the fretted note. */
internal data class PdfHarmonic(val kind: PdfHarmonicKind, val node: Double)

internal object PdfTabHarmonics {
    private data class Mark(val kind: PdfHarmonicKind, val x: Double, val y: Double, val end: Double?)

    fun apply(
        g: PdfGeometry, staff: List<Double>, gap: Double, anchors: List<PdfTabAnchor>,
        precedingNotes: List<PdfTabNote>, review: MutableSet<String>
    ) {
        val top = staff.first()
        val marks = g.text.mapNotNull { text ->
            if (text.y !in (top - gap * 6.5)..top || text.size !in (gap * 0.8)..(gap * 1.6)) return@mapNotNull null
            val label = text.text.filterNot { it == '.' || it.isWhitespace() }.uppercase()
            val kind = (if (label == "FDBK") PdfHarmonicKind.FEEDBACK else PdfHarmonicKind.fromCode(label))
                ?.takeUnless { it == PdfHarmonicKind.NATURAL } ?: return@mapNotNull null
            val end = g.dashes.filter {
                val left = minOf(it.from.x, it.to.x)
                left - text.right in (-gap * 0.08)..(gap * 0.85) &&
                    abs(it.from.y - it.to.y) < 0.08 && abs(it.from.y - text.y) < gap * 0.65
            }.maxOfOrNull { maxOf(it.from.x, it.to.x) }
            Mark(kind, (text.x + text.right) / 2, text.y, end)
        }
        if (marks.isEmpty()) return
        val ranges = HashMap<Mark, Set<Int>>()
        var previous = precedingNotes
        for ((stem, chord) in anchors.groupBy { it.stem }.entries.sortedBy { it.key.x }) {
            val direct = marks.filter { abs(it.x - stem.x) < gap * 0.65 }
            val eligible = chord.filter { !it.note.dead && it.note.harmonic?.kind != PdfHarmonicKind.NATURAL }
            val active = marks.filter {
                it !in direct && it.end != null && stem.x > it.x && stem.x <= it.end + gap * 0.15
            }
            val explicit = eligible.filter { it.note.harmonic != null }
            val candidates = explicit.ifEmpty { eligible }
            val bindings = active.associateWith { mark ->
                val strings = ranges[mark] ?: previous.filter { it.harmonic?.kind == mark.kind }.map { it.string }.toSet()
                candidates.filter { it.note.string in strings }
            }
            val continued = candidates.mapNotNull { note ->
                active.singleOrNull { mark -> bindings.getValue(mark).any { it === note } }?.let { note to it }
            }
            val available = eligible.filter { note -> continued.none { it.first === note } }
            val unresolved = active.filter { mark -> continued.none { it.second == mark } }
            val remaining = direct + unresolved
            val assignments = continued + assign(available, remaining)
            if (remaining.any { mark -> assignments.none { it.second == mark } }) {
                review.add("和弦中的泛音类型或弦号需核对")
            }
            for ((anchor, mark) in assignments) {
                val printed = anchor.note.harmonic
                if (printed == null) review.add("谱面未标泛音触弦位置，按高八度试听，请核对")
                anchor.note = anchor.note.copy(harmonic = PdfHarmonic(mark.kind, printed?.node ?: 12.0))
            }
            assignments.groupBy({ it.second }, { it.first.note.string }).forEach { (mark, strings) ->
                if (mark.end != null) ranges[mark] = strings.toSet()
            }
            previous = chord.map { it.note }
        }
    }

    private fun assign(chord: List<PdfTabAnchor>, marks: List<Mark>): List<Pair<PdfTabAnchor, Mark>> {
        if (marks.isEmpty()) return emptyList()
        val printed = chord.filter { it.note.harmonic != null }
        if (marks.size == 1) {
            return (printed.ifEmpty { chord.takeIf { it.size == 1 }.orEmpty() }).map { it to marks.single() }
        }
        val matching = printed.ifEmpty { chord }
        if (matching.size != marks.size) return emptyList()
        // At an onset with several new types, labels nearest the staff correspond to the highest marked string.
        return matching.sortedBy { it.line }.zip(marks.sortedByDescending { it.y })
    }
}
