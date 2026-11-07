@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.importer.AlphaTexImporter
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class FactoryScoreRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun performance() = AlphaTexImporter().run {
        initFromString("""\tempo 120 . 0.1.4 2.1.4 3.1.4 5.1.4 | 7.1.4 5.1.4 3.1.4 2.1.4""", Settings())
        ScorePerformance.compile(readScore())
    }
    private fun bank(): BankLibrary.Item {
        val file = File(context.filesDir,"rse-reference.gp7bank")
        assumeTrue("Optional original bank is provisioned outside the APK", file.isFile)
        return BankLibrary.Item(file, Gp7Bank.open(file).readMetadata())
    }
    @Test fun rendersBoundedStereoPcmWithMonotonicProgressAndRealSampleData() {
        val item = bank(); val performance = performance(); val progress = ArrayList<Int>()
        val output = File(context.cacheDir,"factory-render-test.wav")
        try {
            val result = FactoryScoreRenderer.render(performance, mapOf(FactoryScoreRenderer.Route(0) to item), output,
                progress = { progress.add(it) })
            assertEquals(8, result.noteCount); assertEquals(4000.0, result.durationMs, 1.0)
            assertTrue(result.peak > 0.001f); assertEquals(0L, result.clippedSamples)
            assertEquals(100, progress.last()); assertEquals(progress.distinct().sorted(), progress)
            RandomAccessFile(output,"r").use { input ->
                val riff = ByteArray(4); input.readFully(riff); assertEquals("RIFF",String(riff))
                input.seek(22); assertEquals(2, java.lang.Short.reverseBytes(input.readShort()).toInt())
                input.seek(24); assertEquals(44100,Integer.reverseBytes(input.readInt()))
                input.seek(40); assertEquals(output.length()-44,Integer.reverseBytes(input.readInt()).toLong())
                input.seek(44); var nonzero = 0
                repeat(((output.length()-44)/2).toInt()) { if (input.readShort() != 0.toShort()) nonzero++ }
                assertTrue(nonzero > 1000)
            }
        } finally { output.delete() }
    }
    @Test fun cancellationRemovesPartialPreparedAudioAndKeepsTheBankUntouched() {
        val item = bank(); val bytes = item.file.length(); val performance = performance()
        val output = File(context.cacheDir,"factory-cancel-test.wav"); val cancelled = AtomicBoolean()
        try {
            try {
                FactoryScoreRenderer.render(performance, mapOf(FactoryScoreRenderer.Route(0) to item), output,
                    cancelled = { cancelled.get() }, progress = { if (it >= 1) cancelled.set(true) })
                fail("Cancelled preparation completed")
            } catch (_: IllegalStateException) { }
            assertFalse(output.exists()); assertEquals(bytes,item.file.length())
            assertEquals(item.bank.id,Gp7Bank.open(item.file).readMetadata().id)
        } finally { output.delete() }
    }
    @Test fun unassignedAudibleTracksCannotSilentlyDisappearFromTheMix() {
        val output = File(context.cacheDir,"factory-missing-route.wav")
        try {
            try { FactoryScoreRenderer.render(performance(), emptyMap(), output); fail("Missing bank was silently accepted") }
            catch (_: IllegalArgumentException) { }
            assertFalse(output.exists())
        } finally { output.delete() }
    }

    @Test fun nativeDefaultReleaseStopsTheActualSampleTailAfter128OutputFrames() {
        val item=bank()
        val importer=AlphaTexImporter()
        importer.initFromString("""\tempo 120 . 0.1.4 r.4 r.4 r.4""",Settings())
        val performance=ScorePerformance.compile(importer.readScore())
        assertEquals(1,performance.notes.size)
        val output=File(context.cacheDir,"factory-release-test.wav")
        try {
            val result=FactoryScoreRenderer.render(performance,mapOf(FactoryScoreRenderer.Route(0) to item),output)
            assertTrue(result.peak>.001f)
            val off=kotlin.math.round(performance.notes.single().endMs*44.1).toLong()
            RandomAccessFile(output,"r").use { input ->
                input.seek(44+off*4)
                assertTrue("The release begins with audible original sample data",(0 until 64).any { input.readShort()!=0.toShort() })
                input.seek(44+(off+128)*4)
                repeat(256*2) { assertEquals("Released voice must be silent after its native default fade",0,input.readShort().toInt()) }
            }
        } finally { output.delete() }
    }
}
