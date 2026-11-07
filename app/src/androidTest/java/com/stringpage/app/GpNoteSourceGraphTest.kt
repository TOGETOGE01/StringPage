@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.NotationMode
import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.AlphaTexImporter
import alphaTab.importer.ScoreLoader
import alphaTab.model.AccentuationType
import alphaTab.model.JsonConverter
import alphaTab.model.Note
import alphaTab.model.Score
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class GpNoteSourceGraphTest {
    private fun Element.children(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.tagName == tag }
    private fun Element.child(tag: String): Element = children(tag).single()
    private fun field(note: Element, tag: String, value: String) {
        note.children(tag).forEach { note.removeChild(it) }
        note.appendChild(note.ownerDocument.createElement(tag).apply { textContent = value })
    }
    private fun flag(note: Element, name: String) {
        val doc = note.ownerDocument
        val properties = note.children("Properties").firstOrNull() ?: doc.createElement("Properties").also { note.appendChild(it) }
        properties.appendChild(doc.createElement("Property").apply { setAttribute("name", name); appendChild(doc.createElement("Enable")) })
    }
    private fun notes(doc: Document) = doc.documentElement.child("Notes").children("Note")
    private fun allNotes(score: Score): List<Note> = score.tracks.toList().flatMap { t -> t.staves.toList().flatMap { s ->
        s.bars.toList().flatMap { b -> b.voices.toList().flatMap { v -> v.beats.toList().flatMap { it.notes.toList() } } } } }
    private fun source(tex: String = ". (0.1 2.2).4 3.1.4 r.4 r.4", edit: (Document) -> Unit = {}): ByteArray {
        val score = AlphaTexImporter().run { initFromString(tex, Settings()); readScore() }
        val members = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(ScoreExport.guitarProBytes(score))).use { input ->
            while (true) { val entry = input.nextEntry ?: break; members[entry.name] = input.readBytes(); input.closeEntry() }
        }
        val name = members.keys.single { it.endsWith("score.gpif") }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(members.getValue(name)))
        edit(doc)
        val xml = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(xml)); members[name] = xml.toByteArray()
        return ByteArrayOutputStream().also { target -> ZipOutputStream(target).use { output ->
            for ((path, bytes) in members) { output.putNextEntry(ZipEntry(path)); output.write(bytes); output.closeEntry() }
        } }.toByteArray()
    }
    private fun load(bytes: ByteArray, settings: Settings = Settings()): Score {
        val score = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), settings)
        GpRseProperties.bind(score, GpRseProperties.read(bytes)); return score
    }

    @Test fun tappedAndHopoFlagsStayOnTheirOwnNotesInsideAChord() {
        val bytes = source { doc ->
            val n = notes(doc); flag(n[0], "Tapped"); flag(n[1], "LeftHandTapped")
            flag(n[1], "HopoOrigin"); flag(n[2], "HopoDestination")
        }
        val score = load(bytes); val n = allNotes(score)
        assertTrue(n[0].beat.tap) // The upstream model still collapses the beat.
        assertTrue(GpRseProperties.note(n[0])!!.tapped)
        assertFalse(GpRseProperties.note(n[1])!!.tapped)
        assertTrue(GpRseProperties.note(n[1])!!.leftHandTapped)
        assertTrue(GpRseProperties.note(n[1])!!.hopoOrigin)
        assertTrue(GpRseProperties.note(n[2])!!.hopoDestination)
    }

    @Test fun arbitrarySourceIdsAndReversedDeclarationsFollowReferencesRatherThanGeneratedIds() {
        val bytes = source { doc ->
            val list = notes(doc); val old = list.mapIndexed { i, n -> n.getAttribute("id") to "source-note-$i" }.toMap()
            list.forEach { it.setAttribute("id", old.getValue(it.getAttribute("id"))) }
            val beats = doc.documentElement.child("Beats").children("Beat")
            for (beat in beats) for (ref in beat.children("Notes")) ref.textContent = ref.textContent.trim().split(Regex("\\s+"))
                .joinToString(" ") { old.getValue(it) }
            flag(list[1], "Tapped")
            val parent = doc.documentElement.child("Notes")
            list.forEach { parent.removeChild(it) }; list.reversed().forEach { parent.appendChild(it) }
        }
        val first = load(bytes)
        val second = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()),Settings())
        allNotes(second).forEach { it.id = -999.0 }
        GpRseProperties.bind(second,GpRseProperties.read(bytes))
        val a = allNotes(first); val b = allNotes(second)
        assertNotEquals(a[0].id, b[0].id)
        assertEquals(listOf("source-note-0", "source-note-1", "source-note-2"), a.map { GpRseProperties.note(it)!!.sourceId })
        assertEquals(listOf(false,true,false), b.map { GpRseProperties.note(it)!!.tapped })
    }

    @Test fun rawCombinedAccentAndStrongAntiAccentDriveActualFactorySelection() {
        val bytes = source(". 0.6.4 0.6.4 r.4 r.4") { doc ->
            val n = notes(doc); field(n[0], "Accent", "28"); field(n[1], "AntiAccent", "Strong")
            doc.documentElement.child("Beats").children("Beat").forEach { it.child("Dynamic").textContent="MF" }
        }
        val score = load(bytes); val n = allNotes(score)
        assertEquals(AccentuationType.Tenuto,n[0].accentuated)
        assertFalse(n[1].isGhost) // Strong is completely omitted by the upstream parser.
        val wave = RseWave("none","test",0.0,0.0,BankEntry("test",0,0))
        fun sample(dynamic: String) = RseSample(listOf("pick","sustain","down"),40,1,dynamic,"none",listOf(wave.copy(file=dynamic)))
        val bank = RseSoundBank("test","","steelGuitar","",listOf(40,45,50,55,59,64),listOf(sample("mf"),sample("ff"),sample("mp")))
        val selected = ScorePerformance.compile(score).notes.map { FactorySampleSelector(bank).select(it) }
        assertEquals("ff",selected[0].wave.file)
        assertEquals("mp",selected[1].wave.file)
        assertEquals(1f,selected[0].dynamicCorrection,0f)
        assertEquals(1f/2.15f,selected[1].dynamicCorrection,.0000001f)
    }

    @Test fun nativeAntiAccentStringsMatch173OriginalCasesIncludingWhitespaceAndNonAscii() {
        val rows = InstrumentationRegistry.getInstrumentation().context.assets.open("native-anti-accent.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("rows")
        assertEquals(173,rows.length())
        repeat(rows.length()) { val row=rows.getJSONObject(it)
            assertEquals("enum $it",row.getInt("value"),GpNoteSourceGraph.antiAccentFromString(row.getString("text"))) }
    }

    @Test fun songBookBendContinuationKeepsItsOriginalSourceIdentity() {
        val bytes = source(". 0.1.4 2.1.4 r.4 r.4") { doc ->
            val first = notes(doc)[0]; flag(first,"Tapped"); flag(first,"Bended")
            val properties = first.child("Properties")
            fun property(name: String,value: String) { properties.appendChild(doc.createElement("Property").apply {
                setAttribute("name",name); appendChild(doc.createElement("Float").apply { textContent=value }) }) }
            property("BendOriginValue","0"); property("BendDestinationValue","100")
        }
        val settings = Settings().apply { notation.notationMode = NotationMode.SongBook }
        val score = load(bytes,settings); val n = allNotes(score)
        assertEquals(3,n.size)
        assertTrue(n[1].isTieDestination)
        assertEquals(GpRseProperties.note(n[0]),GpRseProperties.note(n[1]))
        assertTrue(GpRseProperties.note(n[0])!!.tapped)
        assertFalse(GpRseProperties.note(n[2])!!.tapped)
    }

    @Test fun ownModelCopyTransfersSourceFieldsAfterAnIntentionalFretChange() {
        val score = load(source { flag(notes(it)[1],"Tapped") })
        val copy = JsonConverter.jsObjectToScore(JsonConverter.scoreToJsObject(score),Settings())
        allNotes(copy)[1].fret += 1
        GpRseProperties.transfer(score,copy)
        assertEquals(allNotes(score).map { GpRseProperties.note(it) },allNotes(copy).map { GpRseProperties.note(it) })
        assertNotEquals(allNotes(score)[1].fret,allNotes(copy)[1].fret)
    }

    @Test fun bendContinuationAndOwnCopyUseTheWrittenPreviousNoteForPullOffAmplitude() {
        val bytes=source(". 5.1.4 3.1.4 r.4 r.4") { doc ->
            val current=notes(doc)[1];flag(current,"Bended");flag(current,"HopoDestination")
            for((name,value) in listOf("BendOriginValue" to "0","BendDestinationValue" to "100")) {
                current.child("Properties").appendChild(doc.createElement("Property").apply {
                    setAttribute("name",name);appendChild(doc.createElement("Float").apply { textContent=value }) })
            }
        }
        val settings=Settings().apply { notation.notationMode=NotationMode.SongBook }
        val score=load(bytes,settings);val n=allNotes(score);assertEquals(3,n.size)
        assertSame(n[1],GpRseProperties.original(n[2]))
        for(note in n.drop(1)) {
            assertEquals(5,NativeNoteTechnique.previousFret(note))
            assertEquals(.7f,NativeNoteTechnique.apply(note).amplitude,0f)
        }
        val copy=JsonConverter.jsObjectToScore(JsonConverter.scoreToJsObject(score),settings)
        GpRseProperties.transfer(score,copy);val copied=allNotes(copy)
        assertSame(copied[1],GpRseProperties.original(copied[2]))
        assertNotSame(n[1],GpRseProperties.original(copied[2]))
        assertEquals(.7f,NativeNoteTechnique.apply(copied[2]).amplitude,0f)
    }

    @Test fun preparedPcmAppliesActualPerNoteCueFallbackAndLeftHandAmplitude() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bankFile=File(context.filesDir,"rse-reference.gp7bank")
        assumeTrue("Optional original bank is provisioned outside both APKs",bankFile.isFile)
        val bank=Gp7Bank.open(bankFile).readMetadata();val item=BankLibrary.Item(bankFile,bank)
        val outputs=ArrayList<ShortArray>();val selected=ArrayList<String>()
        for(flags in listOf(emptyList(),listOf("Tapped"),listOf("LeftHandTapped"),listOf("LeftHandTapped","Tapped"))) {
            val score=load(source(". 0.1.4 r.4 r.4 r.4") { doc -> flags.forEach { flag(notes(doc)[0],it) } })
            val performance=ScorePerformance.compile(score);assertEquals(1,performance.notes.size)
            val selection=FactorySampleSelector(bank).select(performance.notes.single());selected.add(selection.wave.file)
            val metadata=Gp7Bank.open(bankFile).readAudioMetadata(selection.wave.entry)
            assertTrue(metadata.sampler!!.loops[0].start>4000)
            assertTrue(metadata.nativeCues.isEmpty()) // Original "cue " keys are inspection-only.
            val output=File.createTempFile("technique-render-",".wav",context.cacheDir)
            try {
                val result=FactoryScoreRenderer.render(performance,mapOf(FactoryScoreRenderer.Route(0) to item),output)
                assertTrue(result.peak>.001f);assertEquals(0L,result.clippedSamples)
                val pcm=ByteBuffer.wrap(output.readBytes(),44,4096).order(ByteOrder.LITTLE_ENDIAN)
                outputs.add(ShortArray(2048) { pcm.short })
            } finally { output.delete() }
        }
        assertEquals(1,selected.distinct().size)
        assertFalse("Tapping must skip the original attack source frames",outputs[0].contentEquals(outputs[1]))
        assertTrue("LHT and TAP share the observed default fallback",outputs[2].contentEquals(outputs[3]))
        assertTrue(outputs[1].any { kotlin.math.abs(it.toInt())>100 })
        repeat(2048) { at -> assertEquals("Prepared LHT amplitude at $at",outputs[1][at]*.8f,outputs[2][at].toFloat(),1.1f) }
    }

    @Test fun graphMismatchCannotSilentlyAttachPropertiesToAnotherNote() {
        val a = source(); val b = source(". 0.1.4 2.1.4 r.4 r.4")
        val wrong = ScoreLoader.loadScoreFromBytes(Uint8Array(b.asUByteArray()),Settings())
        try { GpRseProperties.bind(wrong,GpRseProperties.read(a));fail("Mismatched source graph accepted") }
        catch (_:GpRsePropertiesException) { }
        assertTrue(allNotes(wrong).all { GpRseProperties.note(it)==null })
    }

    @Test fun originalGp8FixtureBindsEveryImportedNoteWithoutDependingOnXmlOrder() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue("Optional GP8 fixture is downloaded separately",assets.list("")?.contains("gp8-compatibility.gp")==true)
        val bytes=assets.open("gp8-compatibility.gp").use { it.readBytes() }
        val score=load(bytes);val n=allNotes(score)
        assertTrue(n.isNotEmpty());assertTrue(n.all { GpRseProperties.note(it)!=null })
    }

    @Test fun concurrentScoreLoadsKeepIndependentNoteProperties() {
        val a=source { flag(notes(it)[0],"Tapped") };val b=source { flag(notes(it)[1],"Tapped") }
        val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=(0 until 12).map { i -> pool.submit<Boolean> {
                val score=load(if(i%2==0)a else b)
                allNotes(score).map { GpRseProperties.note(it)!!.tapped } == if(i%2==0)listOf(true,false,false) else listOf(false,true,false)
            } }
            assertTrue(futures.all { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun rebindingOrAnExplicitFailureClearsPreviouslyAttachedNoteFields() {
        val score=load(source { flag(notes(it)[0],"Tapped") });val first=allNotes(score)[0]
        assertNotNull(GpRseProperties.note(first))
        val props=GpRseProperties.get(score)!!
        GpRseProperties.bind(score,props.copy(noteGraph=null))
        assertNull(GpRseProperties.note(first))
        GpRseProperties.bind(score,props);assertNotNull(GpRseProperties.note(first))
        GpRseProperties.bindFailure(score,"invalid source")
        assertNull(GpRseProperties.note(first));assertEquals("invalid source",GpRseProperties.failure(score))
    }

    @Test fun structuralCopyMismatchCannotMoveAFlagToTheNextBeat() {
        val score=load(source { flag(notes(it)[0],"Tapped") })
        val other=load(source(". 0.1.4 (2.2 3.3).4 r.4 r.4"))
        assertEquals(allNotes(score).size,allNotes(other).size)
        try { GpRseProperties.transfer(score,other);fail("Different beat structure accepted as a copy") }
        catch (_:GpRsePropertiesException) { }
    }

    @Test fun multipleTracksAndStavesFollowMasterOrderWhenTrackDeclarationsAreReversed() {
        val bytes=source("""\track "First" . 0.1.1 \staff . 2.2.1 \track "Second" . 3.3.1""") { doc ->
            flag(notes(doc)[1],"Tapped")
            val parent=doc.documentElement.child("Tracks");val tracks=parent.children("Track")
            tracks.forEach { parent.removeChild(it) };tracks.reversed().forEach { parent.appendChild(it) }
        }
        val score=load(bytes)
        assertEquals(2.0,score.tracks.length,0.0);assertEquals(2.0,score.tracks[0].staves.length,0.0)
        assertEquals(listOf(false,true,false),allNotes(score).map { GpRseProperties.note(it)!!.tapped })
    }

    @Test fun duplicateNoteIdsAndReferenceListsAreRejectedByTheBoundedReader() {
        val duplicate=source { doc -> val n=notes(doc);n[1].setAttribute("id",n[0].getAttribute("id")) }
        val repeated=source { doc -> val beat=doc.documentElement.child("Beats").children("Beat")[0]
            beat.appendChild(beat.child("Notes").cloneNode(true)) }
        for(bytes in listOf(duplicate,repeated)) {
            try { GpRseProperties.read(bytes);fail("Ambiguous note graph accepted") }
            catch (_:GpRsePropertiesException) { }
        }
    }
}
