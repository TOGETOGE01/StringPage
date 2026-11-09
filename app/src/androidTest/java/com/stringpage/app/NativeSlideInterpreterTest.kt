@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Note
import alphaTab.model.Score
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeSlideInterpreterTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).bufferedReader().use { JSONObject(it.readText()) }
    private fun load(bytes: ByteArray) = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()),Settings())
        .also { GpRseProperties.bind(it,GpRseProperties.read(bytes)) }
    private fun notes(score: Score): List<Note> = score.tracks.toList().flatMap { it.staves.toList() }.flatMap { it.bars.toList() }
        .flatMap { it.voices.toList() }.flatMap { it.beats.toList() }.flatMap { it.notes.toList() }
    private fun packed(xml: String) = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { out ->
        out.putNextEntry(ZipEntry("Content/score.gpif"));out.write(xml.toByteArray());out.closeEntry() }
    }.toByteArray()
    private fun state(row: JSONObject) = NativeFadePolicy.State(row.getLong("fadeIn"),row.getLong("fadeOut"),
        row.getInt("cue"),row.getLong("userFrame"),row.getBoolean("decrease"),row.getDouble("factor").toFloat())
    private fun segment(label: String,row: JSONObject,actual: NativeSlideInterpreter.Segment) {
        assertEquals(label,row.getInt("midi"),actual.midi);assertEquals(label,row.getInt("start"),actual.start)
        assertEquals(label,row.getInt("end"),actual.end);assertEquals(label,row.getInt("engine"),actual.engine)
        assertEquals(label,state(row.getJSONObject("sample")),actual.state)
    }

    @Test fun actualGpifSlideFlagsMatch80OriginalFirstChildAndSignedLongConversions() {
        val bytes=Base64.getDecoder().decode(fixture("native-slide-graphs.json").getJSONArray("cases").getJSONObject(0).getString("gp"))
        val xml=ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry;it.readBytes().toString(Charsets.UTF_8) }
        val score=load(bytes);val note=notes(score)[0]
        val target=Regex("<Note id=\"0\">[\\s\\S]*?</Note>").find(xml)!!.value
        val rows=fixture("native-slide-flags.json").getJSONArray("rows");assertEquals(80,rows.length())
        repeat(rows.length()) { at ->
            val row=rows.getJSONObject(at)
            val seed="<Property name=\"Slide\"><Flags>${row.getInt("initial")}</Flags></Property>"
            val value=if(row.isNull("content")) "" else "<Property name=\"Slide\">${row.getString("content")}</Property>"
            GpRseProperties.bind(score,GpRseProperties.read(packed(xml.replace(target,target.replace("</Properties>","$seed$value</Properties>")))))
            assertEquals("flags $at",row.getLong("result").toInt(),GpRseProperties.note(note)!!.slideFlags)
        }
    }

    @Test fun actualCorePredicatesAndGeneratedAutomationGraphsMatch884NativeEvaluations() {
        verifyGraphs("native-slide-graphs.json",476,884)
    }
    @Test fun tiedQuarterEighthDottedTripletAndSeptupletGraphsMatch80NativeEvaluations() {
        verifyGraphs("native-slide-tied.json",40,80)
    }
    @Test fun customGpifSlideParametersMatch864OriginalAutomationGraphs() {
        verifyGraphs("native-slide-custom.json",427,864)
    }
    @Test fun typedGpifNumericAttributesMatch234ActualNativeConversions() {
        val rows=fixture("native-slide-parameter-values.json").getJSONArray("rows");assertEquals(234,rows.length())
        repeat(rows.length()) { at ->
            val row=rows.getJSONObject(at);val actual=NativeNumericAttribute.read(row.getString("tag"),row.getString("text"))
            val label=row.getString("tag")+" "+row.getString("text")
            assertEquals(label,row.getLong("floatBits").toInt(),(actual?.floatValue ?: .321f).toRawBits())
            assertEquals(label,row.getInt("intValue"),actual?.intValue ?: -98765)
        }
    }
    private fun verifyGraphs(asset: String,scoreCount: Int,evaluations: Int) {
        val cases=fixture(asset).getJSONArray("cases");assertEquals(scoreCount,cases.length())
        var evaluated=0
        repeat(cases.length()) { ci ->
            val case=cases.getJSONObject(ci);val score=load(Base64.getDecoder().decode(case.getString("gp")))
            val graph=NativeSlideScoreGraph(score)
            val rows=case.getJSONArray("rows")
            repeat(rows.length()) { ri ->
                val row=rows.getJSONObject(ri);val path=row.getJSONArray("path");val label="${case.getString("name")} $path"
                val note=score.tracks[path.getInt(0)].staves[path.getInt(1)].bars[path.getInt(2)]
                    .voices[path.getInt(3)].beats[path.getInt(4)].notes[path.getInt(5)]
                val node=graph.get(note)!!;assertEquals(label,row.getInt("midi"),node.midi)
                assertEquals(label,row.getLong("flags").toInt(),node.flags);assertEquals(label,row.getInt("errors"),NativeSlideInterpreter.errors(node))
                val expected=row.getJSONObject("plan");val before=expected.getJSONObject("before")
                val actual=NativeSlideInterpreter.plan(node,before.getInt("start"),before.getInt("end"),state(before.getJSONObject("sample")))
                assertEquals(label,expected.getBoolean("predicate"),actual.applies)
                segment(label,expected.getJSONObject("root"),actual.root)
                val children=expected.getJSONArray("children");assertEquals(label,children.length(),actual.children.size)
                repeat(children.length()) { at ->segment("$label child $at",children.getJSONObject(at),actual.children[at]) }
                evaluated++
            }
        }
        assertEquals(evaluations,evaluated)
    }

    @Test fun staccatoAfterSlideChangesOnlyTheOriginalRangeIn56NativeGraphs() {
        val cases=fixture("native-slide-staccato.json").getJSONArray("cases");assertEquals(56,cases.length())
        repeat(cases.length()) { at ->
            val case=cases.getJSONObject(at);val score=load(Base64.getDecoder().decode(case.getString("gp")))
            val source=notes(score)[0];val node=NativeSlideScoreGraph(score).get(source)!!
            val plan=NativeSlideInterpreter.plan(node,0,480)
            val end=NativeStaccato.endTick(GpRseProperties.note(source)!!.accentFlags ?: 0,480,plan.root.start)
            val expected=case.getJSONObject("plan")
            segment("staccato $at",expected.getJSONObject("root"),if(end == null)plan.root else plan.root.copy(end=end))
            val children=expected.getJSONArray("children");assertEquals(children.length(),plan.children.size)
            repeat(children.length()) { child ->segment("staccato $at child $child",children.getJSONObject(child),plan.children[child]) }
        }
    }
}
