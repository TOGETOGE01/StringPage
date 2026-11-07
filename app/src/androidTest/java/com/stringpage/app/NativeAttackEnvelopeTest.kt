package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeAttackEnvelope
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeAttackEnvelopeTest {
    @Test fun cachedNativeReadTickAttackMatchesPcmAndStateAcrossTickAndBlockBoundaries() {
        val rows=JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
            .open("native-attack.json").bufferedReader().use { it.readText() })
        assertEquals(28,rows.length());var blocks=0;var total=0
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val channels=row.getInt("channels")
            val envelope=NativeAttackEnvelope(row.getLong("fadeFrames"));val sequence=row.getJSONArray("blocks")
            for(block in 0 until sequence.length()) {
                val item=sequence.getJSONObject(block);val frames=item.getInt("frames")
                assertEquals(item.getLong("beforeElapsed"),envelope.elapsedFrames)
                assertEquals(item.getDouble("beforeGain").toFloat().toRawBits(),envelope.gain.toRawBits())
                val pcm=FloatArray((frames+2)*channels) { 9876.5f }
                for(frame in 0 until frames) for(channel in 0 until channels)
                    pcm[(frame+1)*channels+channel]=item.getJSONArray("input").getJSONArray(channel).getDouble(frame).toFloat()
                envelope.applyTick(pcm,channels,frames,if(item.isNull("tickFrames"))null else item.getLong("tickFrames"),1)
                for(frame in 0 until frames) for(channel in 0 until channels) {
                    val expected=item.getJSONArray("output").getJSONArray(channel).getDouble(frame).toFloat()
                    assertEquals("sequence $index block $block frame $frame channel $channel",expected.toRawBits(),pcm[(frame+1)*channels+channel].toRawBits())
                }
                for(channel in 0 until channels) {
                    assertEquals(9876.5f,pcm[channel],0f);assertEquals(9876.5f,pcm[(frames+1)*channels+channel],0f)
                }
                assertEquals(item.getLong("elapsed"),envelope.elapsedFrames)
                assertEquals(item.getDouble("gain").toFloat().toRawBits(),envelope.gain.toRawBits())
                blocks++;total+=frames*channels
            }
        }
        assertEquals(1344,blocks);assertEquals(21756,total)
    }
}
