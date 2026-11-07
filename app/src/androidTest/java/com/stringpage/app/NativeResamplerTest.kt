package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeResampler
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeResamplerTest {
    @Test fun nativeDispatcherBranchesPhaseHistoryAndPitchChangesMatchOriginalDll() {
        val rows=JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
            .open("native-resample.json").bufferedReader().use { it.readText() })
        assertEquals(40,rows.length());var blocks=0
        for(index in 0 until rows.length()) {
            val state=NativeResampler();val sequence=rows.getJSONArray(index)
            for(block in 0 until sequence.length()) {
                val item=sequence.getJSONObject(block)
                if(item.isNull("pitch"))state.setRatio(item.getDouble("ratio").toFloat())
                else state.setPitchSemitones(item.getDouble("pitch").toFloat())
                assertEquals(item.getDouble("ratio").toFloat().toRawBits(),state.ratio.toRawBits())
                assertEquals(item.getDouble("beforePhase").toFloat().toRawBits(),state.phase.toRawBits())
                val needed=item.getInt("needed");assertEquals(needed,state.requiredInputFrames())
                val pcm=FloatArray(needed) { item.getJSONArray("input").getDouble(it).toFloat() }
                val output=FloatArray(36) { 12345.25f };state.process(pcm,output)
                repeat(32) { frame -> assertEquals("sequence $index block $block frame $frame",
                    item.getJSONArray("output").getDouble(frame).toFloat().toRawBits(),output[frame].toRawBits()) }
                repeat(4) { at ->
                    assertEquals(12345.25f,output[32+at],0f)
                    assertEquals(item.getJSONArray("history").getDouble(at).toFloat().toRawBits(),state.history()[at].toRawBits())
                }
                assertEquals(item.getDouble("phase").toFloat().toRawBits(),state.phase.toRawBits());blocks++
            }
        }
        assertEquals(1920,blocks)
    }
}
