package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeFadePolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeFadePolicyTest {
    private fun state(row: JSONObject) = NativeFadePolicy.State(
        fadeInFrames = row.getLong("fadeIn"), fadeOutFrames = row.getLong("fadeOut"),
        startingCuePoint = row.getInt("cue"), userDefinedFrame = row.getLong("userFrame"),
        shouldDecreaseAmplitude = row.getBoolean("decrease"), decreaseAmplitudeFactor = row.getDouble("factor").toFloat())

    @Test fun slideParameterTransitionsMatchOriginalDllIncludingPreservedStateAndMissingAccessor() {
        val cases=JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
            .open("native-fade-policy.json").bufferedReader().use { it.readText() })
        assertEquals(640,cases.length())
        for (index in 0 until cases.length()) {
            val row=cases.getJSONObject(index)
            val before=if(row.getBoolean("hasSample")) state(row.getJSONObject("before")) else null
            val actual=when(row.getString("kind")) {
                "legato" -> NativeFadePolicy.legatoDestination(before,row.getInt("previousMidi"),row.getInt("currentMidi"))
                "shift" -> NativeFadePolicy.shift(before,row.getBoolean("fadeIn"),row.getBoolean("fadeOut"))
                "generated" -> NativeFadePolicy.generatedSegment(before,row.getBoolean("fadeIn"),row.getBoolean("fadeOut"),
                    row.getInt("previousMidi"),row.getInt("currentMidi"))
                else -> throw AssertionError("Unknown oracle transition")
            }
            if (before==null) assertNull("case $index",actual)
            else assertEquals("case $index",state(row.getJSONObject("actual")),actual)
        }
    }
}
