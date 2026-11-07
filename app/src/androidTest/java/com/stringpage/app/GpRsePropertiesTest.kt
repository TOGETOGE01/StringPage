@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.GpRseProperties
import com.stringpage.app.rse.GpRsePropertiesException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GpRsePropertiesTest {
    private fun track(id: String, style: String? = null, extra: String = "") =
        "<Track id=\"$id\">"+(style?.let { "<PlayingStyle>$it</PlayingStyle>" } ?: "")+extra+"</Track>"
    private fun xml(tracks: String, master: String? = null) =
        ("<GPIF>"+(master?.let { "<MasterTrack><Tracks>$it</Tracks></MasterTrack>" } ?: "")+"<Tracks>$tracks</Tracks></GPIF>").toByteArray()
    private fun zip(vararg members: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { out -> for ((name,data) in members) {
            out.putNextEntry(ZipEntry(name));out.write(data);out.closeEntry()
        } }
    }.toByteArray()
    private fun packed(xml:ByteArray) = zip("Content/score.gpif" to xml)
    private fun expectFailure(bytes:ByteArray) {
        try { GpRseProperties.read(bytes);fail("Invalid container/properties accepted") }
        catch (_:GpRsePropertiesException) { }
    }

    @Test fun masterTrackOrderAndPerElementArticulationsRemainSourceAccurate() {
        val instrument="""<Instrument ref="drm"/><InstrumentSet><Type>drum</Type><Elements>
            <Element><Articulations><Articulation id="a"><OutputRSESound>stick hit rimshot</OutputRSESound><OutputMidiNumber>38</OutputMidiNumber></Articulation>
            <Articulation><OutputRSESound/></Articulation></Articulations></Element>
            <Element><Articulations><Articulation><OutputRSESound>brush hit open</OutputRSESound><OutputMidiNumber>46</OutputMidiNumber></Articulation></Articulations></Element>
            </Elements></InstrumentSet>"""
        val bytes=packed(xml(track("b","BassSlap")+track("a","DrumkitBrush",instrument),"a b"));val original=bytes.copyOf()
        val data=GpRseProperties.read(bytes)
        assertEquals(GpRseProperties.Container.Zip,data.container);assertTrue(data.usesMasterTrackOrder)
        assertEquals(listOf("a","b"),data.tracks.map { it.id })
        val a=data.track(0)!!;assertEquals(5,a.playingStyleCore);assertEquals("DrumkitBrush",a.rawPlayingStyle)
        assertEquals("drum",a.instrumentType);assertEquals("drm",a.instrumentReference)
        assertEquals(listOf(0 to 0,0 to 1,1 to 0),a.articulations.map { it.elementIndex to it.articulationIndex })
        assertEquals(listOf("stick","hit","rimshot"),a.articulations[0].outputRseSound)
        assertEquals(38,a.articulations[0].outputMidiNumber);assertEquals("a",a.articulations[0].id)
        assertTrue(a.articulations[1].outputRseSound.isEmpty());assertEquals("",a.articulations[1].rawOutputRseSound)
        assertNull(data.track(2));assertArrayEquals(original,bytes)
    }

    @Test fun nativeStylesAreCaseInsensitiveWhileEmptyAndUnknownTextRemainInspectable() {
        val data=GpRseProperties.read(packed(xml(track("0")+track("1","stringedpick")+track("2","")+track("3","DrumkitStick"))))
        assertFalse(data.usesMasterTrackOrder)
        assertEquals(0,data.track(0)!!.playingStyleCore);assertFalse(data.track(0)!!.hasExplicitPlayingStyle)
        assertEquals(1,data.track(1)!!.playingStyleCore);assertEquals("stringedpick",data.track(1)!!.rawPlayingStyle)
        assertNull(data.track(2)!!.playingStyleCore);assertTrue(data.track(2)!!.hasExplicitPlayingStyle)
        assertEquals(6,data.track(3)!!.playingStyleCore)
        val styles=listOf("Default","StringedPick","StringedFinger","StringedFingerPicking","BassSlap","DrumkitBrush","DrumkitHotrod")
        for((index,style) in styles.withIndex()) assertEquals(index,GpRseProperties.playingStyleCore(style))
        assertEquals(1,GpRseProperties.nativePlayingStyleCore("STRINGEDPICK"))
        assertEquals(0,GpRseProperties.nativePlayingStyleCore("unknown"))
        assertEquals(0,GpRseProperties.nativePlayingStyleCore(null))
    }

    @Test fun ambiguousPathsDuplicateIdsEntitiesAndMalformedFilesAreExplicitFailures() {
        val valid=xml(track("0"))
        expectFailure(zip("../score.gpif" to valid))
        expectFailure(zip("Content/score.gpif" to valid,"Content\\score.gpif" to valid))
        expectFailure(zip("Content/score.gpif" to valid,"Other/score.gpif" to valid))
        expectFailure(zip("Content/other" to valid))
        expectFailure(packed(xml(track("0")+track("0"))))
        expectFailure(packed(xml(track("0"),"0 0")))
        expectFailure(packed(xml(track("0"),"missing")))
        expectFailure(packed("<!DOCTYPE GPIF [<!ENTITY x 'expanded'>]><GPIF><Tracks><Track id='0'><PlayingStyle>&x;</PlayingStyle></Track></Tracks></GPIF>".toByteArray()))
        expectFailure(packed("<!DOCTYPE GPIF SYSTEM 'file:///never-opened'><GPIF/>".toByteArray(Charsets.UTF_16)))
        expectFailure(packed("<GPIF><Tracks>".toByteArray()))
        expectFailure(packed("<Other/>".toByteArray()))
        expectFailure(packed(xml(track("0",extra="<PlayingStyle>Default</PlayingStyle><PlayingStyle>BassSlap</PlayingStyle>"))))
    }

    private fun writeInt(data:ByteArray,at:Int,value:Int) { repeat(4) { data[at+it]=(value ushr (it*8)).toByte() } }
    private fun bcfs(xml:ByteArray,name:String="score.gpif"):ByteArray {
        val sectors=(xml.size+4095)/4096
        val data=ByteArray(4+(2+sectors)*4096);"BCFS".toByteArray().copyInto(data)
        val directory=4+4096;writeInt(data,directory,2);name.toByteArray().copyInto(data,directory+4)
        writeInt(data,directory+140,xml.size)
        repeat(sectors) { writeInt(data,directory+148+it*4,2+it) }
        xml.copyInto(data,4+8192)
        return data
    }
    private fun bcfz(expanded:ByteArray):ByteArray {
        val output=ByteArrayOutputStream();output.write("BCFZ".toByteArray())
        val length=ByteArray(4);writeInt(length,0,expanded.size);output.write(length)
        var current=0;var count=0
        fun bits(value:Int,width:Int,reversed:Boolean=false) {
            repeat(width) { index ->
                val bit=(value ushr if(reversed) index else width-index-1) and 1
                current=(current shl 1) or bit
                if(++count==8) { output.write(current);current=0;count=0 }
            }
        }
        var position=0
        while(position<expanded.size) {
            if(position>=8 && position<=expanded.size-8 && (0 until 8).all { expanded[position+it]==expanded[position-8+it] }) {
                bits(1,1);bits(4,4);bits(8,4,true);bits(8,4,true);position+=8
            } else {
                val size=minOf(3,expanded.size-position);bits(0,1);bits(size,2,true)
                repeat(size) { bits(expanded[position++].toInt() and 255,8) }
            }
        }
        if(count>0)output.write(current shl (8-count))
        return output.toByteArray()
    }

    @Test fun boundedGp6FilesystemReadsBothRawAndCompressedContainers() {
        val text=xml(track("z","StringedFingerPicking"),"z")
        val plain=bcfs(text);val compressed=bcfz(plain)
        val expected=GpRseProperties.read(packed(text)).tracks
        for(bytes in listOf(plain,compressed)) {
            val original=bytes.copyOf();val actual=GpRseProperties.read(bytes)
            assertEquals(GpRseProperties.Container.Gpx,actual.container);assertEquals(expected,actual.tracks)
            assertArrayEquals(original,bytes)
        }
        expectFailure(bcfs(text,"../score.gpif"))
        expectFailure(plain.copyOf().also { writeInt(it,4+4096+148,Int.MAX_VALUE) })
        expectFailure(plain.copyOf().also { writeInt(it,4+4096+140,text.size+8192) })
        expectFailure(compressed.copyOf(10))
        expectFailure("BCFZ".toByteArray()+ByteArray(4).also { writeInt(it,0,256*1024*1024+1) })
    }

    @Test fun generatedGp6ContainerAgreesWithPublicScoreLoaderAndOriginalPracticeScore() {
        val bytes=InstrumentationRegistry.getInstrumentation().targetContext.assets.open("morning.gp").use { it.readBytes() }
        var gpif:ByteArray?=null
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while(true) {
                val entry=input.nextEntry ?: break
                if(entry.name.substringAfterLast('/')=="score.gpif")gpif=input.readBytes()
            }
        }
        val original=ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()),Settings())
        val source=GpRseProperties.read(bytes)
        assertEquals(original.tracks.length.toInt(),source.tracks.size)
        for(container in listOf(bcfs(gpif!!),bcfz(bcfs(gpif!!)))) {
            val properties=GpRseProperties.read(container)
            val score=ScoreLoader.loadScoreFromBytes(Uint8Array(container.asUByteArray()),Settings())
            assertEquals(original.title,score.title);assertEquals(original.tracks.length,score.tracks.length,0.0)
            assertEquals(source.tracks,properties.tracks)
            assertEquals(original.masterBars.length,score.masterBars.length,0.0)
            GpRseProperties.bind(score,properties)
            var mapped=0
            for(t in score.tracks)for(s in t.staves)for(b in s.bars)for(v in b.voices)for(beat in v.beats)for(note in beat.notes) {
                assertNotNull(GpRseProperties.note(note));mapped++
            }
            assertTrue(mapped>0)
        }
    }

    @Test fun legacyAbsenceAndEditTransferKeepSourceMetadataAssociatedWithTheCorrectScore() {
        val missing=GpRseProperties.read(byteArrayOf(1,2,3,4))
        assertFalse(missing.hasGpif);assertTrue(missing.tracks.isEmpty())
        val a=Score();val b=Score();val unrelated=Score()
        val source=GpRseProperties.read(packed(xml(track("0","BassSlap"))))
        GpRseProperties.bind(a,source);assertSame(source,GpRseProperties.get(a));assertNull(GpRseProperties.get(b))
        GpRseProperties.transfer(a,b);assertSame(source,GpRseProperties.get(b));assertSame(source,GpRseProperties.get(a))
        GpRseProperties.transfer(unrelated,b);assertNull(GpRseProperties.get(b))
    }
}
