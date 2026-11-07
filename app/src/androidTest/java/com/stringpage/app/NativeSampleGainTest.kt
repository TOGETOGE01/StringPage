package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.NativeSampleGain
import com.stringpage.app.rse.NativeSampleVoice
import com.stringpage.app.rse.DecodedBankSample
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSampleGainTest {
    private fun fixture(name: String)=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).bufferedReader().use { it.readText() })

    @Test fun coordinateGainAndPitchConversionMatchOriginalFloatBits() {
        val rows=fixture("native-dynamic.json").getJSONArray("rows");assertEquals(270,rows.length())
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val delta=row.getInt("delta")
            val result=if(row.getString("kind")=="amplitude")NativeSampleGain.dynamicCorrection(delta) else NativeSampleGain.pitchCorrection(delta)
            assertEquals("case $index",row.getLong("bits").toInt(),result.toRawBits())
        }
    }
    @Test fun accentuationInterpreterMatchesNativePriorityAndIntegerOverflow() {
        val rows=fixture("native-accent.json").getJSONArray("rows");assertEquals(2688,rows.length())
        for(index in 0 until rows.length()) {
            val r=rows.getJSONObject(index)
            assertEquals("case $index",r.getInt("result"),NativeSampleGain.adjustedDynamic(
                r.getInt("dynamic"),r.getInt("flags"),r.getInt("anti"),r.getInt("process")))
        }
    }
    @Test fun decoderDefaultMatchesAll350InitializedOriginalBanks() {
        val rows=fixture("native-decoder-gain.json").getJSONArray("rows");assertEquals(350,rows.length())
        for(index in 0 until rows.length())assertEquals(rows.getJSONObject(index).getLong("bits").toInt(),NativeSampleGain.DEFAULT_DECODER_GAIN.toRawBits())
    }
    @Test fun decoderSampleAndNoteGainStagesMatchNativePipelineAcrossCachedBlocks() {
        val rows=fixture("native-gain-pipeline.json").getJSONArray("rows");assertEquals(8,rows.length());var samples=0
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val channels=row.getInt("channels");val source=row.getJSONArray("source")
            val pcm=FloatArray(source.getJSONArray(0).length()*channels) { source.getJSONArray(it%channels).getDouble(it/channels).toFloat() }
            val voice=NativeSampleVoice(DecodedBankSample(pcm,channels,44100),44100,
                semitones=row.getDouble("pitch"),gain=row.getDouble("gain").toFloat(),loopStart=17,loopEndExclusive=89,
                decoderGain=row.getDouble("decoderGain").toFloat(),sampleGain=row.getDouble("sampleGain").toFloat())
            val calls=row.getJSONArray("calls")
            for(call in 0 until calls.length()) {
                val item=calls.getJSONObject(call);val frames=item.getInt("frames");val output=FloatArray(frames*2)
                voice.render(output,frames)
                for(at in output.indices)assertEquals("voice $index call $call sample $at",
                    item.getJSONArray("output").getDouble(at).toFloat().toRawBits(),output[at].toRawBits())
                samples+=frames*2
            }
        }
        assertEquals(5200,samples)
    }
}
