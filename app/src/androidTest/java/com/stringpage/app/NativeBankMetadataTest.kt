package com.stringpage.app

import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeBankMetadataTest {
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("native-bank-metadata.json").bufferedReader().use { it.readText() })
    private fun JSONArray.ints() = List(length()) { getInt(it) }
    private fun JSONArray.strings() = List(length()) { getString(it) }
    private fun wave(pickup: String, name: String, repitch: Double = 0.0, gain: Double = 0.0, ratio: Double = 1.0) =
        RseWave(pickup, name, repitch, gain, BankEntry(name,0,0), ratio)
    private fun sample(path: List<String>, waves: List<RseWave>, midi: Int = 44, string: Int = 1) =
        RseSample(path,midi,string,"mf","none",waves)
    private fun bank(samples: List<RseSample>, id: String = "synthetic", type: String = "steelGuitar") =
        RseSoundBank(id,"",type,"",listOf(40,45,50,55,59,64),samples)
    private fun signature(art: List<Int>, coordinate: List<Int>, waves: List<Pair<String,String>>, corrections: List<Int>) =
        art.joinToString(",")+"|"+coordinate.joinToString(",")+"|"+
            waves.sortedWith(compareBy<Pair<String,String>> { it.first }.thenBy { it.second }).joinToString(";") { "${it.first}:${it.second}" }+
            "|"+corrections.joinToString(",")

    @Test fun independentRawMetadataReproducesAll350NativeSampleSetsAndCorrectionBits() {
        val rows=fixture().getJSONArray("banks");assertEquals(350,rows.length());var total=0
        for (bankIndex in 0 until rows.length()) {
            val row=rows.getJSONObject(bankIndex);val raw=row.getJSONArray("source")
            val source=List(raw.length()) { index ->
                val item=raw.getJSONArray(index);val waves=item.getJSONArray(5)
                RseSample(item.getJSONArray(0).strings(),item.getInt(1),item.getInt(2),item.getString(3),item.getString(4),
                    List(waves.length()) { i -> val w=waves.getJSONArray(i)
                        wave(w.getString(0),w.getString(1),w.getDouble(2),w.getDouble(3)) })
            }
            val metadata=NativeBankMetadata(RseSoundBank(row.getString("id"),"",row.getString("type"),"",row.getJSONArray("tuning").ints(),source))
            assertEquals(row.getString("id"),row.getBoolean("pitched"),metadata.pitched)
            fun nodes(n:NativeSampleTree.Node):Int=1+n.children.sumOf { nodes(it) }
            assertEquals(row.getString("id"),row.getInt("nodeCount"),nodes(metadata.root))
            val actual=metadata.samples.map { sample ->
                val a=sample.articulation;val c=sample.coordinate
                signature(listOf(a.attack,a.technique,a.variation),listOf(c.stringIndex,c.midi,c.dynamic,c.option,c.fret),
                    sample.waves.map { it.pickup to it.file },listOf(sample.pitchCorrection.toRawBits(),sample.amplitudeCorrection.toRawBits()))
            }.groupingBy { it }.eachCount()
            val expectedRows=row.getJSONArray("expected")
            val expected=List(expectedRows.length()) { i ->
                val s=expectedRows.getJSONArray(i);val w=s.getJSONArray(2);val corr=s.getJSONArray(3)
                signature(s.getJSONArray(0).ints(),s.getJSONArray(1).ints(),
                    List(w.length()) { j -> w.getJSONArray(j).let { it.getString(0) to it.getString(1) } },
                    List(corr.length()) { j -> corr.getLong(j).toInt() })
            }.groupingBy { it }.eachCount()
            assertEquals("${row.getString("id")} metadata/correction multiset",expected,actual)
            total+=metadata.samples.size
        }
        assertEquals(11387,total)
    }

    @Test fun enumCaseSensitivityDynamicComparisonAndTypeMembershipMatchPureNativeGetters() {
        val fixture=fixture();val enums=fixture.getJSONObject("enums")
        for ((kind,pitched) in listOf("pitched" to true,"unpitched" to false)) {
            for ((axis,name) in listOf("attack","technique","variation").withIndex()) {
                val values=enums.getJSONObject("${kind}_$name").getJSONArray("values")
                for (i in 0 until values.length()) {
                    val row=values.getJSONObject(i)
                    assertEquals("$kind $name ${row.getString("text")}",row.getInt("value"),NativeBankMetadata.enumValue(row.getString("text"),pitched,axis))
                }
            }
        }
        for (name in listOf("dynamic","option")) {
            val values=enums.getJSONObject(name).getJSONArray("values")
            for (i in 0 until values.length()) {
                val row=values.getJSONObject(i);val text=row.getString("text")
                assertEquals("$name $text",row.getInt("value"),if(name=="dynamic")NativeBankMetadata.dynamicValue(text) else NativeBankMetadata.optionValue(text))
            }
        }
        val types=fixture.getJSONArray("types")
        for(i in 0 until types.length()) {
            val row=types.getJSONObject(i)
            assertEquals(row.getString("text"),row.getBoolean("pitched"),NativeBankMetadata.isPitched(row.getString("text")))
        }
    }

    @Test fun nativeWaveMapLastWaveCorrectionsHarmonicOrderAndExplicitSkipRulesStaySeparate() {
        val a=wave("bridge","a",30.0,6.0,2.0);val b=wave("bridge","b",40.0,9.0,3.0);val c=wave("neck","c",50.0,10.0,4.0)
        val input=sample(listOf("pick","sustain","down"),listOf(a,b,c))
        val m=NativeBankMetadata(bank(listOf(input),"synthetic+"));val s=m.samples.single()
        assertEquals(listOf("b","c"),s.waves.map { it.file });assertEquals(-.5f,s.pitchCorrection,0f);assertEquals(4f,s.amplitudeCorrection,0f)
        assertEquals(1.9952623f,NativeBankMetadata.waveGain(a),.000001f)
        val harmonic=sample(listOf("harmonic","natural","9th"),listOf(a),73,3)
        val h=NativeBankMetadata(bank(listOf(harmonic))).samples.single().coordinate
        assertEquals(78,h.midi);assertEquals(23,h.fret);assertEquals(7,h.dynamic)
        val excluded=wave("none","EasternGrandC7-Key-Sus-none-07-none-081-none.ogg")
        assertTrue(NativeBankMetadata.skipSample("Eastern-APiano",listOf(excluded.file)))
        assertFalse(NativeBankMetadata.skipSample("eastern-apiano",listOf(excluded.file)))
        assertFalse(NativeBankMetadata.skipSample("other",listOf(excluded.file)))
        assertTrue(NativeBankMetadata(bank(listOf(input.copy(nodePath=listOf("PICK")),input.copy(nodePath=emptyList())))).samples.isEmpty())
    }

    @Test fun rawReaderPreservesOptionalWaveAttributesAndRejectsNonfiniteValues() {
        val xml="""<SoundBank id="synthetic" name="" type="steelGuitar" description="" tuning="40"><SamplesTree><Node class="pick"><Node class="sustain"><Node class="down"><Samples><Sample midi="44" string="1" dynamic="mf" option="none"><Wave pickup="none" file="n.ogg" repitch="12.5" gain="-6" ratio="0.25" peak="0.75" velocity="92"/></Sample></Samples></Node></Node></Node></SamplesTree></SoundBank>"""
        fun archive(text:String):ByteArray {
            fun i32(value:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            fun i64(value:Long)=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
            val members=listOf("Samples.xml" to text.toByteArray(),"n.ogg" to byteArrayOf(1))
            val indexLength=members.sumOf { 12+it.first.toByteArray().size+1 };var offset=25L+indexLength
            val index=members.fold(ByteArray(0)) { bytes,(name,data) -> (bytes+i64(offset)+i32(data.size)+name.toByteArray()+byteArrayOf(0)).also { offset+=data.size } }
            return "AMARCHIVE".toByteArray()+i32(1)+i32(2)+i32(indexLength)+i32(25)+index+members.fold(ByteArray(0)) { bytes,item -> bytes+item.second }
        }
        val file=File.createTempFile("native-metadata-",".gp7bank",InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(archive(xml));val wave=Gp7Bank.open(file).readMetadata().samples.single().waves.single()
            assertEquals(12.5,wave.repitch,0.0);assertEquals(-6.0,wave.gain,0.0);assertEquals(.25,wave.ratio,0.0);assertEquals(.75,wave.peak,0.0);assertEquals(92,wave.velocity)
            file.writeBytes(archive(xml.replace("ratio=\"0.25\"","ratio=\"NaN\"")))
            try { Gp7Bank.open(file).readMetadata();fail("Nonfinite ratio accepted") }catch(_:BankFormatException) { }
        } finally { file.delete() }
    }
}
