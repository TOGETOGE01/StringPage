@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.model.Lyrics
import alphaTab.model.Score
import alphaTab.model.Track
import com.stringpage.app.rse.GpRseProperties

internal object ScoreLyrics {
    /**
     * alphaTab 1.8.4 skips GPIF track-level lyrics for the entire score as soon as
     * it encounters any beat-level lyrics. Restore missing verses on each track.
     */
    fun restore(score: Score, properties: GpRseProperties.Properties) {
        for ((index, track) in score.tracks.withIndex()) {
            val source = properties.track(index)?.lyrics ?: continue
            if (source.dispatched && hasLyrics(track)) continue
            val staff = track.staves.firstOrNull() ?: continue
            for ((verse, line) in source.lines.withIndex()) {
                if (line.text.isBlank() || line.startBar !in 0 until staff.bars.length.toInt()) continue
                val lyrics = Lyrics().apply { text = line.text; finish() }
                var syllable = 0
                for (barIndex in line.startBar until staff.bars.length.toInt()) {
                    if (syllable >= lyrics.chunks.length) break
                    val voice = staff.bars[barIndex].voices.firstOrNull() ?: continue
                    for (beat in voice.beats) {
                        if (syllable >= lyrics.chunks.length) break
                        if (beat.isRest || beat.isEmpty) continue
                        val text = lyrics.chunks[syllable++]
                        if (text.isBlank()) continue
                        val lines = beat.lyrics ?: alphaTab.collections.List<String>().also { beat.lyrics = it }
                        while (lines.length <= verse) lines.push("")
                        if (lines[verse].isBlank()) lines[verse] = text
                    }
                }
            }
        }
    }

    fun trackIndexes(score: Score): Set<Int> = buildSet {
        for ((index, track) in score.tracks.withIndex()) if (hasLyrics(track)) add(index)
    }

    private fun hasLyrics(track: Track): Boolean {
        for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) for (beat in voice.beats) {
            if (beat.lyrics?.any { it.isNotBlank() } == true) return true
        }
        return false
    }
}
