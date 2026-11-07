package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.DecodedBankSample
import com.stringpage.app.rse.NativeSampleVoice
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeSampleVoiceTest {
    @Test fun cachedStereoBridgeMatchesNativePcmWithExclusiveLoopsAndPitchCorrectionOrder() {
        verify("native-voice.json")
    }
    @Test fun selectedSourceStartingFrameFeedsTheNativeResamplerBeforeItsHistoryAndCache() {
        verify("native-voice-start.json")
    }
    private fun verify(asset: String) {
        val rows=JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
            .open(asset).bufferedReader().use { it.readText() })
        assertEquals(12,rows.length());var samples=0
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val channels=row.getInt("channels");val source=row.getJSONArray("source")
            val pcm=FloatArray(source.getJSONArray(0).length()*channels) {
                source.getJSONArray(it%channels).getDouble(it/channels).toFloat()
            }
            val loop=if(row.isNull("loop"))null else row.getJSONArray("loop")
            val voice=NativeSampleVoice(DecodedBankSample(pcm,channels,44100),44100,
                gain=row.getDouble("gain").toFloat(),pitchCorrection=row.getDouble("correction").toFloat(),
                loopStart=loop?.getInt(0),loopEndExclusive=loop?.getInt(1),startingFrame=row.optInt("startingFrame",0))
            val calls=row.getJSONArray("calls")
            for(call in 0 until calls.length()) {
                val item=calls.getJSONObject(call);val frames=item.getInt("frames")
                voice.pitchSemitones=item.getDouble("pitch")
                val output=FloatArray((frames+2)*2) { 12345.25f };voice.render(output,frames)
                repeat(frames*2) { at ->
                    assertEquals("voice $index call $call sample $at",item.getJSONArray("output").getDouble(at).toFloat().toRawBits(),output[at].toRawBits())
                }
                for(at in frames*2 until output.size)assertEquals(12345.25f,output[at],0f)
                samples+=frames*2
            }
            if(loop!=null)assertFalse(voice.finished)
        }
        assertEquals(21168,samples)
    }
}
