@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import com.stringpage.app.rse.GpRseProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ScoreExportTest {
    @Test fun authoredPlayingStyleSurvivesCompatibilityExportAndKeepsEmbeddedAudio() {
        val score=demo();val before=rawAudio(score)
        for (style in listOf("StringedFinger","stringedpick","BassSlap","", "unknown<&>",null)) {
            val properties=GpRseProperties.Properties(GpRseProperties.Container.Zip,
                score.tracks.toList().map { GpRseProperties.TrackInfo(it.index.toInt().toString(),null,null,style,
                    style?.let(GpRseProperties::playingStyleCore),emptyList()) }.toList(),true)
            GpRseProperties.bind(score,properties)
            val output=ScoreExport.guitarProBytes(score)
            assertEquals(style ?: "Default",GpRseProperties.read(output).track(0)!!.rawPlayingStyle)
            val reloaded=ScoreLoader.loadScoreFromBytes(Uint8Array(output.asUByteArray()),Settings())
            assertArrayEquals(before,rawAudio(reloaded));assertEquals(notes(score),notes(reloaded))
            assertSame(properties,GpRseProperties.get(score))
        }
    }

    private fun demo(): Score {
        val bytes = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("morning.gp").use { it.readBytes() }
        return ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())
    }

    @Test fun gpRoundTripRetainsAudioNotesAndSyncPoints() {
        val original = demo()
        original.title = "Round trip export"
        val output = ScoreExport.guitarProBytes(original)
        assertEquals("PK", output.copyOfRange(0, 2).toString(Charsets.US_ASCII))
        assertEquals(original.tracks.length.toInt(), GpRseProperties.read(output).tracks.size)
        val reloaded = ScoreLoader.loadScoreFromBytes(Uint8Array(output.asUByteArray()), Settings())
        assertEquals(original.title, reloaded.title)
        assertEquals(original.tracks.length, reloaded.tracks.length, 0.0)
        assertEquals(original.masterBars.length, reloaded.masterBars.length, 0.0)
        assertEquals(notes(original), notes(reloaded))
        assertArrayEquals(rawAudio(original), rawAudio(reloaded))
        for ((index, bar) in original.masterBars.withIndex()) {
            val expected = bar.syncPoints?.toList() ?: emptyList()
            val actual = reloaded.masterBars[index].syncPoints?.toList() ?: emptyList()
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals(expected[i].ratioPosition, actual[i].ratioPosition, 0.0001)
                assertEquals(expected[i].syncPointValue!!.millisecondOffset,
                    actual[i].syncPointValue!!.millisecondOffset, 0.1)
            }
        }
    }

    @Test fun midiHasValidSmf1TracksAuthoredNotesTempoAndMix() {
        val score = demo()
        score.tracks[0].playbackInfo.volume = 7.0
        score.tracks[0].playbackInfo.balance = 2.0
        score.tracks[0].playbackInfo.program = 27.0
        val file = parseMidi(ScoreExport.midiBytes(score))
        assertEquals(1, file.format)
        assertEquals(2, file.trackCount) // conductor plus one instrument
        assertEquals(960, file.division)
        assertTrue(file.events.any { it.status and 0xf0 == 0x90 && it.a == 40 && it.b > 0 })
        assertTrue(file.events.any { it.status and 0xf0 == 0xb0 && it.a == 7 && it.b == 56 })
        assertTrue(file.events.any { it.status and 0xf0 == 0xb0 && it.a == 10 && it.b == 16 })
        assertTrue(file.events.any { it.status and 0xf0 == 0xc0 && it.a == 27 })
        val tempo = file.events.first { it.status == 0xff && it.a == 0x51 }.payload
        assertArrayEquals(byteArrayOf(0x09, 0x27, 0xc0.toByte()), tempo) // 100 BPM = 600000 µs/qn
        assertTrue(file.events.count { it.status == 0xff && it.a == 0x2f } == file.trackCount)
    }

    @Test fun midiOmitsModelMutedTracksButRetainsConductor() {
        val score = demo()
        score.tracks[0].playbackInfo.isMute = true
        val file = parseMidi(ScoreExport.midiBytes(score))
        assertEquals(1, file.trackCount)
        assertFalse(file.events.any { it.status and 0xf0 == 0x90 })
        assertTrue(file.events.any { it.status == 0xff && it.a == 0x51 })
    }

    @Test fun exportDoesNotMutateCurrentScoreAndDoesNotCloseCallerStream() {
        val score = demo()
        val beat = score.tracks[0].staves[0].bars[0].voices[0].beats[0]
        beat.showTimer = true
        beat.timer = 12345.0
        val before = notes(score)
        ScoreExport.midiBytes(score)
        ScoreExport.guitarProBytes(score)
        assertEquals(12345.0, beat.timer!!, 0.0)
        assertEquals(before, notes(score))
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        ScoreExport.writeTo(score, ScoreExport.Format.MIDI, output)
        assertFalse(output.closed)
        assertTrue(output.size() > 14)
    }

    private fun rawAudio(score: Score): ByteArray {
        val raw = score.backingTrack!!.rawAudioFile!!
        return raw.buffer.asByteArray().copyOfRange(raw.byteOffset.toInt(), (raw.byteOffset + raw.length).toInt())
    }

    private fun notes(score: Score): List<String> = buildList {
        for ((ti, track) in score.tracks.withIndex()) for ((si, staff) in track.staves.withIndex())
            for ((bi, bar) in staff.bars.withIndex()) for ((vi, voice) in bar.voices.withIndex())
                for ((beatIndex, beat) in voice.beats.withIndex()) {
                    add("$ti:$si:$bi:$vi:$beatIndex:${beat.duration}:${beat.dots}:${beat.isRest}")
                    for (note in beat.notes) add("${note.string}:${note.fret}:${note.realValue}")
                }
    }

    private data class Event(val status: Int, val a: Int, val b: Int, val payload: ByteArray = byteArrayOf())
    private data class FileData(val format: Int, val trackCount: Int, val division: Int, val events: List<Event>)

    /** Reads the written SMF independently of alphaTab to validate chunks and event encoding. */
    private fun parseMidi(bytes: ByteArray): FileData {
        var cursor = 0
        fun read(): Int {
            require(cursor < bytes.size) { "Unexpected end of MIDI" }
            return bytes[cursor++].toInt() and 255
        }
        fun int16(): Int = (read() shl 8) or read()
        fun int32(): Int = (read() shl 24) or (read() shl 16) or (read() shl 8) or read()
        fun tag(): String = CharArray(4) { read().toChar() }.concatToString()
        fun vlq(): Int {
            var value = 0
            repeat(4) {
                val next = read(); value = (value shl 7) or (next and 127)
                if (next and 128 == 0) return value
            }
            error("Invalid MIDI variable-length quantity")
        }
        require(tag() == "MThd")
        require(int32() == 6)
        val format = int16(); val tracks = int16(); val division = int16()
        val events = mutableListOf<Event>()
        repeat(tracks) {
            require(tag() == "MTrk")
            val length = int32(); val end = cursor + length
            require(length >= 0 && end <= bytes.size)
            var running = -1
            while (cursor < end) {
                vlq()
                var status = read()
                if (status < 0x80) { cursor--; status = running }
                require(status >= 0x80)
                if (status == 0xff) {
                    running = -1
                    val type = read(); val size = vlq()
                    require(size >= 0 && cursor + size <= end)
                    val payload = bytes.copyOfRange(cursor, cursor + size); cursor += size
                    events.add(Event(status, type, 0, payload))
                } else if (status == 0xf0 || status == 0xf7) {
                    running = -1
                    val size = vlq(); require(cursor + size <= end); cursor += size
                } else {
                    require(status <= 0xef)
                    running = status
                    val a = read()
                    val b = if (status and 0xf0 == 0xc0 || status and 0xf0 == 0xd0) 0 else read()
                    require(a < 0x80 && b < 0x80)
                    events.add(Event(status, a, b))
                }
            }
            require(cursor == end)
        }
        require(cursor == bytes.size)
        return FileData(format, tracks, division, events)
    }
}
