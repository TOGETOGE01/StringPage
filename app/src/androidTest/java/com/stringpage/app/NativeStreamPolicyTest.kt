package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStreamPolicyTest {
    @Test fun everyEasternPianoMemberMatchesActualSoundFontStreamLoopState() {
        val data=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("native-stream-loops.json").bufferedReader().use { it.readText() })
        val rows=data.getJSONArray("rows");assertEquals(120,rows.length());var disabled=0
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val name=row.getString("member")
            assertEquals(name,row.getBoolean("disabled"),NativeStreamPolicy.disablesLoop(name))
            val loop=RseLoop(0,0,row.getLong("authoredStart"),row.getLong("authoredEnd"),0,0)
            val frames=maxOf(loop.start,loop.end).toInt()+1
            val chosen=NativeStreamPolicy.forwardLoop(name,listOf(loop),frames)
            val nativeLoops=row.getBoolean("hasLoop") && row.getLong("nativeStart")<row.getLong("nativeEnd")
            assertEquals(name,nativeLoops,chosen!=null)
            if(row.getBoolean("disabled"))disabled++
        }
        assertEquals(40,disabled)
    }

    @Test fun exactMemberRuleStopsTheTailWhileNeighborAndCaseVariantsKeepTheirLoop() {
        val excluded="EasternGrandC7-Key-Sus-none-04-none-081-none.ogg"
        assertFalse(NativeStreamPolicy.disablesLoop(excluded.lowercase()))
        assertFalse(NativeStreamPolicy.disablesLoop("folder/$excluded"))
        assertFalse(NativeStreamPolicy.disablesLoop(excluded.replace("081","078")))
        val audio=DecodedBankSample(FloatArray(64) { .5f },1,44100)
        val loop=RseLoop(0,0,8,24,0,0)
        for((name,shouldFinish) in listOf(excluded to true,"other.ogg" to false)) {
            val policy=NativeStreamPolicy.forwardLoop(name,listOf(loop),audio.frames)
            val voice=NativeSampleVoice(audio,44100,loopStart=policy?.start?.toInt(),loopEndExclusive=policy?.end?.toInt())
            val output=FloatArray(512);voice.render(output,256)
            assertEquals(name,shouldFinish,voice.finished)
            if(shouldFinish)assertTrue(output.takeLast(256).all { it==0f })
            else assertTrue(output.takeLast(256).all { it==.5f })
        }
    }
}
