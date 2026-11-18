@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.StaveProfile
import alphaTab.importer.AlphaTexImporter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PublicationExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun score(tab: String) = AlphaTexImporter().run {
        initFromString("""\title "Publication Study" \artist "StringPage QA" \tempo 120 . $tab""", Settings())
        readScore()
    }

    @Test fun printableScoreSpansWholeSystemsAndCanSelectPaperAndMeasureRange() {
        val document = score(List(60) { "0.1.4 2.1.4 3.1.4 5.2.4" }.joinToString(" | "))
        val full = File.createTempFile("print-full-", ".pdf", context.cacheDir)
        val selection = File.createTempFile("print-range-", ".pdf", context.cacheDir)
        try {
            full.outputStream().use {
                ScorePrintExport.write(context, document, ScorePrintExport.Options(ScorePrintExport.Paper.A4,
                    listOf(0), 1..60, StaveProfile.ScoreTab), it)
            }
            selection.outputStream().use {
                ScorePrintExport.write(context, document, ScorePrintExport.Options(ScorePrintExport.Paper.LETTER,
                    listOf(0), 9..12, StaveProfile.Tab), it)
            }
            renderer(full) { pdf ->
                assertTrue(pdf.pageCount >= 2)
                for (index in 0 until pdf.pageCount) pdf.openPage(index).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    assertInkInsideMargins(page)
                }
            }
            renderer(selection) { pdf ->
                assertEquals(1, pdf.pageCount)
                pdf.openPage(0).use { page ->
                    assertEquals(612, page.width)
                    assertEquals(792, page.height)
                    assertInkInsideMargins(page)
                }
            }
        } finally {
            full.delete()
            selection.delete()
        }
    }

    @Test fun wavIsStereoPcmAndUsesTheActualGuitarEffectChain() {
        val document = score("0.1.4 2.1.4 3.1.4 5.2.4")
        val original = File.createTempFile("wave-clean-", ".wav", context.cacheDir)
        val processed = File.createTempFile("wave-amp-", ".wav", context.cacheDir)
        val font = context.assets.open("sonivox.sf2").use { it.readBytes() }
        try {
            ScoreWaveExport.write(document, Settings(), listOf(GuitarTone.Clean), emptyMap(), original, { font })
            val amp = GuitarAmpSettings(AmpPreset.Modern, pan = -100)
            ScoreWaveExport.write(document, Settings(), listOf(GuitarTone.Clean), mapOf(0 to amp), processed, { font })
            val clean = inspectWave(original)
            val dirty = inspectWave(processed)
            assertTrue(clean.first > 100_000L && clean.second > 100_000L)
            assertTrue(dirty.first > 100_000L)
            assertTrue(dirty.first > dirty.second * 20)
            assertFalse(original.readBytes().contentEquals(processed.readBytes()))
        } finally {
            original.delete()
            processed.delete()
        }
    }

    private fun inspectWave(file: File): Pair<Long, Long> {
        val bytes = file.readBytes()
        val stream = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", bytes.copyOfRange(8, 12).decodeToString())
        assertEquals(bytes.size - 8, stream.getInt(4))
        assertEquals(1, stream.getShort(20).toInt())
        assertEquals(2, stream.getShort(22).toInt())
        assertEquals(44100, stream.getInt(24))
        assertEquals(16, stream.getShort(34).toInt())
        assertEquals(bytes.size - 44, stream.getInt(40))
        assertTrue((bytes.size - 44) / 4 >= 44100 * 2)
        stream.position(44)
        var left = 0L
        var right = 0L
        while (stream.remaining() >= 4) {
            left += abs(stream.short.toInt())
            right += abs(stream.short.toInt())
        }
        return left to right
    }

    private inline fun renderer(file: File, use: (PdfRenderer) -> Unit) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use(use)
    }

    private fun assertInkInsideMargins(page: PdfRenderer.Page) {
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            var marked = 0
            for (y in 0 until page.height - 45) for (x in 0 until page.width) {
                if (Color.red(bitmap.getPixel(x, y)) < 150) {
                    assertTrue("Ink crossed the printable margin at ($x, $y)", x in 30..(page.width - 30) && y >= 29)
                    marked++
                }
            }
            assertTrue("Printable page should contain score content", marked > 150)
        } finally { bitmap.recycle() }
    }
}
