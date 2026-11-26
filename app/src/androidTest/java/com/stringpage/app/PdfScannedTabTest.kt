@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.pdf.PdfBend
import com.stringpage.app.pdf.AndroidRasterGlyphs
import com.stringpage.app.pdf.PdfBox
import com.stringpage.app.pdf.PdfConversionDocument
import com.stringpage.app.pdf.PdfConversionException
import com.stringpage.app.pdf.PdfMeter
import com.stringpage.app.pdf.PdfLine
import com.stringpage.app.pdf.PdfPoint
import com.stringpage.app.pdf.PdfRasterPrintLayout
import com.stringpage.app.pdf.PdfPrintFrets
import com.stringpage.app.pdf.PdfRasterTab
import com.stringpage.app.pdf.RasterTabStaff
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

    @Test fun outlinedPrintUsesActualMeasureStrokesInsteadOfDarkInteriorColumns() {
        val staff = RasterTabStaff(listOf(100, 120, 140, 160, 180, 200), listOf(40, 190, 215, 380))
        fun vertical(x: Double, top: Double, bottom: Double) = PdfLine(PdfPoint(x, top), PdfPoint(x, bottom))
        val drawn = listOf(vertical(40.0, 100.0, 200.0), vertical(190.0, 100.0, 200.0),
            vertical(215.0, 105.0, 200.0), vertical(380.0, 100.0, 200.0))
        assertEquals(listOf(40, 190, 380), PdfRasterPrintLayout(400.0, drawn).align(staff, 400)?.edges)
        assertNull(PdfRasterPrintLayout(400.0, drawn.dropLast(1)).align(staff, 400))
    }

    @Test fun outlinedPrintReadsItsHeaderAndDoesNotTreatUnpairedRhythmAsASilentBar() {
        val page = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        try {
            page.eraseColor(Color.WHITE)
            val canvas = Canvas(page)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 2f }
            val top = 174f
            val space = 24f
            val bottom = top + space * 5
            val left = 92f
            val edges = listOf(left, 300f, 520f, 910f)
            repeat(6) { i -> canvas.drawLine(left, top + i * space, edges.last(), top + i * space, paint) }
            for (x in edges) canvas.drawLine(x, top, x, bottom, paint)
            paint.typeface = Typeface.SANS_SERIF
            paint.textSize = space * 3f
            canvas.drawText("4", left + space * 2.15f, top + space * 2.30f, paint)
            canvas.drawText("4", left + space * 2.15f, top + space * 4.74f, paint)
            val tempoStart = left + space * 4.3f
            canvas.drawOval(tempoStart, top - space * 2.28f, tempoStart + space * 0.56f, top - space * 1.86f, paint)
            canvas.drawRect(tempoStart + space * 0.45f, top - space * 3.26f, tempoStart + space * 0.56f, top - space * 2.06f, paint)
            canvas.drawRect(tempoStart + space * 1.08f, top - space * 2.82f, tempoStart + space * 1.63f, top - space * 2.72f, paint)
            canvas.drawRect(tempoStart + space * 1.08f, top - space * 2.59f, tempoStart + space * 1.63f, top - space * 2.49f, paint)
            paint.textSize = space * 1.15f
            canvas.drawText("90", tempoStart + space * 2.02f, top - space * 2.12f, paint)
            canvas.drawLine(650f, bottom + space * 0.9f, 650f, bottom + space * 2.7f, paint)

            val layout = PdfRasterPrintLayout(page.width.toDouble(), edges.map { x ->
                PdfLine(PdfPoint(x.toDouble(), top.toDouble()), PdfPoint(x.toDouble(), bottom.toDouble()))
            })
            val result = PdfRasterTab(AndroidRasterGlyphs.load(context.assets)).recognize(
                AndroidRasterGlyphs.bitmapToInk(page), 1, 1, printLayout = layout).first
            assertEquals(90, result.bpm)
            assertFalse(result.meterNeedsConfirmation)
            assertEquals(PdfMeter(4, 4), result.bars.first().meter)
            assertEquals(3, result.bars.size)
            assertTrue(result.bars.take(2).all { it.issues.isEmpty() && it.beats.single().notes.isEmpty() })
            assertTrue(result.bars.last().issues.isNotEmpty())
        } finally { page.recycle() }
    }

    @Test fun outlinedChordKeepsAnAlertForUnreadableInkOnAnotherString() {
        val page = Bitmap.createBitmap(1000, 380, Bitmap.Config.ARGB_8888)
        try {
            page.eraseColor(Color.WHITE)
            val canvas = Canvas(page)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 2f }
            val top = 105f
            val space = 24f
            val bottom = top + space * 5
            val left = 64f
            val right = 936f
            repeat(6) { i -> canvas.drawLine(left, top + i * space, right, top + i * space, paint) }
            val edges = listOf(left, right)
            for (x in edges) canvas.drawLine(x, top, x, bottom, paint)
            paint.typeface = Typeface.SANS_SERIF
            paint.textSize = space * 1.1f
            paint.textAlign = Paint.Align.CENTER
            for ((index, fret) in listOf("0", "3", "2", "7").withIndex()) {
                val x = 180f + index * 160
                paint.color = Color.WHITE
                canvas.drawRect(x - 13, bottom - 15, x + 13, bottom + 12, paint)
                paint.color = Color.BLACK
                canvas.drawText(fret, x, bottom + space * 0.36f, paint)
                canvas.drawLine(x, bottom + space * 0.68f, x, bottom + space * 2.5f, paint)
            }
            val y = top + space * 2
            paint.color = Color.WHITE
            canvas.drawRect(168f, y - 6f, 192f, y + 6f, paint)
            paint.color = Color.BLACK
            canvas.drawRect(178f, y - 4f, 182f, y + 5f, paint)
            val layout = PdfRasterPrintLayout(page.width.toDouble(), edges.map { x ->
                PdfLine(PdfPoint(x.toDouble(), top.toDouble()), PdfPoint(x.toDouble(), bottom.toDouble()))
            })
            val bar = PdfRasterTab(AndroidRasterGlyphs.load(context.assets)).recognize(
                AndroidRasterGlyphs.bitmapToInk(page), 1, 1, printLayout = layout).first.bars.single()
            assertEquals(listOf(0, 3, 2, 7), bar.beats.map { it.notes.single().fret })
            assertTrue(bar.issues.toString(), "第 3 弦品位需确认" in bar.issues)
        } finally { page.recycle() }
    }

    @Test fun aPrintedFretIsReadFromItsDigitOutlineInsideTheBrackets() {
        val bitmap = Bitmap.createBitmap(210, 150, Bitmap.Config.ARGB_8888)
        try {
            val gap = 24.0
            val center = 90.0
            val row = 67.0
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = (gap * 1.15).toFloat()
            }
            fun glyph(text: String, x: Double): PdfBox {
                val bounds = Rect()
                paint.getTextBounds(text, 0, text.length, bounds)
                val originX = x - bounds.exactCenterX()
                val originY = row - bounds.exactCenterY()
                canvas.drawText(text, originX.toFloat(), originY.toFloat(), paint)
                return PdfBox(originX + bounds.left, originY + bounds.top, originX + bounds.right, originY + bounds.bottom)
            }
            val digit = glyph("4", center)
            val open = glyph("(", center - gap * 0.60)
            val close = glyph(")", center + gap * 0.60)
            val matcher = AndroidRasterGlyphs.load(context.assets)
            val image = AndroidRasterGlyphs.bitmapToInk(bitmap)
            assertEquals(PdfPrintFrets.Fingering(4, true), PdfPrintFrets(image, gap, listOf(open, digit, close), matcher).at(center, row))
            assertNull(PdfPrintFrets(image, gap, listOf(open, digit), matcher).at(center, row))
        } finally { bitmap.recycle() }
    }
}
