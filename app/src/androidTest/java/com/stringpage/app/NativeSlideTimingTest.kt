package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeSlideTiming
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSlideTimingTest {
    @Test fun originalIntegerAndFloat32VectorBuildersMatch864Pairs() {
        val rows=InstrumentationRegistry.getInstrumentation().context.assets.open("native-slide-timing.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("rows")
        assertEquals(864,rows.length())
        repeat(rows.length()) { at ->
            val row=rows.getJSONObject(at);val first=row.getInt("first");val last=row.getInt("last")
            val ticks=row.getJSONArray("ticks");val midis=row.getJSONArray("midis")
            assertArrayEquals("ticks $at",IntArray(ticks.length()) { ticks.getInt(it) },NativeSlideTiming.ticks(
                first,last,row.getInt("start"),row.getInt("end"),row.getInt("offset"),row.getInt("brush")))
            assertArrayEquals("MIDI $at",IntArray(midis.length()) { midis.getInt(it) },NativeSlideTiming.midis(first,last,row.getInt("midi")))
        }
    }
}
