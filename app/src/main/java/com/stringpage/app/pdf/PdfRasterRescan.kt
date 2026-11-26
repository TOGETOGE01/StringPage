package com.stringpage.app.pdf

import kotlin.math.abs

/** A second raster size can separate symbols or recover a thin stroke lost to pixel rounding. */
internal object PdfRasterRescan {
    fun combine(original: PdfTabResult, rescans: List<PdfTabResult>, preceding: List<PdfTabNote>): PdfTabResult {
        val track = original.tracks.singleOrNull() ?: return original
        val alternatives = rescans.filter { aligned(original, it) }
        if (alternatives.isEmpty()) return original
        val bars = track.bars.toMutableList()
        for ((index, current) in track.bars.withIndex()) {
            if (current.issues.isEmpty()) continue
            val clean = alternatives.map { it.bars[index] }.filter { bar ->
                bar.issues.isEmpty() && bar.beats.isNotEmpty() && pdfBeatsFit(bar.beats, bar.meter)
            }
            val candidate = clean.groupBy { it.beats }.maxByOrNull { it.value.size }?.value?.firstOrNull() ?: continue
            if (current.beats.any { it.notes.isNotEmpty() } && candidate.beats.none { it.notes.isNotEmpty() }) continue
            val agreed = clean.count { it.beats == candidate.beats } >= 2
            if (!agreed && (clean.size > 1 || !keepsKnownNotes(current, candidate))) continue
            val incoming = if (index == 0) preceding else bars[index - 1].beats.lastOrNull()?.notes.orEmpty()
            if (!tiesMatch(incoming, candidate.beats.firstOrNull())) continue
            val next = bars.getOrNull(index + 1)
            if (next != null && !tiesMatch(candidate.beats.lastOrNull()?.notes.orEmpty(), next.beats.firstOrNull())) continue
            bars[index] = current.copy(beats = candidate.beats, issues = emptyList(), review = candidate.review)
        }
        return original.copy(tracks = listOf(track.copy(bars = bars)))
    }

    private fun aligned(original: PdfTabResult, candidate: PdfTabResult): Boolean {
        if (!original.scanned || !candidate.scanned || candidate.tracks.size != 1 ||
            candidate.tracks[0].strings != original.tracks[0].strings || original.bars.size != candidate.bars.size) return false
        fun close(a: Double, b: Double) = abs(a / original.pageWidth - b / candidate.pageWidth) < 0.006
        return original.bars.zip(candidate.bars).all { (a, b) ->
            a.page == b.page && a.meter == b.meter && close(a.bounds.left, b.bounds.left) &&
                close(a.bounds.right, b.bounds.right) && close(a.bounds.top, b.bounds.top)
        }
    }

    private fun keepsKnownNotes(current: PdfTabBar, candidate: PdfTabBar): Boolean {
        val known = current.beats.filter { it.notes.isNotEmpty() }
        if (known.isEmpty()) return false
        val options = candidate.beats.iterator()
        return known.all { beat ->
            options.asSequence().any { other ->
                other.notes.size >= beat.notes.size && beat.notes.all { expected ->
                    other.notes.any { it.string == expected.string && it.fret == expected.fret && it.dead == expected.dead }
                }
            }
        }
    }

    private fun tiesMatch(origin: List<PdfTabNote>, destination: PdfTabBeat?): Boolean =
        destination?.notes.orEmpty().all { note ->
            !note.tied || origin.any { !it.dead && it.string == note.string && it.fret == note.fret }
        }
}
