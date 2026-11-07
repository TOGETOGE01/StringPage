package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeVolumePanCurve
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeVolumePanCurveTest {
    @Test fun gainCurveMatchesRetainedNativeProbesWithinMeasuredFloatError() {
        val rows = JSONArray(InstrumentationRegistry.getInstrumentation().context.assets.open("native-volume-pan.json")
            .bufferedReader().use { it.readText() })
        assertEquals(1049, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val result = NativeVolumePanCurve.gains(row.getDouble("volume").toFloat(), row.getDouble("pan").toFloat())
            assertEquals("left $i", row.getDouble("left").toFloat(), result.left, 0.000002f)
            assertEquals("right $i", row.getDouble("right").toFloat(), result.right, 0.000002f)
        }
        assertEquals(NativeVolumePanCurve.Gains(1f,1f), NativeVolumePanCurve.gains(0.8f,0.5f))
        assertEquals(NativeVolumePanCurve.Gains(0f,0f), NativeVolumePanCurve.gains(0f,0.5f))
    }
}
