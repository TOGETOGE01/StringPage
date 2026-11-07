package com.stringpage.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeSampleStart
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSampleStartTest {
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("native-sample-start.json").bufferedReader().use { it.readText() })

    private fun JSONArray.longs() = List(length()) { getLong(it) }

    @Test fun decoderRecordCountAndSignedCueOffsetsMatchOriginalDll() {
        val cases = fixture().getJSONArray("decoderCases")
        assertEquals(72, cases.length())
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            // The probe owns two records even in count=3 tests; only the first
            // two are reachable in the native getter. A third arbitrary record
            // reproduces that decoder domain without copying native memory.
            val count = row.getInt("count")
            val offsets = row.getJSONArray("offsets").longs().take(count).toMutableList()
            while (offsets.size < count) offsets.add(123L)
            assertEquals("decoder case $index", row.getLong("actual"),
                NativeSampleStart.decoderCuePoint(offsets, row.getInt("mode")))
        }
    }

    @Test fun clampingEnumMappingAndUnchangedStartPreserveOriginalCursor() {
        val cases = fixture().getJSONArray("playerCases")
        assertEquals(144, cases.length())
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val before = row.getJSONArray("before")
            val cursor = NativeSampleStart.Cursor(before.getLong(0), before.getLong(1))
            val cap = row.getLong("cap")
            val actual = if (row.getString("kind") == "cue") {
                val offsets = row.getJSONArray("offsets").longs()
                NativeSampleStart.setStartingCuePoint(cursor, row.getInt("mode"), cap,
                    offsets.indices.associateWith { offsets[it] })
            } else NativeSampleStart.setStartingFrame(cursor, row.getLong("requested"), cap)
            val expected = row.getJSONArray("actual")
            assertEquals("starting frame case $index", expected.getLong(0), actual.startingFrame)
            assertEquals("current frame case $index", expected.getLong(1), actual.currentFrame)
        }
    }

    @Test fun missingStreamResetAndMissingStreamCueKeyFollowObservedBranches() {
        val cursor = NativeSampleStart.Cursor(17, 23)
        assertEquals(cursor, NativeSampleStart.setStartingCuePoint(cursor, 1, null, mapOf(1 to 9060L)))
        val explicit = NativeSampleStart.setStartingFrame(cursor, 1000000L, null)
        assertEquals(NativeSampleStart.Cursor(1000000L, 1000000L), explicit)
        assertEquals(explicit, NativeSampleStart.reset(explicit.copy(currentFrame = 2000000)))
        assertEquals(NativeSampleStart.Cursor(), NativeSampleStart.setStartingCuePoint(cursor, 2, 100L, emptyMap()))
        assertEquals(NativeSampleStart.Cursor(), NativeSampleStart.setStartingCuePoint(cursor, 1, 0L, mapOf(1 to 9060L)))
    }

    @Test fun explicitSlideFramesAndFallbackMatch192OriginalAccessorSetters() {
        val rows=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("native-slide-cues.json")
            .bufferedReader().use { it.readText() }).getJSONArray("rows")
        assertEquals(192,rows.length())
        repeat(rows.length()) { at ->
            val row=rows.getJSONObject(at);val cues=row.getJSONArray("cues")
            assertEquals("slide cue $at",row.getLong("result"),NativeSampleStart.initialFrame(row.getInt("cue"),
                (0..2).associateWith { cues.getLong(it) },row.getLong("cap"),row.getLong("user")))
        }
    }
}
