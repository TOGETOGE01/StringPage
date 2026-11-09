@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.NotationMode
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.util.Base64
import kotlin.math.roundToLong
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class NativeBendPlaybackTest {
    private fun cases(asset: String)=InstrumentationRegistry.getInstrumentation().context.assets.open(asset)
        .bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }
    private fun load(row: JSONObject,settings: Settings=Settings()): Score {
        val bytes=Base64.getDecoder().decode(row.getString("gp"))
        return ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()),settings).also { GpRseProperties.bind(it,GpRseProperties.read(bytes)) }
    }
    private fun first(score: Score)=score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
    private fun compare(row: JSONObject,plan: NativeBendInterpreter.Plan?) {
        val expected=row.getJSONObject("graph");assertEquals(row.getString("name"),expected.getBoolean("predicate"),plan!=null)
        val regions=expected.getJSONArray("regions");assertEquals(regions.length(),plan?.regions?.size ?: 0)
        for(j in 0 until regions.length()) {
            val r=regions.getJSONObject(j);val actual=plan!!.regions[j];val points=r.getJSONArray("points")
            assertEquals(r.getInt("start"),actual.start);assertEquals(r.getInt("end"),actual.end)
            assertEquals(points.length(),actual.curve.points.size)
            for(k in 0 until points.length()) {
                assertEquals(points.getJSONArray(k).getDouble(0).toFloat().toRawBits(),actual.curve.points[k].position.toRawBits())
                assertEquals(points.getJSONArray(k).getDouble(1).toFloat().toRawBits(),actual.curve.points[k].value.toRawBits())
            }
        }
        val samples=expected.getJSONArray("samples")
        for(j in 0 until samples.length()) { val s=samples.getJSONObject(j)
            assertEquals(row.getString("name")+" tick "+s.getInt("tick"),s.getLong("bits").toInt(),(plan?.value(s.getInt("tick")) ?: 0f).toRawBits()) }
    }
    @Test fun originalGpifToBendGraphMatches183NativeScoresInBothDisplayModes() {
        var count=0
        for(mode in listOf(NotationMode.GuitarPro,NotationMode.SongBook)) {
            val settings=Settings().apply { notation.notationMode=mode }
            for(asset in listOf("native-bend-graphs.json","native-bend-ties.json")) {
                val rows=cases(asset)
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i);val score=load(row,settings);val note=first(score)
                    val source=GpRseProperties.note(note);val model=note.bendPoints?.toList()?.map { it.offset to it.value }
                    compare(row,NativeBendScoreGraph(score).plan(note))
                    assertSame(source,GpRseProperties.note(note));assertEquals(model,note.bendPoints?.toList()?.map { it.offset to it.value });count++
                }
            }
        }
        assertEquals(366,count)
    }
    @Test fun realPerformanceRetainsTheOriginalNativeBendAndRemovesTheReplacedMidiCurve() {
        val rows=cases("native-bend-graphs.json")
        val row=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name")=="grid-0,0,100" }
        for(mode in listOf(NotationMode.GuitarPro,NotationMode.SongBook)) {
            val settings=Settings().apply { notation.notationMode=mode };val score=load(row,settings);val written=first(score)
            val raw=GpRseProperties.note(written)!!;val model=written.bendPoints!!.toList().map { it.offset to it.value }
            val performance=RseSlidePlayback.compile(score,settings)
            val event=performance.notes.first { it.sourceNote?.let(GpRseProperties::note)?.sourceId==raw.sourceId }
            assertNotNull(event.nativeBend);compare(row,event.nativeBend)
            assertEquals(raw,GpRseProperties.note(event.sourceNote!!));assertFalse(event.sourceNote!!.hasBend)
            assertTrue(written.hasBend);assertEquals(model,written.bendPoints!!.toList().map { it.offset to it.value })
            assertTrue(performance.pitches.none { it.midi==event.midi && it.value!=8192.0 })
            assertEquals(45,event.midi);assertEquals(45,FactoryNoteAdapter.adapt(event,true).request.coordinate.midi)
        }
    }
    @Test fun theBendClockUsesNativeTicksAcrossTempoChangesAndRoundedPcmBoundaries() {
        val performance=ScorePerformance(emptyList(),emptyList(),emptyList(),emptyList(),
            listOf(ScorePerformance.TempoPoint(0.0,0.0,120.0),ScorePerformance.TempoPoint(960.0,500.0,60.0)),emptyList(),1920.0,1500.0,0.0)
        for(tick in 0..960) {
            val frame=(performance.tickToMs(tick*2.0)*44100/1000.0).roundToLong()
            assertEquals(tick,RseBendClock.tick(performance,0.0,0,frame,44100))
            if(tick>0) assertEquals(tick-1,RseBendClock.tick(performance,0.0,0,frame-1,44100))
        }
    }
    @Test fun actualPerformanceAppliesNativeStaccatoAfterBendWithoutRescalingItsCurve() {
        val rows=cases("native-bend-staccato.json");assertEquals(12,rows.length())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val performance=RseSlidePlayback.compile(load(row));val event=performance.notes.first()
            assertNotNull(event.nativeBend);compare(row,event.nativeBend)
            assertEquals(row.getString("name"),row.getJSONObject("graph").getInt("end")*2.0,event.endTick,.00001)
        }
    }
    @Test fun actualPreparedAudioBendsOneChordVoiceAndPreservesTheOtherVoice() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bankFile=File(context.filesDir,"rse-reference.gp7bank")
        assumeTrue("The private reference bank is provisioned outside the APK",bankFile.isFile)
        val rows=cases("native-bend-graphs.json")
        val row=(0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name")=="grid-0,0,100" }
        val bytes=Base64.getDecoder().decode(row.getString("gp"))
        val xml=ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry;it.readBytes() }
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val master=doc.getElementsByTagName("MasterBars").item(0)
        while(master.childNodes.length>1) master.removeChild(master.lastChild)
        val notes=doc.getElementsByTagName("Notes").let { all -> (0 until all.length).map { all.item(it) }.first { it.parentNode.nodeName=="GPIF" } }
        val authored=(0 until notes.childNodes.length).map { notes.childNodes.item(it) }.filter { it.nodeName=="Note" }
        val second=authored[1] as org.w3c.dom.Element
        val props=second.getElementsByTagName("Property")
        for(i in 0 until props.length) {
            val property=props.item(i) as org.w3c.dom.Element
            val value=when(property.getAttribute("name")) { "String" -> "1";"Fret" -> "5";"Midi" -> "50";else -> null }
            if(value!=null) (0 until property.childNodes.length).map { property.childNodes.item(it) }
                .first { it.nodeType==org.w3c.dom.Node.ELEMENT_NODE }.textContent=value
        }
        val beats=doc.getElementsByTagName("Beat")
        val refs=(0 until beats.length).map { beats.item(it) as org.w3c.dom.Element }.map { it.getElementsByTagName("Notes").item(0) }
        refs[0].textContent=(authored[0] as org.w3c.dom.Element).getAttribute("id")+" "+second.getAttribute("id")
        refs[1].textContent=""
        val content=ByteArrayOutputStream();TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc),StreamResult(content))
        val packed=ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { out ->
            out.putNextEntry(ZipEntry("Content/score.gpif"));out.write(content.toByteArray());out.closeEntry() } }.toByteArray()
        val score=ScoreLoader.loadScoreFromBytes(Uint8Array(packed.asUByteArray()),Settings()).also { GpRseProperties.bind(it,GpRseProperties.read(packed)) }
        val performance=RseSlidePlayback.compile(score)
        val chord=performance.notes.filter { it.startTick==performance.notes.first().startTick }
        assertEquals(listOf(45,50),chord.map { it.midi });assertEquals(chord[0].channel,chord[1].channel)
        assertNotNull(chord[0].nativeBend);assertNull(chord[1].nativeBend)
        fun subset(selected: List<ScorePerformance.NoteEvent>)=ScorePerformance(selected,performance.pitches,performance.controls,
            performance.programs,performance.tempos,performance.bars,performance.endTick,performance.durationMs,performance.tickShift,
            performance.rseSyncOffsetMs,performance.rseFirstCueMs,performance.rsePartialTechniques)
        val bank=BankLibrary.Item(bankFile,Gp7Bank.open(bankFile).readMetadata())
        val files=ArrayList<File>()
        fun render(selected: List<ScorePerformance.NoteEvent>): ShortArray {
            val file=File.createTempFile("native-bend-audio-",".wav",context.cacheDir);files.add(file)
            val result=FactoryScoreRenderer.render(subset(selected),mapOf(FactoryScoreRenderer.Route(0) to bank),file)
            assertTrue(result.peak>.001f);assertEquals(0L,result.clippedSamples)
            val bytes=file.readBytes();val pcm=ByteBuffer.wrap(bytes,44,bytes.size-44).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray((bytes.size-44)/2) { pcm.short }
        }
        try {
            val mixed=render(chord);val bent=render(listOf(chord[0]));val other=render(listOf(chord[1]))
            val flat=render(listOf(chord[0].copy(nativeBend=null)))
            assertTrue("The actual generated PCM must contain the bend",bent.indices.count { bent[it]!=flat[it] }>100)
            assertEquals("The other chord voice must remain unchanged",0,mixed.indices.count { kotlin.math.abs(mixed[it].toInt()-bent[it].toInt()-other[it].toInt())>2 })
        } finally { files.forEach { it.delete() } }
    }
}
