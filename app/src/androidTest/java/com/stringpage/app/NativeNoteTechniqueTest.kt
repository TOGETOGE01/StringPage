@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeNoteTechniqueTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).bufferedReader().use { JSONObject(it.readText()) }
    private fun packed(xml: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { out ->
            out.putNextEntry(ZipEntry("Content/score.gpif")); out.write(xml.toByteArray()); out.closeEntry()
        }
    }.toByteArray()
    private fun load(bytes: ByteArray) = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()),Settings())
        .also { GpRseProperties.bind(it,GpRseProperties.read(bytes)) }

    @Test fun actualGpifBooleansMatch184OriginalReaderResultsIncludingLexicalBoundaries() {
        val bytes=Base64.getDecoder().decode(fixture("native-note-techniques.json").getJSONArray("cases").getJSONObject(0).getString("gp"))
        val xml=ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry; it.readBytes().toString(Charsets.UTF_8) }
        val score=load(bytes);val note=score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        val target=Regex("<Note id=\"1000\">[\\s\\S]*?</Note>").find(xml)!!.value
        val rows=fixture("native-note-booleans.json").getJSONArray("rows");assertEquals(184,rows.length())
        repeat(rows.length()) { at ->
            val row=rows.getJSONObject(at);val name=row.getString("property")
            val initial=if(row.getBoolean("initial")) "<Property name=\"$name\"><Enable/></Property>" else ""
            val content=if(row.isNull("content")) "" else "<Property name=\"$name\">${row.getString("content")}</Property>"
            val changed=xml.replace(target,target.replace("</Properties>","$initial$content</Properties>"))
            GpRseProperties.bind(score,GpRseProperties.read(packed(changed)))
            val source=GpRseProperties.note(note)!!
            val actual=when(name) { "Tapped" -> source.tapped; "LeftHandTapped" -> source.leftHandTapped
                "HopoOrigin" -> source.hopoOrigin; "HopoDestination" -> source.hopoDestination; else -> error(name) }
            assertEquals("$at $name ${row.getString("kind")}",row.getBoolean("result"),actual)
        }
    }

    @Test fun nativeCoreNotesInterpretersCueFallbackAndOrderedFloatSlotsMatch435Evaluations() {
        val cases=fixture("native-note-techniques.json").getJSONArray("cases");assertEquals(19,cases.length())
        var count=0
        repeat(cases.length()) { ci ->
            val case=cases.getJSONObject(ci);val score=load(Base64.getDecoder().decode(case.getString("gp")))
            val rows=case.getJSONArray("rows")
            repeat(rows.length()) { ri ->
                val row=rows.getJSONObject(ri);val path=row.getJSONArray("path")
                val note=score.tracks[path.getInt(0)].staves[path.getInt(1)].bars[path.getInt(2)]
                    .voices[path.getInt(3)].beats[path.getInt(4)].notes[path.getInt(5)]
                val label="${case.getString("name")} $path";val source=GpRseProperties.note(note)!!
                assertEquals(label,row.getInt("string")+1,note.string.toInt());assertEquals(label,row.getInt("fret"),note.fret.toInt())
                assertEquals(label,row.getBoolean("hopo"),source.hopoDestination)
                assertEquals(label,row.getBoolean("left"),source.leftHandTapped)
                assertEquals(label,row.getBoolean("tapped"),source.tapped)
                assertEquals(label,if(row.isNull("previousFret"))null else row.getInt("previousFret"),NativeNoteTechnique.previousFret(note))
                val evaluations=row.getJSONArray("evaluations")
                repeat(evaluations.length()) { ei ->
                    val expected=evaluations.getJSONObject(ei)
                    val actual=NativeNoteTechnique.apply(note,expected.getDouble("dynamic").toFloat(),expected.getInt("process"))
                    assertFalse(label,actual.incompleteSource);assertEquals(label,expected.getInt("cuePoint"),actual.cuePoint)
                    val slots=expected.getJSONArray("slots")
                    repeat(slots.length()) { slot -> assertEquals("$label slot $slot",
                        if(slots.isNull(slot))null else slots.getDouble(slot).toFloat().toRawBits(),actual.modifiers[slot]?.toRawBits()) }
                    assertEquals(label,expected.getLong("productBits").toInt(),actual.amplitude.toRawBits())
                    val cues=expected.getJSONArray("cues")
                    assertEquals(label,expected.getLong("startingFrame"),NativeSampleStart.initialFrame(actual.cuePoint,
                        (0..2).associateWith { cues.getLong(it) },expected.getLong("loopStart")))
                    count++
                }
            }
        }
        assertEquals(435,count)
    }

    @Test fun actualConstructed44kAnd96kStreamsRetainNativeCueLookupAndRawLoopCap() {
        var count=0
        for(name in listOf("native-cue-keys.json","native-cue-keys-96k.json")) {
            val rows=fixture(name).getJSONArray("rows")
            repeat(rows.length()) { at ->
                val row=rows.getJSONObject(at);val cues=row.getJSONArray("cues")
                val stream=NativeSampleStart.decoderStreamCuePoints((0..1).map { cues.getLong(it) })
                repeat(3) { key -> assertEquals("$name $at cue $key",row.getJSONArray("streamCues").getLong(key),stream[key]) }
                val loop=RseLoop(0,0,6000,12000,0,0)
                assertEquals(row.getLong("streamLoopStart"),NativeStreamPolicy.forwardLoop("other.ogg",listOf(loop),20000,row.getInt("rate"))!!.start)
                assertEquals(4000L,NativeSampleStart.initialFrame(0,stream,row.getLong("streamLoopStart")))
                assertEquals(4000L,NativeSampleStart.initialFrame(1,stream,row.getLong("streamLoopStart")))
                assertEquals(0L,NativeSampleStart.initialFrame(2,stream,row.getLong("streamLoopStart")))
                count++
            }
        }
        assertEquals(26,count)
    }
}
