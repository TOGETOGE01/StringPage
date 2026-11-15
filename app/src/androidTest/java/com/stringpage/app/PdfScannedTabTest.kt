@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.pdf.PdfBend
import com.stringpage.app.pdf.PdfBox
import com.stringpage.app.pdf.PdfConversionDocument
import com.stringpage.app.pdf.PdfConversionException
import com.stringpage.app.pdf.PdfMeter
import com.stringpage.app.pdf.PdfTabBar
import com.stringpage.app.pdf.PdfTabCorrection
import com.stringpage.app.pdf.PdfTabGp
import com.stringpage.app.pdf.PdfTabTuning
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfScannedTabTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun readsImageOnlyPdfAndWritesPlayableFrettedNotes() {
        val picture = Bitmap.createBitmap(850, 920, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("scanned-tab-fixture-", ".pdf", context.cacheDir)
        try {
            picture.eraseColor(Color.WHITE)
            val canvas = Canvas(picture)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 1.7f }
            val staffTop = 198f
            val space = 24f
            val bottom = staffTop + space * 5
            for (line in 0..5) {
                val y = staffTop + line * space
                canvas.drawLine(72f, y, 776f, y, paint)
            }
            for (x in listOf(72f, 405f, 776f)) canvas.drawLine(x, staffTop, x, bottom, paint)
            paint.typeface = Typeface.createFromAsset(context.assets, "Bravura.otf")
            paint.textSize = space * 2.667f
            canvas.drawText("\ue06d", 82f, staffTop + space * 2.5f, paint)
            canvas.drawText("\ue4e3", 249f, staffTop + space + 1, paint)
            paint.typeface = Typeface.SANS_SERIF
            paint.textSize = space * 1.1f
            paint.textAlign = Paint.Align.CENTER
            for ((index, digit) in listOf("0", "3", "X", "7").withIndex()) {
                val x = 466f + index * 81
                paint.color = Color.WHITE
                canvas.drawRect(x - 12, bottom - 15, x + 12, bottom + 12, paint)
                paint.color = Color.BLACK
                canvas.drawText(digit, x, bottom + space * 0.36f, paint)
                canvas.drawLine(x, bottom + space * 0.68f, x, bottom + space * 2.5f, paint)
            }
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(850, 920, 1).create())
                page.canvas.drawBitmap(picture, 0f, 0f, null)
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val result = PdfConversionDocument(file, file.readBytes(), context.assets).read(1)
            assertTrue(result.scanned)
            assertTrue(result.meterNeedsConfirmation)
            val track = result.tracks.single()
            assertEquals(2, track.bars.size)
            assertTrue(track.bars.all { it.meter == PdfMeter(4, 4) })
            assertTrue(track.bars.toString(), track.bars.all { it.issues.isEmpty() })
            val output = PdfTabGp.encodeTracks("Scanned TAB", 118, listOf(track.copy(tuning = PdfTabTuning.standard(6))))
            val score = ScoreLoader.loadScoreFromBytes(Uint8Array(output.asUByteArray()), Settings())
            assertEquals(118.0, score.tempo, 0.0)
            val staff = score.tracks[0].staves[0]
            assertTrue(staff.bars[0].voices[0].beats[0].isFullBarRest)
            val notes = staff.bars[1].voices[0].beats.asSequence().map { it.notes[0] }.toList()
            assertEquals(listOf(0.0, 3.0, 0.0, 7.0), notes.map { it.fret })
            assertTrue(notes[2].isDead)
            assertTrue(notes.all { it.string == 1.0 })
        } finally {
            picture.recycle()
            file.delete()
        }
    }

    @Test fun correctingAnUncertainBarValidatesTimeAndPreservesTabTechniques() {
        val original = PdfTabBar(12, 1, PdfBox(0.0, 0.0, 50.0, 50.0), emptyList(), listOf("Unreadable"), emptyList(), PdfMeter(4, 4))
        val corrected = PdfTabCorrection.correct(original, 6, "4 1-3^1\n8. 6-0m\n16 R\n2 2-X")
        assertTrue(corrected.issues.isEmpty())
        assertEquals(PdfBend.up(2), corrected.beats.first().notes.first().bend)
        assertTrue(corrected.beats[1].notes.first().palmMute)
        assertTrue(corrected.beats.last().notes.first().dead)
        assertEquals(corrected.beats, PdfTabCorrection.correct(original, 6, PdfTabCorrection.format(corrected.beats, 6)).beats)
        try {
            PdfTabCorrection.correct(original, 6, "4 1-3")
            fail("Accepted a quarter note as a complete 4/4 measure")
        } catch (_: PdfConversionException) { }
    }
}
