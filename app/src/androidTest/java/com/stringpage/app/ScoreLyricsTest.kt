@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.StaveProfile
import alphaTab.collections.DoubleList
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Duration
import alphaTab.model.Score
import alphaTab.rendering.ScoreRenderer
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.GpRseProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ScoreLyricsTest {
    @Test fun mixedGpifLyricsKeepBothTracksAndRenderInEveryScoreView() {
        val original = mixedLyricsGp()
        val score = read(original)
        assertEquals(setOf(0), ScoreLyrics.trackIndexes(score))
        ScoreLyrics.restore(score, GpRseProperties.read(original))
        assertEquals(setOf(0, 1), ScoreLyrics.trackIndexes(score))
        assertEquals(listOf("Inline lyric"), lyrics(score, 0, 0, 0))
        assertTrue(lyrics(score, 1, 0, 0).isEmpty())
        assertTrue(lyrics(score, 1, 1, 0).isEmpty())
        assertEquals(listOf("星光", "朝日"), lyrics(score, 1, 1, 1))
        assertEquals(listOf("ささやく", "晨风"), lyrics(score, 1, 1, 2))
        val roundTrip = read(ScoreExport.guitarProBytes(score))
        assertEquals(lyrics(score, 1, 1, 1), lyrics(roundTrip, 1, 1, 1))
        for (profile in listOf(StaveProfile.ScoreTab, StaveProfile.Score, StaveProfile.Tab)) {
            val svg = render(score, profile)
            assertTrue("Lyrics missing in $profile", "星光" in svg && "朝日" in svg && "ささやく" in svg)
            assertEquals("Lyrics duplicated in $profile", 1, "星光".toRegex().findAll(svg).count())
        }
    }

    private fun render(score: Score, profile: StaveProfile): String {
        val settings = Settings().apply {
            core.engine = "svg"
            core.enableLazyLoading = false
            display.staveProfile = profile
        }
        val renderer = ScoreRenderer(settings)
        try {
            val text = StringBuilder()
            renderer.width = 900.0
            renderer.error.on { throw AssertionError("Score render failed", it) }
            renderer.partialRenderFinished.on { e -> text.append(e.renderResult) }
            renderer.renderScore(score, DoubleList().apply { push(1.0) }, null)
            return text.toString()
        } finally { renderer.destroy() }
    }

    private fun lyrics(score: Score, track: Int, bar: Int, beat: Int) =
        score.tracks[track].staves[0].bars[bar].voices[0].beats[beat].lyrics?.toList().orEmpty()

    private fun read(bytes: ByteArray) = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())

    private fun mixedLyricsGp(): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var score = ScoreStructure.newScore("歌词显示测试", ScoreStructure.Instrument.STEEL, 100, Settings())
        val editor = BasicScoreEditor(context, { score }, { next, _, _ -> score = next })
        assertTrue(editor.addTrack(ScoreStructure.Instrument.NYLON))
        assertTrue(editor.insertBar(1))
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(0, 0, 0, 0, 0), 1, 5))
        assertTrue(editor.updateDuration(BasicScoreEditor.BeatAddress(1, 0, 1, 0, 0), Duration.Quarter))
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(1, 0, 1, 0, 1), 1, 7))
        assertTrue(editor.putFret(BasicScoreEditor.BeatAddress(1, 0, 1, 0, 2), 1, 9))
        score.tracks[0].staves[0].bars[0].voices[0].beats[0].lyrics =
            alphaTab.collections.List<String>("Inline lyric")
        val original = ScoreExport.guitarProBytes(score)
        return ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { output ->
                ZipInputStream(ByteArrayInputStream(original)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val bytes = input.readBytes()
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name.endsWith("score.gpif")) output.write(addTrackLyrics(bytes.toString(Charsets.UTF_8)))
                        else output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
        }.toByteArray()
    }

    private fun addTrackLyrics(xml: String): ByteArray {
        val start = xml.indexOf("""<Track id="1">""")
        require(start >= 0)
        val end = xml.indexOf("</Track>", start)
        var track = xml.substring(start, end)
        track = track.replace(Regex("""<Lyrics[^>]*(?:/>|>[sS]*?</Lyrics>)"""), "")
        val lyrics = """<Lyrics dispatched="false"><Line><Offset>1</Offset><Text>星光 ささやく</Text></Line><Line><Offset>1</Offset><Text>朝日 晨风</Text></Line></Lyrics>"""
        return (xml.substring(0, start) + track + lyrics + xml.substring(end)).toByteArray(Charsets.UTF_8)
    }
}
