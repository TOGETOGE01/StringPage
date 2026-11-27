package com.stringpage.app.pdf

import kotlin.math.abs

/** A second raster size can separate symbols or recover a thin stroke lost to pixel rounding. */
internal object PdfRasterRescan {
    fun combine(original: PdfTabResult, rescans: List<PdfTabResult>, preceding: List<PdfTabNote>,
                requireConsensus: Boolean = false): PdfTabResult {
        val track = original.tracks.singleOrNull() ?: return original
        if (!original.scanned || rescans.isEmpty()) return original
        val alternatives = rescans.filter {
            it.scanned && it.pageWidth > 0 && it.tracks.singleOrNull()?.strings == track.strings
        }
        val source = if (requireConsensus && alternatives.size >= 2) {
            recoverMissingBoundaries(track.bars, original.pageWidth, alternatives, preceding)
        } else track.bars
        val aligned = alternatives.mapNotNull { candidate ->
            val matching = if (requireConsensus) {
                source.map { current ->
                    candidate.bars.singleOrNull { aligned(current, it, original.pageWidth, candidate.pageWidth) }
                }
            } else {
                if (track.bars.size != candidate.bars.size || !track.bars.zip(candidate.bars).all { (a, b) ->
                    aligned(a, b, original.pageWidth, candidate.pageWidth)
                }) return@mapNotNull null
                candidate.bars
            }
            // A neighboring segmentation cannot supply more than one original measure.
            val duplicates = matching.filterNotNull().groupingBy { it.number }.eachCount()
            matching.map { bar -> bar?.takeIf { duplicates[it.number] == 1 } }
        }
        if (aligned.isEmpty()) return original
        val bars = source.toMutableList()
        for ((index, current) in source.withIndex()) {
            if (current.issues.isEmpty()) continue
            val clean = aligned.mapNotNull { it[index] }.filter(::readable)
            val candidate = clean.groupBy { it.beats }.maxByOrNull { it.value.size }?.value?.firstOrNull() ?: continue
            val known = current.beats.any { it.notes.isNotEmpty() }
            val sounding = candidate.beats.any { it.notes.isNotEmpty() }
            if (known && !sounding) continue
            val votes = clean.count { it.beats == candidate.beats }
            if (requireConsensus) {
                if (!sounding || votes < 2 || votes * 2 <= clean.size || (known && !keepsKnownNotes(current.beats, candidate.beats))) continue
            } else if (votes < 2 && (clean.size > 1 || !keepsKnownNotes(current.beats, candidate.beats))) continue
            val incoming = if (index == 0) preceding else bars[index - 1].beats.lastOrNull()?.notes.orEmpty()
            if (!tiesMatch(incoming, candidate.beats.firstOrNull())) continue
            val next = bars.getOrNull(index + 1)
            if (next != null && !tiesMatch(candidate.beats.lastOrNull()?.notes.orEmpty(), next.beats.firstOrNull())) continue
            bars[index] = current.copy(beats = candidate.beats, issues = emptyList(), review = candidate.review)
        }
        return original.copy(tracks = listOf(track.copy(bars = bars)))
    }

    private fun recoverMissingBoundaries(original: List<PdfTabBar>, width: Double, rescans: List<PdfTabResult>,
                                         preceding: List<PdfTabNote>): List<PdfTabBar> {
        val bars = ArrayList<PdfTabBar>()
        for ((index, current) in original.withIndex()) {
            if (current.issues.isEmpty()) {
                bars.add(current)
                continue
            }
            val partitions = rescans.mapNotNull { partition(current, width, it) }
            val complete = partitions.filter { it.all(::readable) }
            val incoming = if (index == 0) preceding else bars.last().beats.lastOrNull()?.notes.orEmpty()
            val next = original.getOrNull(index + 1)?.beats?.firstOrNull()
            val known = current.beats.any { it.notes.isNotEmpty() }
            val candidate = complete.firstOrNull { pieces ->
                val agree = partitions.filter { variant ->
                    variant.size == pieces.size && pieces.zip(variant).all { (a, b) -> aligned(a, b, width, width) }
                }
                if (agree.size < 2 || agree.size * 2 <= rescans.size) return@firstOrNull false
                val clean = agree.filter { it.all(::readable) }
                val votes = clean.count { it.map { bar -> bar.beats } == pieces.map { bar -> bar.beats } }
                if (votes * 2 <= clean.size || (!known && votes < 2)) return@firstOrNull false
                if (known && !keepsKnownNotes(current.beats, pieces.flatMap { it.beats })) return@firstOrNull false
                tiesMatch(incoming, pieces.first().beats.firstOrNull()) &&
                    tiesMatch(pieces.last().beats.lastOrNull()?.notes.orEmpty(), next) &&
                    pieces.zipWithNext().all { (a, b) -> tiesMatch(a.beats.lastOrNull()?.notes.orEmpty(), b.beats.firstOrNull()) }
            }
            if (candidate == null) bars.add(current) else bars.addAll(candidate)
        }
        if (bars.size == original.size) return original
        return bars.mapIndexed { index, bar -> bar.copy(number = original.first().number + index) }
    }

    private fun partition(current: PdfTabBar, width: Double, candidate: PdfTabResult): List<PdfTabBar>? {
        val scale = width / candidate.pageWidth
        val tolerance = width * 0.006
        val frame = current.bounds
        val pieces = candidate.bars.mapNotNull { bar ->
            val b = bar.bounds
            val scaled = PdfBox(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale)
            if (bar.page != current.page || bar.meter != current.meter ||
                abs(scaled.top - frame.top) >= tolerance || abs(scaled.bottom - frame.bottom) >= tolerance ||
                scaled.left < frame.left - tolerance || scaled.right > frame.right + tolerance) null
            else bar.copy(bounds = scaled.copy(top = frame.top, bottom = frame.bottom),
                provisionalMeter = current.provisionalMeter)
        }
        if (pieces.size < 2 || abs(pieces.first().bounds.left - frame.left) >= tolerance ||
            abs(pieces.last().bounds.right - frame.right) >= tolerance ||
            pieces.zipWithNext().any { (a, b) -> abs(a.bounds.right - b.bounds.left) >= tolerance }) return null
        return pieces.mapIndexed { i, bar ->
            val left = if (i == 0) frame.left else pieces[i - 1].bounds.right
            val right = if (i == pieces.lastIndex) frame.right else bar.bounds.right
            bar.copy(bounds = bar.bounds.copy(left = left, right = right))
        }
    }

    private fun readable(bar: PdfTabBar): Boolean =
        bar.issues.isEmpty() && bar.beats.isNotEmpty() && pdfBeatsFit(bar.beats, bar.meter)

    private fun aligned(a: PdfTabBar, b: PdfTabBar, originalWidth: Double, candidateWidth: Double): Boolean {
        fun close(x: Double, y: Double) = abs(x / originalWidth - y / candidateWidth) < 0.006
        return a.page == b.page && a.meter == b.meter && close(a.bounds.left, b.bounds.left) &&
            close(a.bounds.right, b.bounds.right) && close(a.bounds.top, b.bounds.top)
    }

    private fun keepsKnownNotes(current: List<PdfTabBeat>, candidate: List<PdfTabBeat>): Boolean {
        val known = current.filter { it.notes.isNotEmpty() }
        if (known.isEmpty()) return false
        val options = candidate.iterator()
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
