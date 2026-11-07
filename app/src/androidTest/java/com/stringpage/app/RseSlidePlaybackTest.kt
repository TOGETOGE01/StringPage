@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.AlphaTabView
import alphaTab.synth.IExternalMediaSynthOutput
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.importer.AlphaTexImporter
import alphaTab.model.Note
import alphaTab.model.BackingTrack
import alphaTab.model.Score
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class RseSlidePlaybackTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> ui(action: () -> T): T {
        val result=AtomicReference<T>();instrument.runOnMainSync { result.set(action()) };return result.get()
    }
    private fun await(description: String,check: () -> Boolean) {
        val until=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis() < until) { if(ui(check)) return;SystemClock.sleep(40) }
        fail(description)
    }
    private fun source(name: String,accent: Int? = null): Score {
        val cases=InstrumentationRegistry.getInstrumentation().context.assets.open("native-slide-graphs.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("cases")
        val case=(0 until cases.length()).map { cases.getJSONObject(it) }.single { it.getString("name") == name }
        val bytes=Base64.getDecoder().decode(case.getString("gp"))
        val xml=ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry;it.readBytes() }
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val masterBars=doc.getElementsByTagName("MasterBars").item(0)
        while(masterBars.childNodes.length > 1) masterBars.removeChild(masterBars.lastChild)
        if(accent != null) doc.getElementsByTagName("Note").item(0).appendChild(doc.createElement("Accent").apply { textContent=accent.toString() })
        val content=ByteArrayOutputStream();TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc),StreamResult(content))
        val packed=ByteArrayOutputStream().also { buffer ->ZipOutputStream(buffer).use { out ->
            out.putNextEntry(ZipEntry("Content/score.gpif"));out.write(content.toByteArray());out.closeEntry() } }.toByteArray()
        return ScoreLoader.loadScoreFromBytes(Uint8Array(packed.asUByteArray()),Settings()).also { GpRseProperties.bind(it,GpRseProperties.read(packed)) }
    }
    private fun notes(score: Score): List<Note> = score.tracks.toList().flatMap { it.staves.toList() }.flatMap { it.bars.toList() }
        .flatMap { it.voices.toList() }.flatMap { it.beats.toList() }.flatMap { it.notes.toList() }

    @Test fun snapshotExpandsNativeSamplesAndFreezesParentFretWithoutChangingTheDocument() {
        val score=source("pull-8");val original=notes(score)
        val audio=Uint8Array(byteArrayOf(1,2,3,4).asUByteArray())
        score.backingTrack=BackingTrack().apply { rawAudioFile=audio }
        val flags=original.map { GpRseProperties.note(it)!!.slideFlags }
        val slides=original.map { it.slideOutType to it.slideInType }
        val performance=RseSlidePlayback.compile(score)
        val voices=performance.notes.filter { it.slideVoice != null }
        assertEquals(listOf(45,46,47,48),voices.map { it.midi })
        assertEquals(listOf(0.0,768.0,818.0,868.0),voices.map { it.startTick })
        assertEquals(listOf(768.0,818.0,868.0,960.0),voices.map { it.endTick })
        assertEquals(512L,voices[0].slideVoice!!.state.fadeOutFrames)
        for(event in voices) {
            val coordinate=FactoryNoteAdapter.adapt(event,true).request.coordinate
            assertEquals(event.midi,coordinate.midi);assertEquals(5,coordinate.fret)
            if(event.slideVoice!!.generated) {
                assertEquals(3,event.slideVoice!!.state.startingCuePoint)
                assertEquals(1024L,event.slideVoice!!.state.userDefinedFrame)
                assertEquals(512L,event.slideVoice!!.state.fadeInFrames)
            }
        }
        assertEquals(flags,original.map { GpRseProperties.note(it)!!.slideFlags })
        assertEquals(slides,original.map { it.slideOutType to it.slideInType })
        assertTrue(voices.all { it.sourceNote !in original })
        assertSame(audio,score.backingTrack!!.rawAudioFile)
        assertNull(voices[0].sourceNote!!.beat.voice.bar.staff.track.score.backingTrack)
    }

    @Test fun anticipatorySlideHasRealLeadTimeAndKeepsBothPerformanceClocksInvertible() {
        val score=source("pull-16");val original=ScorePerformance.compile(score)
        val performance=RseSlidePlayback.compile(score)
        val voices=performance.notes.filter { it.slideVoice != null }
        assertEquals(listOf(42,43,44,45),voices.take(4).map { it.midi })
        assertEquals(listOf(0.0,32.0,64.0,96.0),voices.take(4).map { it.startTick })
        assertEquals(96.0,performance.tickShift,0.0)
        assertTrue(performance.rseFirstCueMs > 0)
        assertEquals(performance.tickToMs(96.0),performance.rseFirstCueMs,.0001)
        assertTrue(performance.controls.filter { it.tick == 0.0 }.isNotEmpty())
        assertEquals((96.0-original.tickShift)*60000.0/(960.0*performance.tempos.first().bpm),performance.rseSyncOffsetMs,.0001)
        assertTrue(performance.pitches.none { it.midi == 45 && it.value != 8192.0 })
        for(tick in listOf(0.0,32.0,96.0,960.0,3500.0)) assertEquals(tick,performance.msToTick(performance.tickToMs(tick)),.0001)
    }

    @Test fun actualSlidePerformanceAppliesStaccatissimoAfterGeneratingTheTransition() {
        val performance=RseSlidePlayback.compile(source("pull-8",2))
        val voices=performance.notes.filter { it.slideVoice != null }
        assertEquals(240.0,voices.first().endTick,0.0)
        assertEquals(listOf(768.0,818.0,868.0),voices.drop(1).map { it.startTick })
        assertEquals(listOf(818.0,868.0,960.0),voices.drop(1).map { it.endTick })
    }

    @Test fun longTieChainHasTheWrittenDurationAtEveryPositionWithoutRecursiveTraversal() {
        val score=AlphaTexImporter().run { initFromString(". "+List(2048) { "0.1.4" }.joinToString(" "),Settings());readScore() }
        val all=notes(score);assertEquals(2048,all.size)
        for(at in 0 until all.lastIndex) { all[at].tieDestination=all[at+1];all[at+1].tieOrigin=all[at];all[at+1].isTieDestination=true }
        val graph=NativeSlideScoreGraph(score)
        for(at in all.indices) { assertEquals(2048-at,graph.get(all[at])!!.soundingNumerator);assertEquals(1,graph.get(all[at])!!.soundingDenominator) }
    }

    @Test fun realPreparedWavContainsTheSlideIntroductionAndPublishesItsSynchronizationOffset() {
        val bankFile=File(context.filesDir,"rse-reference.gp7bank")
        assumeTrue("Optional original bank is provisioned outside both APKs",bankFile.isFile)
        val score=source("pull-16");val performance=RseSlidePlayback.compile(score)
        val bank=BankLibrary.Item(bankFile,Gp7Bank.open(bankFile).readMetadata())
        val file=File.createTempFile("native-slide-render-",".wav",context.cacheDir)
        try {
            val result=FactoryScoreRenderer.render(performance,mapOf(FactoryScoreRenderer.Route(0) to bank),file)
            assertEquals(performance.notes.size,result.noteCount);assertTrue(result.peak>.001f)
            assertEquals(performance.rseFirstCueMs,result.firstCueMs,0.0);assertEquals(performance.rseSyncOffsetMs,result.syncOffsetMs,0.0)
            val introFrames=kotlin.math.floor(result.firstCueMs*44.1).toInt();assertTrue(introFrames>128)
            val pcm=ByteBuffer.wrap(file.readBytes(),44,introFrames*4).order(ByteOrder.LITTLE_ENDIAN)
            assertTrue("Native anticipatory voices must be audible before the first written note",(0 until introFrames*2).count { pcm.short != 0.toShort() } > 100)
        } finally { file.delete() }
    }

    @Test fun actualExternalPlayerKeepsTheSlideLeadInSynchronizedAndReplaysItWhenSeekingTheFirstCue() {
        val bankFile=File(context.filesDir,"rse-reference.gp7bank")
        assumeTrue("Optional original bank is provisioned outside both APKs",bankFile.isFile)
        val document=File.createTempFile("slide-document-",".gp",context.filesDir)
        document.writeBytes(ScoreExport.guitarProBytes(source("pull-16")))
        val activity=instrument.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val api=ui { activity.findViewById<AlphaTabView>(R.id.score_view).api }
        val button=ui { activity.findViewById<Button>(R.id.play_pause) }
        var file: File?=null
        try {
            ui { activity.openUri(Uri.fromFile(document)) }
            await("Slide document ready") { button.isEnabled && api.score?.masterBars?.length == 1.0 }
            val loaded=ui { api.score!! };val performance=RseSlidePlayback.compile(loaded)
            val bank=BankLibrary.Item(bankFile,Gp7Bank.open(bankFile).readMetadata())
            file=File.createTempFile("slide-player-",".wav",context.cacheDir)
            val result=FactoryScoreRenderer.render(performance,mapOf(FactoryScoreRenderer.Route(0) to bank),file)
            assertTrue(result.firstCueMs > 0)
            ui { activity.acceptFactoryAudio(loaded,result) }
            await("Native slide playback ready") { button.isEnabled && api.player?.output is IExternalMediaSynthOutput }
            val handler=ui { (api.player!!.output as IExternalMediaSynthOutput).handler as EmbeddedAudio }
            val field=EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }
            val media=ui { field.get(handler) as MediaPlayer }
            val seek=result.firstCueMs+400.0
            ui { handler.seekTo(seek) }
            await("Physical audio seek reaches its offset") { media.currentPosition >= seek-40 }
            await("Score clock subtracts the prepared lead-in") { kotlin.math.abs(api.timePosition-(media.currentPosition-result.syncOffsetMs)) < 35.0 }
            ui { handler.seekTo(result.firstCueMs) }
            await("Seeking the first written cue rewinds through the audible introduction") { media.currentPosition < 20 }
            ui { button.performClick() }
            await("Prepared slide audio advances after rewind") { media.currentPosition > result.firstCueMs+150 }
            ui { assertEquals(media.currentPosition-result.syncOffsetMs,api.timePosition,100.0);api.pause() }
        } finally { ui { activity.finish() };instrument.waitForIdleSync();file?.delete();document.delete() }
    }
}
