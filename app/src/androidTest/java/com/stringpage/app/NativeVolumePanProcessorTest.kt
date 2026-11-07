package com.stringpage.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeVolumePanProcessor
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVolumePanProcessorTest {
    private fun cases() = JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
        .open("native-volume-pan-processor.json").bufferedReader().use { it.readText() })

    @Test fun sequentialMonoStereoBlocksMatchNativeResetBypassAndParameterChanges() {
        val cases = cases()
        assertEquals(344, cases.length())
        var channels = 0; var processor = NativeVolumePanProcessor()
        for (case in 0 until cases.length()) {
            val row = cases.getJSONObject(case)
            if (channels != row.getInt("channels")) { channels = row.getInt("channels"); processor = NativeVolumePanProcessor() }
            val controls = row.getJSONObject("controls")
            if (controls.has("volume")) processor.volume = controls.getDouble("volume").toFloat()
            if (controls.has("pan")) processor.pan = controls.getDouble("pan").toFloat()
            if (controls.has("bypassed")) processor.bypassed = controls.getBoolean("bypassed")
            if (controls.optBoolean("reset")) processor.reset()
            val frames = controls.getInt("frames")
            val input = Array(channels) { channel ->
                val values = row.getJSONArray("input").getJSONArray(channel)
                FloatArray(frames + 4) { index -> if (index < frames) values.getDouble(index).toFloat() else 12345.25f }
            }
            val before = input.map { it.copyOf() }
            val inPlace = controls.getBoolean("inPlace")
            val output = if (inPlace) input else Array(channels) { FloatArray(frames + 4) { -9876.5f } }
            processor.process(input, output, frames)
            for (channel in 0 until channels) {
                val expected = row.getJSONArray("output").getJSONArray(channel)
                for (frame in 0 until frames) assertEquals("case $case ch$channel frame$frame",
                    expected.getDouble(frame).toFloat(), output[channel][frame], 0.00001f)
                for (index in frames until frames + 4) assertEquals(if (inPlace) 12345.25f else -9876.5f, output[channel][index], 0f)
                if (!inPlace) assertArrayEquals(before[channel], input[channel], 0f)
            }
        }
    }

    @Test fun interleavedAdapterMatchesTheSameNativeStereoBlocksIncludingZeroFrames() {
        val cases = cases(); val processor = NativeVolumePanProcessor()
        for (case in 0 until cases.length()) {
            val row = cases.getJSONObject(case)
            if (row.getInt("channels") != 2) continue
            val controls = row.getJSONObject("controls")
            if (controls.has("volume")) processor.volume = controls.getDouble("volume").toFloat()
            if (controls.has("pan")) processor.pan = controls.getDouble("pan").toFloat()
            if (controls.has("bypassed")) processor.bypassed = controls.getBoolean("bypassed")
            if (controls.optBoolean("reset")) processor.reset()
            val frames = controls.getInt("frames")
            val buffer = FloatArray((frames + 2) * 2) { 12345.25f }
            for (frame in 0 until frames) for (channel in 0..1) buffer[(frame + 1) * 2 + channel] =
                row.getJSONArray("input").getJSONArray(channel).getDouble(frame).toFloat()
            processor.processStereo(buffer, frames, offsetFrames = 1)
            for (frame in 0 until frames) for (channel in 0..1) assertEquals("case $case ch$channel frame$frame",
                row.getJSONArray("output").getJSONArray(channel).getDouble(frame).toFloat(), buffer[(frame + 1) * 2 + channel], 0.00001f)
            assertEquals(12345.25f, buffer.first(), 0f); assertEquals(12345.25f, buffer[1], 0f)
            assertEquals(12345.25f, buffer[buffer.lastIndex - 1], 0f); assertEquals(12345.25f, buffer.last(), 0f)
        }
    }
}
