@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Duration
import alphaTab.model.HarmonicType
import alphaTab.model.GraceType
import alphaTab.model.SlideInType
import alphaTab.model.SlideOutType
import alphaTab.model.VibratoType
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.pdf.PdfBox
import com.stringpage.app.pdf.PdfBend
import com.stringpage.app.pdf.PdfConversionException
import com.stringpage.app.pdf.PdfConvertActivity
import com.stringpage.app.pdf.PdfObjects
import com.stringpage.app.pdf.PdfTabBar
import com.stringpage.app.pdf.PdfTabBeat
import com.stringpage.app.pdf.PdfTabCorrection
import com.stringpage.app.pdf.PdfTabGp
import com.stringpage.app.pdf.PdfTabNote
import com.stringpage.app.pdf.PdfTabRecognition
import com.stringpage.app.pdf.PdfWhammy
import com.stringpage.app.pdf.Ref
import com.stringpage.app.pdf.VectorPdf
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfTabConversionTest {
    @Test fun vectorScoreExportsAsPlayableGp() {
        val recognized = PdfTabRecognition.read(VectorPdf(examplePdf()), 1)
        assertEquals(120, recognized.bpm)
        assertTrue(recognized.standardTuning)
        assertEquals(2, recognized.bars.size)
        assertTrue(recognized.bars.toString(), recognized.bars.all { it.issues.isEmpty() })
        val output = load(PdfTabGp.encode("PDF test", 120, recognized.bars))
        assertEquals("PDF test", output.title)
        assertEquals(120.0, output.tempo, 0.0)
        assertEquals(2.0, output.masterBars.length, 0.0)
        val bars = output.tracks[0].staves[0].bars
        val beats = bars[0].voices[0].beats
        assertEquals(4.0, beats.length, 0.0)
        assertEquals(Duration.Quarter, beats[0].duration)
        assertEquals(6.0, beats[0].notes[0].string, 0.0)
        assertEquals(3.0, beats[0].notes[0].fret, 0.0)
        assertTrue(beats[0].notes[0].isPalmMute)
        assertEquals(2.0, beats[0].notes[0].bendPoints!![1].value, 0.0)
        assertTrue(beats[1].notes[0].isDead)
        assertTrue(beats[2].isRest)
        assertEquals(7.0, beats[3].notes[0].harmonicValue, 0.0)
        assertEquals(HarmonicType.Natural, beats[3].notes[0].harmonicType)
        val sustained = bars[1].voices[0].beats
        assertEquals(1.0, sustained.length, 0.0)
        assertEquals(Duration.Whole, sustained[0].duration)
        assertEquals(2.0, sustained[0].notes.length, 0.0)
    }

    @Test fun measuresWithUnmatchedRhythmAreFlagged() {
        val recognized = PdfTabRecognition.read(VectorPdf(examplePdf(includeRest = false)), 1)
        assertTrue(recognized.bars.first().issues.isNotEmpty())
        try {
            PdfTabGp.encode("Test", 120, recognized.bars)
            fail("Exported an ambiguous rhythm")
        } catch (_: PdfConversionException) { }
    }

    @Test fun saveExplainsWhichMeasureNeedsCorrection() = withPdfEditor(examplePdf(includeRest = false)) { activity ->
        waitForText(activity, "无法保存 GP · 第 1 小节待校对")
        tapSave(activity)
        assertSaveDialogContains("第 1 小节", "修正小节")
    }

    @Test fun saveExplainsMissingTempoAndTuningTogether() = withPdfEditor(examplePdf(includeSettings = false)) { activity ->
        waitForText(activity, "无法保存 GP · 缺少速度")
        tapSave(activity)
        assertSaveDialogContains("设置速度", "设置调弦")
    }

    @Test fun suggestionsResolveMissingTempoTuningAndMeterTogether() =
        withPdfEditor(examplePdf(includeSettings = false, includeMeter = false)) { activity ->
            waitForText(activity, "100 BPM（试听参考） · 标准调弦 · 暂按 4/4")
            tapControl(activity, "应用建议设置")
            waitForText(activity, "100 BPM")
            waitForText(activity, "已选 第 1 小节 至 第 2 小节")
            assertPreviewEnabled(activity)
        }

    @Test fun readyRangeCanBeSelectedWithoutTypingMeasureNumbers() = withPdfEditor(examplePdf(includeRest = false)) { activity ->
        tapControl(activity, "选用可导出段 · 第 2 小节")
        waitForText(activity, "已选 第 2 小节")
        assertPreviewEnabled(activity)
    }

    @Test fun missingTrailingRestCanBeReviewedWithoutTypingNotation() = withPdfEditor(examplePdf(includeRest = false)) { activity ->
        tapControl(activity, "校对第 1 小节")
        tapDialogControl("补尾部休止符")
        tapDialogControl("应用")
        waitForText(activity, "已选 第 1 小节 至 第 2 小节")
        assertPreviewEnabled(activity)
    }

    @Test fun aNoteDurationCanBeCorrectedByTappingWithoutOpeningAdvancedEditing() =
        withPdfEditor(examplePdf(includeRest = false)) { activity ->
            tapControl(activity, "校对第 1 小节")
            tapDialogControl("点选时值")
            tapDialogControl("第 1 组 ·", prefix = true)
            tapDialogControl("二分音符")
            tapDialogControl("应用")
            waitForText(activity, "已选 第 1 小节 至 第 2 小节")
            assertPreviewEnabled(activity)
        }

    @Test fun saveReportsCancellationWriteFailureAndSuccessfulRetry() = withPdfEditor(examplePdf()) { activity ->
        val instrument = InstrumentationRegistry.getInstrumentation()
        val resolver = activity.contentResolver
        val output = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "stringpage-test-output.gp")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        }))
        fun chooseLocation(result: Instrumentation.ActivityResult, expected: String) {
            val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("application/octet-stream")
            }
            val monitor = instrument.addMonitor(filter, result, true)
            try {
                tapSave(activity)
                waitForText(activity, expected)
                assertEquals("Save should open the Android file picker once", 1, monitor.hits)
            } finally {
                instrument.removeMonitor(monitor)
            }
        }
        try {
            chooseLocation(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), "已取消保存")
            val unavailable = Intent().setData(Uri.parse("content://com.stringpage.test.unavailable/export.gp"))
            chooseLocation(Instrumentation.ActivityResult(Activity.RESULT_OK, unavailable), "写入 GP 文件失败")
            chooseLocation(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(output)), "GP 文件已保存")
            val bytes = checkNotNull(resolver.openInputStream(output)).use { it.readBytes() }
            assertEquals(2.0, load(bytes).masterBars.length, 0.0)
        } finally {
            resolver.delete(output, null, null)
        }
    }

    private fun withPdfEditor(pdf: ByteArray, check: (PdfConvertActivity) -> Unit) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "stringpage-test-source.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(pdf) }
            val activity = instrument.startActivitySync(Intent(context, PdfConvertActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }) as PdfConvertActivity
            try {
                check(activity)
            } finally {
                instrument.runOnMainSync { activity.finish() }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun tapSave(activity: PdfConvertActivity) = tapControl(activity, "保存 GP")

    private fun tapControl(activity: PdfConvertActivity, value: String) {
        waitForText(activity, value)
        var clicked = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val control = viewsWithText(activity, value).filterIsInstance<Button>().firstOrNull { it.text == value }
            clicked = control?.takeIf { it.isEnabled }?.performClick() == true
        }
        assertTrue("PDF conversion button could not be clicked: $value", clicked)
    }

    private fun tapDialogControl(value: String, prefix: Boolean = false) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)
                ?.firstOrNull { it.isVisibleToUser && (it.text == value || prefix && it.text?.startsWith(value) == true) }
            if (node != null) {
                val rect = Rect().also(node::getBoundsInScreen)
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                        rect.exactCenterX(), rect.exactCenterY(), 0)
                    instrument.sendPointerSync(event)
                    event.recycle()
                }
                return
            }
            SystemClock.sleep(40)
        }
        fail("Correction dialog did not show: $value")
    }

    private fun assertPreviewEnabled(activity: PdfConvertActivity) {
        var enabled = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            enabled = viewsWithText(activity, "试听").filterIsInstance<Button>().any { it.text == "试听" && it.isEnabled }
        }
        assertTrue("The confirmed selection should be ready to export", enabled)
    }

    private fun assertSaveDialogContains(vararg values: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val expected = listOf("现在无法保存 GP", *values)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrument.uiAutomation.rootInActiveWindow
            if (root != null && expected.all { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }) return
            SystemClock.sleep(40)
        }
        fail("Save dialog did not explain: $expected")
    }

    private fun waitForText(activity: PdfConvertActivity, text: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var shown = false
            instrument.runOnMainSync {
                shown = viewsWithText(activity, text).filterIsInstance<TextView>().any { it.isShown && it.text.contains(text) }
            }
            if (shown) return
            SystemClock.sleep(40)
        }
        fail("PDF conversion did not show: $text")
    }

    private fun viewsWithText(activity: PdfConvertActivity, text: String) = ArrayList<View>().apply {
        activity.window.decorView.findViewsWithText(this, text, View.FIND_VIEWS_WITH_TEXT)
    }

    @Test fun compressedPdfRetainsRhythmAndTechniques() {
        val original = examplePdf()
        val compressed = compactPdf(original)
        val expected = PdfTabRecognition.read(VectorPdf(original), 1)
        val actual = PdfTabRecognition.read(VectorPdf(compressed), 1)
        assertEquals(expected.bpm, actual.bpm)
        assertEquals(expected.tracks, actual.tracks)
        val exported = load(PdfTabGp.encode("Compressed PDF", 120, actual.bars))
        val firstNote = exported.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
        assertTrue(firstNote.isPalmMute)
        assertEquals(2.0, firstNote.bendPoints!![1].value, 0.0)
    }

    @Test fun nestedFormsRestorePageCoordinatesAndClipHiddenScoreMarks() {
        val original = VectorPdf(examplePdf())
        val expected = PdfTabRecognition.read(original, 1)
        for (scale in listOf(1.0, 0.5)) {
            val wrapped = VectorPdf(nestedForms(scale = scale))
            assertEquals(original.pageWidth(0) * scale, wrapped.pageWidth(0), 0.0)
            val geometry = wrapped.geometry(0)
            assertFalse(geometry.containsExternalDrawing)
            assertEquals(1, geometry.text.count { it.text == "\ue06d" })
            val actual = PdfTabRecognition.Document(wrapped, geometry).read(1)
            assertEquals(expected, actual)
            assertEquals(2.0, load(PdfTabGp.encode("Nested PDF", 120, actual.bars)).masterBars.length, 0.0)
        }
    }

    @Test fun rejectsRecursiveFormReferences() {
        val wrapped = VectorPdf(nestedForms(recursive = true))
        assertThrows(PdfConversionException::class.java) { wrapped.geometry(0) }
    }

    @Test fun hybridIncrementalRevisionResolvesNewestObjectsAndRetainsFreeEntries() {
        val original = examplePdf().toString(Charsets.ISO_8859_1)
        val previous = original.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        val body = original + "9 0 obj\n<< /Type /ObjStm /N 1 /First 5 /Length 7 >>\nstream\n10 0 42\nendstream\nendobj\n"
        val supplement = indexStream(11, 12, listOf(2, 9, 0), index = "10 1")
        val current = body + supplement
        val updated = current + buildString {
            append("xref\n8 2\n0000000000 00001 f \n")
            append("%010d 00000 n \n".format(Locale.ROOT, original.length))
            append("11 1\n%010d 00000 n \n".format(Locale.ROOT, body.length))
            append("trailer\n<< /Size 12 /Root 1 0 R /Prev $previous /XRefStm ${body.length} >>\n")
            append("startxref\n${current.length}\n%%EOF\n")
        }
        val pdf = PdfObjects(updated.toByteArray(Charsets.ISO_8859_1))
        assertEquals(Ref(1, 0), pdf.root)
        assertEquals(42.0, pdf.resolve(Ref(10, 0)))
        assertNotNull(pdf.resolve(Ref(3, 0)))
        assertThrows(PdfConversionException::class.java) { pdf.stream(Ref(8, 0)) }
    }

    @Test fun dottedAndTripletRhythmsSurviveGpRoundTrip() {
        val note = PdfTabNote(1, 2)
        val bar = PdfTabBar(1, 1, PdfBox(0.0, 0.0, 50.0, 50.0), listOf(
            PdfTabBeat(2, listOf(note), dots = 1),
            PdfTabBeat(8, listOf(note), triplet = true),
            PdfTabBeat(8, listOf(note), triplet = true),
            PdfTabBeat(8, emptyList(), triplet = true)
        ), emptyList(), emptyList())
        val beats = load(PdfTabGp.encode("Rhythm", 120, listOf(bar))).tracks[0].staves[0].bars[0].voices[0].beats
        assertEquals(1.0, beats[0].dots, 0.0)
        for (index in 1..3) {
            assertEquals(3.0, beats[index].tupletNumerator, 0.0)
            assertEquals(2.0, beats[index].tupletDenominator, 0.0)
        }
    }

    @Test fun alignedTracksReuseNotationRhythmAndInheritMeterAcrossPages() {
        fun content(continuation: Boolean) = buildString {
            append("1 0 0 -1 0 400 cm\n")
            val edges = if (continuation) listOf(28, 370) else listOf(28, 250, 370)
            for (y in (100..130 step 6).toList() + (200..216 step 4).toList() + (235..271 step 6).toList()) {
                append("28 $y m 370 $y l S\n")
            }
            append("28 100 m 28 271 l S\n")
            for (x in edges.drop(1)) append("$x 100 m $x 130 l S $x 235 m $x 271 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "31", "212", "\ue050"))
            append(drawText("G", "17", "31", "253", "\ue06d"))
            if (continuation) {
                append(drawText("G", "12", "90", "114", "\ue4e3"))
            } else {
                append(drawText("A", "7", "28", "30", "Lead"))
                append(drawText("A", "7", "28", "45", "Standard tuning"))
                append(drawText("A", "7", "160", "30", "Riff"))
                append(drawText("G", "14", "160", "46", "\ue83a"))
                append(drawText("A", "7", "168", "45", " = A"))
                append(drawText("A", "7", "28", "65", "= 120"))
                append(drawText("G", "17", "42", "110", "\ue083"))
                append(drawText("G", "17", "42", "118", "\ue084"))
                append(drawText("G", "17", "257", "110", "\ue086"))
                append(drawText("G", "17", "257", "118", "\ue088"))
                for (x in listOf(72, 112, 152)) {
                    append(drawText("A", "6.6", x.toString(), "132.2", "3"))
                    append("${x + 2} 133 m ${x + 2} 150 l S\n")
                    append(drawText("G", "8", x.toString(), "212", "\ue0a4"))
                    append("$x 212 m $x 227 l S\n")
                    append(drawText("A", "6.6", x.toString(), "273.2", "7"))
                }
                append(drawText("G", "12", "310", "114", "\ue4e3"))
            }
        }
        val pdf = PdfTabRecognition.Document(VectorPdf(wrapPdf(content(false), content(true))))
        val second = pdf.read(2)
        val first = pdf.read(1)
        assertEquals(2, first.tracks.size)
        assertEquals(listOf(6, 7), first.tracks.map { it.strings })
        assertEquals(33, first.tracks[1].tuning!!.last())
        assertEquals(3, first.tracks[1].bars[0].beats.size)
        val tracks = first.tracks.zip(second.tracks) { a, b -> a.copy(bars = a.bars + b.bars) }
        assertTrue(tracks.toString(), tracks.all { track -> track.bars.all { it.issues.isEmpty() } })
        val reloaded = load(PdfTabGp.encodeTracks("Mixed meter", 120, tracks))
        assertEquals(2.0, reloaded.tracks.length, 0.0)
        assertEquals(3.0, reloaded.masterBars[0].timeSignatureNumerator, 0.0)
        assertEquals(4.0, reloaded.masterBars[0].timeSignatureDenominator, 0.0)
        assertEquals(6.0, reloaded.masterBars[1].timeSignatureNumerator, 0.0)
        assertEquals(8.0, reloaded.masterBars[2].timeSignatureDenominator, 0.0)
        val riff = reloaded.tracks[1].staves[0]
        assertEquals(33.0, riff.tuning[6], 0.0)
        assertEquals(1.0, riff.bars[0].voices[0].beats[0].notes[0].string, 0.0)
        assertEquals(40.0, riff.bars[0].voices[0].beats[0].notes[0].realValue, 0.0)
        assertTrue(riff.bars[2].voices[0].beats[0].isFullBarRest)
    }

    @Test fun readsVectorArticulationsAndGraceNotesIntoGp() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            append(drawText("A", "7", "28", "42", "Standard tuning"))
            append(drawText("A", "7", "28", "64", "= 120"))
            for (y in 100..130 step 6) append("28 $y m 370 $y l S\n")
            append("28 100 m 28 130 l S 370 100 m 370 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "42", "110", "\ue084"))
            append(drawText("G", "17", "42", "118", "\ue084"))
            for ((index, x) in listOf(80, 140, 204, 270).withIndex()) {
                append(drawText("A", "6.6", (x - 1.8).toString(), "102.2", listOf("3", "5", "7", "9")[index]))
                append("$x 133 m $x 150 l S\n")
            }
            append(drawText("A", "4.9", "60.7", "101.65", "2"))
            append("62 132 m 62 137 l S\n")
            append(drawText("G", "8", "62", "135", "\ue564"))
            append(drawText("A", "6", "108", "95", "H"))
            append("80 97.4 m 96 90 124 90 140 97.4 c 124 92 96 92 80 97.4 c f\n")
            append("72.4 102 m 76.4 98 l S\n")
            append(drawText("A", "6", "164", "94", "sl."))
            append("143.6 101.45 m 200.4 98.55 l S\n")
            append(drawText("G", "17", "203.9", "94", "\ueab2\ueab2\ueab2"))
            append("273.6 98 m 277.6 102 l S\n")
            append(drawText("A", "7", "255", "89", "let ring"))
            append("270 97 m 270 81 l S\n")
            append(drawText("G", "8", "267.8", "81", "\ueb78"))
            append(drawText("A", "7", "262.4", "75", "full"))
        }
        val result = PdfTabRecognition.read(VectorPdf(wrapPdf(content)), 1)
        val bar = result.bars.single()
        assertTrue(bar.toString(), bar.issues.isEmpty())
        val decoded = load(PdfTabGp.encode("Technique", 120, result.bars))
        val beats = decoded.tracks[0].staves[0].bars[0].voices[0].beats
        assertEquals(5.0, beats.length, 0.0)
        assertEquals(GraceType.BeforeBeat, beats[0].graceType)
        assertEquals(2.0, beats[0].notes[0].fret, 0.0)
        assertEquals(SlideInType.IntoFromBelow, beats[1].notes[0].slideInType)
        assertTrue(beats[1].notes[0].isHammerPullOrigin)
        assertEquals(SlideOutType.Legato, beats[2].notes[0].slideOutType)
        assertEquals(VibratoType.Slight, beats[3].notes[0].vibrato)
        val last = beats[4].notes[0]
        assertEquals(SlideOutType.OutDown, last.slideOutType)
        assertTrue(last.isLetRing)
        assertTrue(last.hasBend)
        assertEquals(4.0, last.bendPoints!![0].value, 0.0)
        assertEquals(4.0, last.bendPoints!![1].value, 0.0)
    }

    @Test fun bendPathsPreservePartialReleasesAndTiedPitchAcrossBeats() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            for (y in 100..130 step 6) append("28 $y m 385 $y l S\n")
            for (x in listOf(28, 201, 385)) append("$x 100 m $x 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "42", "110", "\ue084"))
            append(drawText("G", "17", "42", "118", "\ue084"))
            for (x in listOf(65, 97, 137, 169, 220, 260, 315, 356)) {
                append("$x 133 m $x 148 l S\n")
                if (x !in setOf(260, 315)) append(drawText("A", "6.6", "${x - 1.8}", "132.2", "5"))
            }
            fun mark(x: Int, y: Int, label: String?, up: Boolean) {
                append(drawText("G", "8", "${x - 2.2}", "$y", if (up) "\ueb78" else "\ueb7c"))
                if (label != null) {
                    val left = x - label.length * 1.65
                    append(drawText("A", "6", "$left", "${y - 6}", label))
                }
            }
            append("68 129 m 79 129 82 125 83 78 c S\n")
            mark(83, 78, "full", up = true)
            append("100 129 m 106 129 111 110 112 71 c S\n")
            mark(112, 71, "1½", up = true)
            append("112 68 m 118 68 122 68 124 74 c S\n")
            mark(124, 77, "½", up = false)
            append("137 127 m 137 78 l S\n")
            mark(137, 78, "½", up = true)
            append("137 75 m 145 75 151 75 153 70 c S\n")
            mark(153, 70, "full", up = true)
            append("169 127 m 169 76 l S\n")
            mark(169, 76, "full", up = true)
            append("169 73 m 182 73 190 73 192 126 c S\n")
            mark(192, 129, null, up = false)
            append("223 129 m 235 129 259 125 260 76 c S\n")
            mark(260, 76, "full", up = true)
            append("q [2 1] 0 d 260 73 m 315 73 l S Q\n")
            append("224 130 m 235 133 245 133 254.4 130 c 245 134 235 134 224 130 c f\n")
            append("265.6 130 m 280 133 300 133 309.4 130 c 300 134 280 134 265.6 130 c f\n")
            append("315 73 m 330 73 346 80 350 126 c S\n")
            mark(350, 129, null, up = false)
        }
        val bars = PdfTabRecognition.read(VectorPdf(wrapPdf(content)), 1).bars
        assertTrue(bars.toString(), bars.all { it.issues.isEmpty() })
        val expected = listOf(
            listOf(PdfBend.up(4), PdfBend(0, 2, 6), PdfBend(2, 4), PdfBend(4, 0)),
            listOf(PdfBend.up(4), PdfBend.hold(4), PdfBend(4, 0), null)
        )
        assertEquals(expected, bars.map { bar -> bar.beats.map { it.notes.single().bend } })
        assertEquals(listOf(false, true, true, false), bars[1].beats.map { it.notes.single().tied })
        val corrected = PdfTabCorrection.correct(bars[0], 6, PdfTabCorrection.format(bars[0].beats, 6))
        assertEquals(bars[0].beats, corrected.beats)
        val gp = load(PdfTabGp.encode("Bend paths", 120, bars))
        val notes = gp.tracks[0].staves[0].bars.asSequence().map {
            it.voices[0].beats.asSequence().map { beat -> beat.notes[0] }.toList()
        }.toList()
        fun changes(values: List<Int>) = values.filterIndexed { i, value -> i == 0 || value != values[i - 1] }
        for ((measure, origin) in expected.withIndex()) {
            for ((index, bend) in origin.withIndex()) {
                val note = notes[measure][index]
                val points = note.bendPoints?.asSequence()?.map { it.value.toInt() }?.toList().orEmpty()
                assertEquals(changes(bend?.levels.orEmpty()), changes(points))
                assertEquals(bend != null, note.hasBend)
            }
        }
        assertTrue(notes[1][1].isTieDestination)
        assertTrue(notes[1][2].isTieDestination)
    }

    @Test fun aBendArrowAcrossTheBarLineStillBelongsToThePreviousNote() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            for (y in 100..130 step 6) append("28 $y m 374 $y l S\n")
            for (x in listOf(28, 180, 374)) append("$x 100 m $x 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "42", "110", "\ue084"))
            append(drawText("G", "17", "42", "118", "\ue084"))
            for (x in listOf(65, 90, 115, 145, 210, 250, 290, 330)) {
                append("$x 133 m $x 148 l S\n")
                val fret = if (x == 210) "(5)" else "5"
                append(drawText("A", "6.6", "${x - fret.length * 1.8}", "132.2", fret))
            }
            append("148 129 m 163 129 208 129 210 76 c S\n")
            append(drawText("G", "8", "207.8", "76", "\ueb78"))
            append(drawText("A", "6", "203.4", "70", "full"))
            append("149 130 m 163 134 194 134 205 130 c 195 135 163 135 149 130 c f\n")
            append("210 73 m 222 73 238 73 240 126 c S\n")
            append(drawText("G", "8", "237.8", "129", "\ueb7c"))
        }
        val bars = PdfTabRecognition.read(VectorPdf(wrapPdf(content)), 1).bars
        assertTrue(bars.toString(), bars.all { it.issues.isEmpty() })
        val origin = bars[0].beats.last().notes.single()
        assertEquals(PdfBend.up(4), origin.bend)
        val continuation = bars[1].beats.first().notes.single()
        assertEquals(PdfBend(4, 0), continuation.bend)
        assertTrue(continuation.tied)
        val gp = load(PdfTabGp.encode("Cross-bar bend", 120, bars))
        val note = gp.tracks[0].staves[0].bars[1].voices[0].beats[0].notes[0]
        assertTrue(note.isTieDestination)
        assertEquals(4.0, note.bendPoints!![0].value, 0.0)
        assertEquals(0.0, note.bendPoints!![1].value, 0.0)
    }

    @Test fun harmonicTypesAndTouchPositionsSurviveRangesChordsAndCorrection() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            for (y in 100..130 step 6) append("28 $y m 374 $y l S\n")
            for (x in listOf(28, 200, 374)) append("$x 100 m $x 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "42", "110", "\ue084"))
            append(drawText("G", "17", "42", "118", "\ue084"))
            fun note(x: Int, y: Int, fret: String, touch: Int? = null) {
                append(drawText("A", "6.6", "${x - fret.length * 1.815}", "${y + 2.2}", fret))
                if (touch != null) append(drawText("A", "5", "${x + 3.5}", "${y + 1.7}", "<$touch>"))
            }
            fun mark(x: Int, text: String, y: Int = 95) {
                append(drawText("A", "8", "${x - text.length * 2.2}", "$y", text))
            }
            for (x in listOf(68, 102, 136, 168, 230, 264, 298, 332)) append("$x 133 m $x 148 l S\n")
            note(68, 130, "<12>")
            mark(102, "P.H.")
            note(102, 130, "5", 17)
            note(136, 124, "5", 12)
            append("q [2 1] 0 d 112 92.5 m 138 92.5 l S Q\n")
            mark(168, "AH")
            note(168, 130, "5", 17)
            mark(230, "PH")
            mark(230, "TH", 86)
            note(230, 130, "5", 12)
            note(230, 124, "7", 12)
            append("q [2 1] 0 d 236 83.5 m 266 83.5 l S Q\n")
            mark(264, "SH")
            note(264, 130, "5", 12)
            note(264, 124, "7", 11)
            mark(298, "Fdbk")
            note(298, 130, "5", 17)
            mark(332, "AH")
            note(332, 130, "5")
        }
        val bars = PdfTabRecognition.read(VectorPdf(wrapPdf(content)), 1).bars
        assertTrue(bars.toString(), bars.all { it.issues.isEmpty() })
        assertTrue(bars.last().review.any { "未标泛音触弦位置" in it })
        val corrected = PdfTabCorrection.correct(bars[1], 6, PdfTabCorrection.format(bars[1].beats, 6))
        assertEquals(bars[1].beats, corrected.beats)
        val score = load(PdfTabGp.encode("Harmonics", 120, bars))
        val notes = score.tracks[0].staves[0].bars.asSequence().flatMap { bar ->
            bar.voices[0].beats.asSequence().flatMap { beat -> beat.notes.asSequence().sortedBy { it.string } }
        }.map { it.harmonicType to it.harmonicValue }.toList()
        assertEquals(listOf(
            HarmonicType.Natural to 12.0, HarmonicType.Pinch to 12.0, HarmonicType.Pinch to 7.0,
            HarmonicType.Artificial to 12.0, HarmonicType.Tap to 7.0, HarmonicType.Pinch to 5.0,
            HarmonicType.Tap to 7.0, HarmonicType.Semi to 4.0,
            HarmonicType.Feedback to 12.0, HarmonicType.Artificial to 12.0
        ), notes)
    }

    @Test fun tremoloMarksAndCorrectedWhammyAreIndependentBeatEffects() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            for (y in 100..130 step 6) append("28 $y m 374 $y l S\n")
            for (x in listOf(28, 374)) append("$x 100 m $x 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "42", "110", "\ue084"))
            append(drawText("G", "17", "42", "118", "\ue084"))
            for ((index, x) in listOf(68, 135, 202, 269).withIndex()) {
                append("$x 133 m $x 148 l S\n")
                append(drawText("A", "6.6", "${x - 2}", "132.2", "5"))
                if (index < 3) {
                    append(drawText("G", "16", "$x", "${136 + index * 1.5}", ('\ue220' + index).toString()))
                }
            }
            append(drawText("A", "6.6", "133", "126.2", "7"))
            append("135 82 m 168 92 l 202 82 l S\n")
            append(drawText("T", "6.6", "165", "88.5", "-½"))
        }
        val original = PdfTabRecognition.read(VectorPdf(wrapPdf(content, includeEffectFont = true)), 1).bars.single()
        assertTrue(original.toString(), original.issues.isEmpty())
        assertEquals(listOf(1, 2, 3, null), original.beats.map { it.tremoloMarks })
        assertTrue(original.review.any { "颤音杆" in it })
        assertTrue(original.beats.all { it.whammy == null })
        val rows = PdfTabCorrection.format(original.beats, 6).lines().toMutableList()
        rows[1] = rows[1].replace("4r16 ", "4r16w0>-0.5>0 ")
        rows[2] = rows[2].replace("4r32 ", "4r32w-0.25>0 ")
        val corrected = PdfTabCorrection.correct(original, 6, rows.joinToString("\n"))
        assertEquals(rows.joinToString("\n"), PdfTabCorrection.format(corrected.beats, 6))
        val beats = load(PdfTabGp.encode("Tremolo", 120, listOf(corrected))).tracks[0].staves[0].bars[0].voices[0].beats
        assertEquals(listOf(1.0, 2.0, 3.0, null), beats.asSequence().map { it.tremoloPicking?.marks }.toList())
        assertEquals(2, beats[1].notes.length.toInt())
        assertEquals(listOf(0.0 to 0.0, 30.0 to -2.0, 60.0 to 0.0),
            beats[1].whammyBarPoints?.asSequence()?.map { it.offset to it.value }?.toList())
        assertEquals(listOf(0.0 to -1.0, 60.0 to 0.0),
            beats[2].whammyBarPoints?.asSequence()?.map { it.offset to it.value }?.distinct()?.toList())
    }

    @Test fun nativeWhammyCurvesPreserveSignedPitchAndFlagAmbiguousJoins() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            for (y in 100..130 step 6) append("28 $y m 374 $y l S\n")
            for (x in listOf(28, 190, 374)) append("$x 100 m $x 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            append(drawText("G", "17", "42", "110", "\ue084"))
            append(drawText("G", "17", "42", "118", "\ue084"))
            for (x in listOf(68, 100, 132, 163, 215, 250, 286, 323)) {
                append("$x 133 m $x 148 l S\n")
                append(drawText("A", "6.6", "${x - 2}", "132.2", "5"))
            }
            append("68 80 m 98 86.6 l S\n")
            append(drawText("T", "6.6", "94", "83.2", "-¼"))
            append("q [2 1] 0 d 100 80 m 100 89.46 l S 100 89.46 m 130 89.46 l S Q\n")
            append(drawText("T", "6.6", "96", "76.6", "-¾"))
            append("132 80 m 146.5 70.54 l 161 80 l S\n")
            append(drawText("T", "6.6", "144", "67.1", "¾"))
            append("215 80 m 250 90.21 l 284 80 l S\n")
            append(drawText("T", "6.6", "246", "86.8", "-1"))
        }
        val bars = PdfTabRecognition.read(VectorPdf(wrapPdf(content, includeEffectFont = true)), 1).bars
        assertTrue(bars.toString(), bars.all { it.issues.isEmpty() })
        assertEquals(listOf(PdfWhammy(0, -1), PdfWhammy(-3, -3), PdfWhammy(0, 0, 3), null),
            bars[0].beats.map { it.whammy })
        assertTrue(bars[0].review.isEmpty())
        assertEquals(listOf(PdfWhammy(0, -4), null, null, null), bars[1].beats.map { it.whammy })
        assertTrue(bars[1].review.any { "颤音杆" in it })
        val score = load(PdfTabGp.encode("Whammy", 120, bars))
        val replay = score.tracks[0].staves[0].bars[0].voices[0].beats
        assertEquals(listOf(0.0, -1.0), replay[0].whammyBarPoints?.asSequence()?.map { it.value }?.distinct()?.toList())
        assertEquals(listOf(-3.0), replay[1].whammyBarPoints?.asSequence()?.map { it.value }?.distinct()?.toList())
        assertEquals(listOf(0.0 to 0.0, 30.0 to 3.0, 60.0 to 0.0),
            replay[2].whammyBarPoints?.asSequence()?.map { it.offset to it.value }?.toList())
    }

    @Test fun pairedWhammyCueUsesTabFingeringsUnderLargeRectangleClip() {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\nq 0 0 400 400 re\n")
            for (row in 0 until 12) for (column in 0 until 12) {
                append("${30 + column * 10} ${280 + row * 4} 2 2 re\n")
            }
            append("W* n\n")
            for (y in 48..64 step 4) append("28 $y m 374 $y l S\n")
            for (y in 140..170 step 6) append("28 $y m 374 $y l S\n")
            for (x in listOf(28, 374)) append("$x 140 m $x 170 l S\n")
            append("28 48 m 28 170 l S\n")
            append(drawText("G", "16", "31", "60", "\ue050"))
            append(drawText("G", "16", "42", "53", "\ue084"))
            append(drawText("G", "16", "42", "61", "\ue084"))
            append(drawText("G", "17", "31", "155", "\ue06d"))
            for (x in listOf(80, 140, 200, 264)) {
                append(drawText("G", "16", "${x - 4.4}", "72", "\ue0a4"))
                append("${x + 4} 57 m ${x + 4} 71 l S\n")
                append(drawText("A", "6.6", "${x - 2}", "172.2", "5"))
            }
            append(drawText("T", "8", "75", "43", "w/bar"))
            append(drawText("G", "12", "129", "72", "\ue0a4"))
            append("80 120 m 138 126.6 l S\n")
            append(drawText("T", "6.6", "134", "123.2", "-¼"))
            append("Q\n")
        }
        val bar = PdfTabRecognition.read(VectorPdf(wrapPdf(content, includeEffectFont = true)), 1).bars.single()
        assertTrue(bar.toString(), bar.issues.isEmpty() && bar.review.isEmpty())
        assertEquals(listOf(4, 4, 4, 4), bar.beats.map { it.denominator })
        assertEquals(listOf(5, 5, 5, 5), bar.beats.map { it.notes.single().fret })
        assertEquals(listOf(PdfWhammy(0, -1), null, null, null), bar.beats.map { it.whammy })
    }

    private fun load(bytes: ByteArray) = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())

    private fun drawText(font: String, size: String, x: String, y: String, value: String): String {
        val encoded = value.map { "%04x".format(Locale.ROOT, it.code) }.joinToString("")
        return "BT /$font $size Tf 1 0 0 -1 $x $y Tm <$encoded> Tj ET\n"
    }

    private fun examplePdf(includeRest: Boolean = true, includeSettings: Boolean = true, includeMeter: Boolean = true): ByteArray {
        val content = buildString {
            append("1 0 0 -1 0 400 cm\n")
            if (includeSettings) {
                append(drawText("A", "7", "28", "45", "Standard tuning"))
                append(drawText("A", "7", "28", "65", "= 120"))
            }
            for (y in 100..130 step 6) append("28 $y m 370 $y l S\n")
            for (x in listOf(28, 240, 370)) append("$x 100 m $x 130 l S\n")
            append(drawText("G", "17", "31", "115", "\ue06d"))
            if (includeMeter) {
                append(drawText("G", "17", "42", "110", "\ue084"))
                append(drawText("G", "17", "42", "118", "\ue084"))
            }
            append(drawText("A", "6.6", "72", "102.2", "3"))
            append("74 133 m 74 150 l S\n")
            append(drawText("A", "5", "69", "91", "P.M."))
            append("76 100 m 85 100 91 94 96 82 c S\n")
            append(drawText("G", "8", "96", "82", "\ueb78"))
            append(drawText("A", "6", "94", "75", "½"))
            append(drawText("A", "6.6", "114", "132.2", "X"))
            append("116 133 m 116 150 l S\n")
            if (includeRest) append(drawText("G", "12", "150", "114", "\ue4e5"))
            append(drawText("A", "6.6", "190", "126.2", "<7>"))
            append("196 133 m 196 150 l S\n")
            append(drawText("A", "6.6", "275", "132.2", "7"))
            append(drawText("A", "6.6", "275", "126.2", "5"))
            append("277 120 m 281 120 283 123 283 127 c 283 132 281 135 277 135 c 273 135 271 132 271 127 c 271 123 273 120 277 120 c s\n")
        }
        return wrapPdf(content)
    }

    private fun wrapPdf(content: String, continuation: String? = null, includeEffectFont: Boolean = false): ByteArray {
        val map = "2 beginbfrange\n<0000> <00ff> <0000>\n<e000> <efff> <e000>\nendbfrange\n"
        val labelId = if (continuation == null) 9 else 11
        val labelResource = if (includeEffectFont) " /T $labelId 0 R" else ""
        fun page(stream: Int) = "<< /Type /Page /Parent 2 0 R /Resources << /Font << /A 5 0 R /G 6 0 R$labelResource >> >> /MediaBox [0 0 400 400] /Contents $stream 0 R >>"
        fun stream(data: String) = "<< /Length ${data.length} >>\nstream\n${data}endstream"
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            if (continuation == null) "<< /Type /Pages /Kids [3 0 R] /Count 1 >>" else "<< /Type /Pages /Kids [3 0 R 9 0 R] /Count 2 >>",
            page(4),
            stream(content),
            "<< /Type /Font /Subtype /Type0 /BaseFont /ArialRegular /Encoding /Identity-H /DescendantFonts [7 0 R] /ToUnicode 8 0 R >>",
            "<< /Type /Font /Subtype /Type0 /BaseFont /GPBravuraRegular /Encoding /Identity-H /DescendantFonts [7 0 R] /ToUnicode 8 0 R >>",
            "<< /Type /Font /Subtype /CIDFontType2 /DW 550 >>",
            stream(map)
        )
        if (continuation != null) objects.addAll(listOf(page(10), stream(continuation)))
        if (includeEffectFont) objects.add("<< /Type /Font /Subtype /Type0 /BaseFont /TimesNewRomanRegular /Encoding /Identity-H /DescendantFonts [7 0 R] /ToUnicode 8 0 R >>")
        return classicPdf(objects)
    }

    private fun classicPdf(objects: List<String>): ByteArray = buildString {
        append("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, value ->
            length.also { append("${index + 1} 0 obj\n$value\nendobj\n") }
        }
        val xref = length
        append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) append("%010d 00000 n \n".format(Locale.ROOT, offset))
        append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun nestedForms(recursive: Boolean = false, scale: Double = 1.0): ByteArray {
        val objects = exampleObjects(examplePdf()).values.map { it.trimEnd() }.toMutableList()
        val inner = objects[3].substringAfter("stream\n").substringBeforeLast("endstream") +
            drawText("G", "17", "31", "14", "\ue06d") +
            "q 0 0 400 400 re 30 65 20 30 re W* n\n" +
            drawText("G", "17", "31", "75", "\ue06d") + "Q\n"
        fun stream(data: String, entries: String = "") =
            "<< /Length ${data.length}$entries >>\nstream\n${data}\nendstream"
        val left = 20 + scale * 26
        val bottom = 20 + scale * 20
        val width = scale * 400
        objects[1] = "<< /Type /Pages /Kids [3 0 R] /Count 1 /CropBox [$left $bottom ${left + width} ${bottom + width}] >>"
        objects[2] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 460 460] /Resources << /XObject << /Layout 9 0 R >> >> /Contents 4 0 R >>"
        objects[3] = stream("q $scale 0 0 $scale 20 20 cm /Layout Do Q")
        val outerResources = "/XObject << /Panel ${if (recursive) 9 else 10} 0 R >>"
        objects.add(stream("/Panel Do",
            " /Type /XObject /Subtype /Form /BBox [0 0 410 420] /Matrix [1 0 0 1 22 15] /Resources << $outerResources >>"))
        objects.add(stream(inner,
            " /Type /XObject /Subtype /Form /BBox [0 0 400 380] /Matrix [1 0 0 1 4 5] /Resources << /Font << /A 5 0 R /G 6 0 R >> >>"))
        return classicPdf(objects)
    }

    private fun exampleObjects(original: ByteArray): Map<Int, String> =
        Regex("""(?ms)^(\d+) 0 obj\n(.*?)^endobj$""").findAll(original.toString(Charsets.ISO_8859_1))
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }

    private fun compactPdf(original: ByteArray): ByteArray {
        val objects = exampleObjects(original)
        val compressed = listOf(1, 2, 3, 5, 6, 7)
        val header = StringBuilder()
        val values = StringBuilder()
        for (id in compressed) {
            header.append("$id ${values.length} ")
            values.append(objects.getValue(id))
        }
        val container = 9
        val index = 10
        val packed = deflate((header.toString() + values).toByteArray(Charsets.ISO_8859_1))
        return buildString {
            append("%PDF-1.5\n")
            val offsets = objects.filterKeys { it !in compressed }.mapValues { (id, body) ->
                length.also { append("$id 0 obj\n${body}endobj\n") }
            }.toMutableMap()
            offsets[container] = length
            append("9 0 obj\n<< /Type /ObjStm /N ${compressed.size} /First ${header.length} /Filter /FlateDecode /Length ${packed.length} >>\n")
            append("stream\n$packed\nendstream\nendobj\n")
            offsets[index] = length
            val entries = (0..index).flatMap { id ->
                when {
                    id in compressed -> listOf(2, container, compressed.indexOf(id))
                    id in offsets -> listOf(1, offsets.getValue(id), 0)
                    else -> listOf(0, 0, 65535)
                }
            }
            append(indexStream(index, index + 1, entries, root = "1 0 R"))
            append("startxref\n${offsets.getValue(index)}\n%%EOF\n")
        }.toByteArray(Charsets.ISO_8859_1)
    }

    private fun indexStream(id: Int, size: Int, entries: List<Int>, index: String? = null, root: String? = null): String {
        val width = 7
        val rows = entries.chunked(3).map { (type, location, generation) ->
            ByteBuffer.allocate(width).put(type.toByte()).putInt(location).putShort(generation.toShort()).array()
        }
        val predicted = ByteArray(rows.size * (width + 1))
        for ((row, bytes) in rows.withIndex()) {
            val offset = row * (width + 1)
            predicted[offset] = 2
            for (column in bytes.indices) {
                predicted[offset + column + 1] = (bytes[column] - (rows.getOrNull(row - 1)?.get(column) ?: 0)).toByte()
            }
        }
        val optional = (index?.let { " /Index [$it]" } ?: "") + (root?.let { " /Root $it" } ?: "")
        val encoded = deflate(predicted)
        return "$id 0 obj\n<< /Type /XRef /Size $size /W [1 4 2]$optional /Filter [/FlateDecode] " +
            "/DecodeParms [<< /Predictor 12 /Columns $width >>] /Length ${encoded.length} >>\nstream\n$encoded\nendstream\nendobj\n"
    }

    private fun deflate(bytes: ByteArray): String = ByteArrayOutputStream().let { encoded ->
        DeflaterOutputStream(encoded).use { it.write(bytes) }
        encoded.toByteArray().toString(Charsets.ISO_8859_1)
    }
}
