@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Duration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.stringpage.app.pdf.PdfRasterLayout
import com.stringpage.app.pdf.PdfRasterRescan
import com.stringpage.app.pdf.PdfRasterGlyphs
import com.stringpage.app.pdf.PdfPrintFrets
import com.stringpage.app.pdf.PdfRasterTab
import com.stringpage.app.pdf.RasterTabStaff
import com.stringpage.app.pdf.PdfTabBar
import com.stringpage.app.pdf.PdfTabBeat
import com.stringpage.app.pdf.PdfTabCorrection
import com.stringpage.app.pdf.PdfTabGp
import com.stringpage.app.pdf.PdfTabNote
import com.stringpage.app.pdf.PdfTabResult
import com.stringpage.app.pdf.PdfTabTrack
import com.stringpage.app.pdf.PdfTabTuning
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfScannedTabTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun printedRescanOnlyUsesCompleteAlignedBarsWithEvidence() {
        fun quarter(fret: Int, tied: Boolean = false) = PdfTabBeat(4, listOf(PdfTabNote(6, fret, tied = tied)))
        val gap = listOf("未识别")
        val first = PdfTabBar(1, 1, PdfBox(20.0, 30.0, 120.0, 200.0), listOf(quarter(12)), gap, emptyList(), PdfMeter(2, 4))
        val second = first.copy(number = 2, bounds = PdfBox(120.0, 30.0, 220.0, 200.0),
            beats = listOf(quarter(14, tied = true), quarter(10)), issues = emptyList())
        fun result(width: Double, vararg bars: PdfTabBar) =
            PdfTabResult(71, listOf(PdfTabTrack("TAB", 6, null, bars.toList())), width, scanned = true)
        val original = result(240.0, first, second)
        val firstRescan = first.copy(bounds = PdfBox(40.0, 60.0, 240.0, 400.0),
            beats = listOf(quarter(12), quarter(14)), issues = emptyList())
        val secondRescan = second.copy(bounds = PdfBox(240.0, 60.0, 440.0, 400.0),
            beats = listOf(quarter(14, tied = true), quarter(8)))
        val rescan = result(480.0, firstRescan, secondRescan)
        val recovered = PdfRasterRescan.combine(original, listOf(rescan), emptyList())
        assertEquals(firstRescan.beats, recovered.bars[0].beats)
        assertEquals(first.bounds, recovered.bars[0].bounds)
        assertTrue(recovered.bars[0].issues.isEmpty())
        assertEquals(second, recovered.bars[1])

        val misaligned = firstRescan.copy(bounds = firstRescan.bounds.copy(right = 232.0))
        val wrongTie = firstRescan.copy(beats = listOf(quarter(12), quarter(15)))
        for (unreliable in listOf(misaligned, wrongTie)) {
            assertEquals(original, PdfRasterRescan.combine(original, listOf(result(480.0, unreliable, secondRescan)), emptyList()))
        }
        val noKnownNotes = result(240.0, first.copy(beats = emptyList()), second)
        assertEquals(noKnownNotes, PdfRasterRescan.combine(noKnownNotes, listOf(rescan), emptyList()))
        assertTrue(PdfRasterRescan.combine(noKnownNotes, listOf(rescan, rescan), emptyList()).bars[0].issues.isEmpty())
        val silent = result(480.0, firstRescan.copy(beats = listOf(PdfTabBeat(2, emptyList()))), secondRescan)
        val noTie = result(240.0, first, second.copy(beats = listOf(quarter(14), quarter(10))))
        assertEquals(noTie, PdfRasterRescan.combine(noTie, listOf(silent, silent), emptyList()))
    }

    @Test fun confirmsAnUntitledPrintOnlyWhenFretOutlinesAlignWithItsStemLane() {
        fun vertical(x: Double, top: Double, bottom: Double) = PdfLine(PdfPoint(x, top), PdfPoint(x, bottom))
        val staff = RasterTabStaff(listOf(80, 100, 120, 140, 160, 180), listOf(40, 380))
        val frame = listOf(vertical(40.0, 80.0, 180.0), vertical(380.0, 80.0, 180.0))
        val fretPositions = listOf(98.0, 151.0, 203.0, 255.0)
        val frets = fretPositions.map { x -> PdfBox(x - 5, 112.0, x + 5, 128.0) }
        val stems = fretPositions.map { x -> vertical(x, 200.0, 230.0) }
        val layout = PdfRasterPrintLayout(400.0, frame + stems, frets, previouslyConfirmed = false)
        assertFalse(layout.confirmed)
        assertTrue(layout.matches(listOf(staff), 400))
        assertTrue(layout.confirmed)
        val noPitches = PdfRasterPrintLayout(400.0, frame + stems, previouslyConfirmed = false)
        val unrelatedLines = PdfRasterPrintLayout(400.0, frame + stems.map { vertical(it.from.x + 22, 200.0, 230.0) },
            frets, previouslyConfirmed = false)
        assertFalse(noPitches.matches(listOf(staff), 400))
        assertFalse(unrelatedLines.matches(listOf(staff), 400))
    }

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

    @Test fun outlinedFinalBarUsesTheEdgeWhereTheStringsEnd() {
        val rows = (0..5).map { 100 + it * 20 }
        val detected = RasterTabStaff(rows, listOf(40, 190, 368))
        val strokes = listOf(40.0, 190.0, 380.0).map { x -> PdfLine(PdfPoint(x, 100.0), PdfPoint(x, 200.0)) }
        val strings = rows.map { y -> PdfLine(PdfPoint(40.0, y.toDouble()), PdfPoint(380.0, y.toDouble())) }
        assertEquals(listOf(40, 190, 380), PdfRasterPrintLayout(400.0, strokes + strings).align(detected, 400)?.edges)
        assertNull(PdfRasterPrintLayout(400.0, strokes + strings.take(3)).align(detected, 400))
        val inside = PdfLine(PdfPoint(368.0, 100.0), PdfPoint(368.0, 200.0))
        assertEquals(listOf(40, 190, 380), PdfRasterPrintLayout(400.0, strokes + strings + inside).align(detected, 400)?.edges)
    }

    @Test fun outlinedDigitsRecognizeAnOpenStringOnlyWithAVisibleCentralHole() {
        val bitmap = Bitmap.createBitmap(120, 110, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawOval(49f, 45f, 62f, 65f, paint)
            paint.color = Color.WHITE
            canvas.drawOval(53f, 48f, 58f, 62f, paint)
            val outer = PdfBox(49.0, 45.0, 62.0, 65.0)
            val hole = PdfBox(53.0, 48.0, 58.0, 62.0)
            val image = AndroidRasterGlyphs.bitmapToInk(bitmap)
            val matcher = PdfRasterGlyphs(emptyList())
            assertEquals(PdfPrintFrets.Fingering(0, false), PdfPrintFrets(image, 24.0, listOf(outer, hole), matcher).at(55.5, 55.0))
            assertNull(PdfPrintFrets(image, 24.0, listOf(outer, hole.copy(top = 55.0)), matcher).at(55.5, 55.0))
        } finally { bitmap.recycle() }
    }

    @Test fun outlinedSystemCanBeRecoveredWhenDenseFretsBreakOneString() {
        val detected = RasterTabStaff((0..5).map { 30 + 20 * it }, listOf(40, 380))
        val rows = (0..5).map { 200 + 20 * it }
        val frame = listOf(40.0, 210.0, 380.0).map { x -> PdfLine(PdfPoint(x, 200.0), PdfPoint(x, 300.0)) }
        val continuous = rows.drop(1).map { y -> PdfLine(PdfPoint(40.0, y.toDouble()), PdfPoint(380.0, y.toDouble())) }
        val fragments = (60..350 step 14).map { x -> PdfLine(PdfPoint(x.toDouble(), 200.0), PdfPoint(x + 2.0, 200.0)) }
        val layout = PdfRasterPrintLayout(400.0, frame + continuous + fragments)
        assertEquals(listOf(detected, RasterTabStaff(rows, listOf(40, 210, 380))), layout.recoverStaves(listOf(detected), 400))
        assertEquals(listOf(detected), PdfRasterPrintLayout(400.0, frame + continuous).recoverStaves(listOf(detected), 400))
        assertEquals(listOf(detected), PdfRasterPrintLayout(400.0, frame.dropLast(1) + continuous + fragments)
            .recoverStaves(listOf(detected), 400))
    }

    @Test fun printedFramesFindRowsOfDifferentWidthsAndDoNotNeedARasterAnchor() {
        fun line(x: Double, y: Double, endX: Double, endY: Double) = PdfLine(PdfPoint(x, y), PdfPoint(endX, endY))
        fun system(top: Int, left: Int, inside: Int, right: Int): Pair<RasterTabStaff, List<PdfLine>> {
            val rows = (0..5).map { top + 20 * it }
            val edges = listOf(left, inside, right)
            val strings = rows.map { y -> line(left.toDouble(), y.toDouble(), right.toDouble(), y.toDouble()) }
            val bars = edges.drop(1).map { x -> line(x.toDouble(), top.toDouble(), x.toDouble(), rows.last().toDouble()) }
            return RasterTabStaff(rows, edges) to (strings + bars)
        }
        val (first, firstStrokes) = system(90, 40, 210, 565)
        val (last, lastStrokes) = system(257, 45, 170, 380)
        val formDivider = line(80.0, 440.0, 80.0, 540.0)
        val layout = PdfRasterPrintLayout(600.0, firstStrokes + lastStrokes + formDivider)
        assertEquals(listOf(first, last), layout.recoverStaves(listOf(first), 600))
        assertEquals(listOf(first, last), layout.recoverStaves(emptyList(), 600))
    }

    @Test fun exportedPrintedNotesSurviveWhenRasterStaffLinesAreTooFaint() {
        val bitmap = Bitmap.createBitmap(780, 350, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 240, 240); strokeWidth = 1f }
            val rows = (0..5).map { 92 + it * 24 }
            val left = 61
            val right = 719
            fun line(x: Int, y: Int, endX: Int, endY: Int) =
                PdfLine(PdfPoint(x.toDouble(), y.toDouble()), PdfPoint(endX.toDouble(), endY.toDouble()))
            val strokes = rows.map { y -> line(left, y, right, y) }.toMutableList()
            strokes.add(line(right, rows.first(), right, rows.last()))
            for (stroke in strokes) canvas.drawLine(stroke.from.x.toFloat(), stroke.from.y.toFloat(),
                stroke.to.x.toFloat(), stroke.to.y.toFloat(), paint)
            paint.color = Color.BLACK
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paint.textSize = 27.6f
            paint.strokeWidth = 2f
            val frets = listOf(2, 3, 5, 7)
            val outlines = frets.mapIndexed { index, fret ->
                val x = 142 + 135 * index
                val stem = line(x, rows.last() + 22, x, rows.last() + 60)
                strokes.add(stem)
                canvas.drawLine(x.toFloat(), stem.from.y.toFloat(), x.toFloat(), stem.to.y.toFloat(), paint)
                val value = fret.toString()
                val box = Rect()
                paint.getTextBounds(value, 0, value.length, box)
                val glyphX = x - box.exactCenterX()
                val glyphY = rows[2] - box.exactCenterY()
                canvas.drawText(value, glyphX, glyphY, paint)
                PdfBox((glyphX + box.left).toDouble(), (glyphY + box.top).toDouble(),
                    (glyphX + box.right).toDouble(), (glyphY + box.bottom).toDouble())
            }
            val image = AndroidRasterGlyphs.bitmapToInk(bitmap)
            assertTrue(PdfRasterLayout.staves(image).isEmpty())
            val layout = PdfRasterPrintLayout(bitmap.width.toDouble(), strokes, outlines, previouslyConfirmed = false)
            val result = PdfRasterTab(AndroidRasterGlyphs.load(context.assets)).recognize(image, 1, 1,
                PdfRasterTab.MeterResult(PdfMeter(4, 4), false), layout).first
            assertTrue(result.bars.toString(), result.bars.single().issues.isEmpty())
            val output = PdfTabGp.encode("Faint lines", 90, result.bars)
            val score = ScoreLoader.loadScoreFromBytes(Uint8Array(output.asUByteArray()), Settings())
            val beats = score.tracks[0].staves[0].bars[0].voices[0].beats.asSequence().toList()
            assertEquals(frets.map(Int::toDouble), beats.map { it.notes[0].fret })

            for (y in 7..55 step 12) canvas.drawLine(4f, y.toFloat(), 767f, y + 30f, paint)
            val falseSkew = AndroidRasterGlyphs.bitmapToInk(bitmap)
            assertTrue(falseSkew.detectSlope() != 0.0)
            val rereadLayout = PdfRasterPrintLayout(bitmap.width.toDouble(), strokes, outlines, previouslyConfirmed = false)
            val reread = PdfRasterTab(AndroidRasterGlyphs.load(context.assets)).recognize(falseSkew, 1, 1,
                PdfRasterTab.MeterResult(PdfMeter(4, 4), false), rereadLayout).first
            assertEquals(result.bars, reread.bars)
        } finally { bitmap.recycle() }
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

    @Test fun outlinedWholeNotesAndSilentMeasuresKeepTheirDurationInGp() {
        val page = Bitmap.createBitmap(800, 380, Bitmap.Config.ARGB_8888)
        try {
            page.eraseColor(Color.WHITE)
            val canvas = Canvas(page)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f }
            val top = 105f
            val gap = 24f
            val bottom = top + gap * 5
            val edges = listOf(58f, 280f, 525f, 740f)
            repeat(6) { i -> canvas.drawLine(edges.first(), top + i * gap, edges.last(), top + i * gap, paint) }
            for (x in edges) canvas.drawLine(x, top, x, bottom, paint)
            val row = top + gap
            fun digit(text: String, left: Float, size: Float): PdfBox {
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                paint.textSize = size
                val b = Rect()
                paint.getTextBounds(text, 0, text.length, b)
                val y = row - b.exactCenterY()
                canvas.drawText(text, left - b.left, y, paint)
                return PdfBox(left.toDouble(), (y + b.top).toDouble(), (left + b.width()).toDouble(), (y + b.bottom).toDouble())
            }
            val digitOutlines = ArrayList<PdfBox>()
            for (left in listOf(180f, 345f)) {
                paint.color = Color.WHITE
                canvas.drawRect(left - 17, row - 17, left + 48, row + 17, paint)
                paint.color = Color.BLACK
                val one = digit("1", left, gap * 1.15f)
                val two = digit("2", one.right.toFloat() + 4, gap * 1.15f)
                digitOutlines.addAll(listOf(one, two))
                if (left > 300) {
                    paint.textSize = gap * 1.15f
                    val bracketBounds = Rect().also { paint.getTextBounds("(", 0, 1, it) }
                    digitOutlines.add(digit("(", left - bracketBounds.width() - 4, gap * 1.15f))
                    digitOutlines.add(digit(")", two.right.toFloat() + 4, gap * 1.15f))
                }
            }
            val lines = edges.map { x -> PdfLine(PdfPoint(x.toDouble(), top.toDouble()), PdfPoint(x.toDouble(), bottom.toDouble())) }
            val layout = PdfRasterPrintLayout(page.width.toDouble(), lines, digitOutlines)
            val raster = PdfRasterTab(AndroidRasterGlyphs.load(context.assets))
            val image = AndroidRasterGlyphs.bitmapToInk(page)
            val result = raster.recognize(image, 1, 1, PdfRasterTab.MeterResult(PdfMeter(4, 4), false), layout).first
            assertTrue(result.bars.toString(), result.bars.all { it.issues.isEmpty() })
            assertTrue(digitOutlines.toString(), result.bars[1].beats.single().notes.single().tied)
            val gp = PdfTabGp.encode("Whole-note print", 91, result.bars)
            val score = ScoreLoader.loadScoreFromBytes(Uint8Array(gp.asUByteArray()), Settings())
            val beats = score.tracks[0].staves[0].bars.asSequence().map { it.voices[0].beats[0] }.toList()
            assertEquals(listOf(Duration.Whole, Duration.Whole, Duration.Whole), beats.map { it.duration })
            assertEquals(12.0, beats[0].notes[0].fret, 0.0)
            assertTrue(beats[1].notes[0].isTieDestination)
            assertTrue(beats[2].isFullBarRest)
            val otherMeter = raster.recognize(image, 1, 1, PdfRasterTab.MeterResult(PdfMeter(3, 4), false), layout).first
            assertTrue(otherMeter.bars.take(2).all { it.issues.isNotEmpty() })

            val tieStart = digitOutlines[1].right.toFloat() + 12
            val tie = Path().apply {
                moveTo(tieStart, row + 2)
                cubicTo(tieStart + 18, row + 19, tieStart + 58, row + 21, tieStart + 94, row + 2)
            }
            canvas.save()
            canvas.clipRect(tieStart - 2, row, edges[1], row + gap)
            paint.style = Paint.Style.STROKE
            canvas.drawPath(tie, paint)
            canvas.restore()
            paint.style = Paint.Style.FILL
            val withTie = AndroidRasterGlyphs.bitmapToInk(page)
            val commonMeter = PdfRasterTab.MeterResult(PdfMeter(4, 4), false)
            val tiedBars = raster.recognize(withTie, 1, 1, commonMeter, layout).first.bars
            assertTrue(tiedBars.toString(), tiedBars.all { it.issues.isEmpty() })
            val withoutBrackets = PdfRasterPrintLayout(page.width.toDouble(), lines, digitOutlines.take(4))
            assertTrue(raster.recognize(withTie, 1, 1, commonMeter, withoutBrackets).first.bars.first().issues.isNotEmpty())

            paint.color = Color.WHITE
            canvas.drawRect(tieStart - 2, row + 1, edges[1] - 1, row + gap - 1, paint)
            paint.color = Color.BLACK
            val x = ((digitOutlines[0].left + digitOutlines[1].right) / 2).toFloat()
            val end = bottom + gap * 2.72f
            canvas.drawLine(x, bottom + gap * 1.95f, x, end, paint)
            canvas.drawCircle(x + gap * 0.39f, end - 2f, gap * 0.09f, paint)
            val dottedHalf = raster.recognize(AndroidRasterGlyphs.bitmapToInk(page), 1, 1,
                PdfRasterTab.MeterResult(PdfMeter(3, 4), false), layout).first.bars.first()
            assertTrue(dottedHalf.toString(), dottedHalf.issues.isEmpty())
            assertEquals(2, dottedHalf.beats.single().denominator)
            assertEquals(1, dottedHalf.beats.single().dots)
        } finally { page.recycle() }
    }

    @Test fun outlinedDottedRestAndShortBracketedTripletPreserveLegatoInGp() {
        val page = Bitmap.createBitmap(690, 370, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
            val gap = 24f
            val top = 100f
            val bottom = top + gap * 5
            val left = 64f
            val right = 630f
            val edges = listOf(left, right).map { x ->
                PdfLine(PdfPoint(x.toDouble(), top.toDouble()), PdfPoint(x.toDouble(), bottom.toDouble()))
            }
            val matcher = AndroidRasterGlyphs.load(context.assets)
            for ((hasBracket, hasSlur) in listOf(true to true, false to true, true to false)) {
                page.eraseColor(Color.WHITE)
                paint.color = Color.BLACK
                repeat(6) { i -> canvas.drawLine(left, top + i * gap, right, top + i * gap, paint) }
                for (x in listOf(left, right)) canvas.drawLine(x, top, x, bottom, paint)
                fun glyph(text: String, x: Float, y: Float): PdfBox {
                    val bounds = Rect()
                    paint.getTextBounds(text, 0, text.length, bounds)
                    val start = x - bounds.exactCenterX()
                    val baseline = y - bounds.exactCenterY()
                    canvas.drawText(text, start, baseline, paint)
                    return PdfBox((start + bounds.left).toDouble(), (baseline + bounds.top).toDouble(),
                        (start + bounds.right).toDouble(), (baseline + bounds.bottom).toDouble())
                }
                paint.typeface = Typeface.createFromAsset(context.assets, "Bravura.otf")
                paint.textSize = gap * 2.667f
                val rest = glyph("\ue4e5", 156f, top + gap * 2)
                val dotX = (rest.right + gap * 0.26).toFloat()
                val dotY = top + gap * 1.82f
                val radius = gap * 0.10f
                canvas.drawCircle(dotX, dotY, radius, paint)
                val dot = PdfBox((dotX - radius).toDouble(), (dotY - radius).toDouble(),
                    (dotX + radius).toDouble(), (dotY + radius).toDouble())

                val notes = listOf(333f, 390f, 447f)
                val beam = bottom + 2.5f * gap
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                paint.textSize = gap * 1.15f
                val frets = ArrayList<PdfBox>()
                for ((index, x) in notes.withIndex()) {
                    paint.color = Color.WHITE
                    canvas.drawRect(x - 12, bottom - 13, x + 12, bottom + 13, paint)
                    paint.color = Color.BLACK
                    frets.add(glyph(listOf("0", "2", "0")[index], x, bottom))
                    canvas.drawLine(x, bottom + gap * 0.72f, x, beam, paint)
                }
                if (hasSlur) {
                    paint.color = Color.WHITE
                    canvas.drawRect(notes.first() - 1, bottom - gap - 3, notes.last() + 1, bottom - gap + 3, paint)
                    paint.color = Color.BLACK
                    paint.style = Paint.Style.STROKE
                    val slur = Path().apply {
                        moveTo(notes.first(), bottom - gap * 0.53f)
                        cubicTo(notes.first() + gap, bottom - gap * 1.41f, notes.last() - gap,
                            bottom - gap * 1.41f, notes.last(), bottom - gap * 0.53f)
                    }
                    canvas.drawPath(slur, paint)
                    paint.style = Paint.Style.FILL
                }
                canvas.drawRect(notes.first(), beam - 3f, notes.last(), beam + 1f, paint)
                val flagX = 552f
                paint.color = Color.WHITE
                canvas.drawRect(flagX - 12, bottom - 13, flagX + 12, bottom + 13, paint)
                paint.color = Color.BLACK
                frets.add(glyph("3", flagX, bottom))
                val flagTop = bottom + gap * 1.1f
                val flagEnd = flagTop + gap * 1.52f
                val hook = Path().apply {
                    moveTo(flagX, flagTop)
                    lineTo(flagX + 1.5f, flagTop)
                    lineTo(flagX + 1.5f, flagEnd - 5f)
                    cubicTo(flagX + 7f, flagEnd - 10f, flagX + 6f, flagEnd - 15f, flagX + 5f, flagEnd - 21f)
                    cubicTo(flagX + 9f, flagEnd - 17f, flagX + 9f, flagEnd - 11f, flagX + 4f, flagEnd - 6f)
                    lineTo(flagX, flagEnd)
                    close()
                }
                canvas.drawPath(hook, paint)
                val flag = PdfBox(flagX.toDouble(), flagTop.toDouble(), (flagX + 8f).toDouble(), flagEnd.toDouble())
                val bracket = bottom + gap * 3.55f
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                paint.textSize = gap * 0.74f
                val numeral = glyph("3", notes[1], bracket)
                if (hasBracket) {
                    canvas.drawLine(notes.first(), bracket, numeral.left.toFloat() - 3f, bracket, paint)
                    canvas.drawLine(numeral.right.toFloat() + 3f, bracket, notes.last(), bracket, paint)
                    canvas.drawLine(notes.first(), bracket - 8f, notes.first(), bracket, paint)
                    canvas.drawLine(notes.last(), bracket - 8f, notes.last(), bracket, paint)
                }
                val layout = PdfRasterPrintLayout(page.width.toDouble(), edges, frets + listOf(rest, dot, numeral, flag))
                val (result, _) = PdfRasterTab(matcher).recognize(AndroidRasterGlyphs.bitmapToInk(page), 1, 1,
                    PdfRasterTab.MeterResult(PdfMeter(6, 8), false), printLayout = layout)
                val bar = result.bars.single()
                assertEquals(bar.toString(), listOf(4, 8, 8, 8, 8), bar.beats.map { it.denominator })
                assertEquals(1, bar.beats.first().dots)
                assertTrue(bar.beats.first().notes.isEmpty())
                assertEquals(listOf(0, 2, 0, 3), bar.beats.drop(1).map { it.notes.single().fret })
                assertEquals(List(3) { hasBracket } + false, bar.beats.drop(1).map { it.triplet })
                assertEquals(listOf(hasSlur, hasSlur, false, false), bar.beats.drop(1).map { it.notes.single().hammerPull })
                if (hasBracket) {
                    assertTrue(bar.issues.toString(), bar.issues.isEmpty())
                    val track = result.tracks.single().copy(tuning = PdfTabTuning.standard(6))
                    val gp = PdfTabGp.encodeTracks("Printed rhythm", 90, listOf(track))
                    val score = ScoreLoader.loadScoreFromBytes(Uint8Array(gp.asUByteArray()), Settings())
                    val beats = score.tracks[0].staves[0].bars[0].voices[0].beats
                    assertTrue(beats[0].isRest)
                    assertEquals(1.0, beats[0].dots, 0.0)
                    for (index in 1..3) {
                        assertEquals(3.0, beats[index].tupletNumerator, 0.0)
                        assertEquals(2.0, beats[index].tupletDenominator, 0.0)
                        assertEquals(hasSlur && index < 3, beats[index].notes[0].isHammerPullOrigin)
                    }
                } else {
                    assertTrue(bar.issues.isNotEmpty())
                }
            }
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
