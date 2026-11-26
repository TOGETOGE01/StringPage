package com.stringpage.app.pdf

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** The same import session serves preview, paging and whole-document export. */
internal class PdfConversionDocument(private val file: File, bytes: ByteArray, assets: AssetManager) {
    val pageCount = withRenderer { it.pageCount }.also {
        pdfRequire(it in 1..200, "请选择 1 到 200 页的 PDF 文件。")
    }
    private val graphics = try { VectorPdf(bytes) }
    catch (error: InterruptedException) { throw error }
    catch (_: Exception) { null }
    private var printed = try { graphics?.title()?.contains("Songsterr Tabs with Rhythm", ignoreCase = true) == true }
    catch (_: PdfConversionException) { false }
    private val vector by lazy { graphics?.let { PdfTabRecognition.Document(it) } }
    private val raster by lazy { PdfRasterTab(AndroidRasterGlyphs.load(assets)) }
    private val pages = ArrayList<Result<PdfTabResult>>()
    private val uprightRotation = IntArray(pageCount)
    private var meter: PdfRasterTab.MeterResult? = null
    private var rasterTempo: Int? = null
    private var nextBar = 1

    fun preview(page: Int): Bitmap {
        pdfRequire(page in 1..pageCount, "请选择有效页码。")
        return render(page, 1_400)
    }

    fun read(page: Int): PdfTabResult {
        pdfRequire(page in 1..pageCount, "请选择有效页码。")
        while (pages.size < page) {
            checkCancelled()
            val result = try {
                val recognized = readPage(pages.size + 1)
                val start = nextBar
                nextBar += recognized.bars.size
                Result.success(recognized.copy(tracks = recognized.tracks.map { track ->
                    track.copy(bars = track.bars.mapIndexed { index, bar -> bar.copy(number = start + index) })
                }))
            } catch (error: PdfConversionException) {
                meter = null
                rasterTempo = null
                Result.failure(error)
            }
            pages.add(result)
        }
        return pages[page - 1].getOrThrow()
    }

    private fun readPage(page: Int): PdfTabResult {
        val angle = graphics?.pageRotation(page - 1) ?: 0
        val geometry = geometry(page, printed)
        if (geometry != null && !geometry.containsExternalDrawing && geometry.text.any { it.text in PdfGeometry.TAB_CLEFS }) {
            meter = null
            rasterTempo = null
            return checkNotNull(vector).read(page, geometry).also { uprightRotation[page - 1] = -angle }
        }
        val outlines = if (!printed && geometry != null && geometry.lines.size >= 12) geometry(page, true) else geometry
        val hints = if (outlines != null && graphics != null && (printed || outlines.inkOutlines.isNotEmpty())) {
            PdfRasterPrintLayout(graphics.pageWidth(page - 1), outlines.lines, outlines.inkOutlines, previouslyConfirmed = printed)
        } else null
        val preceding = pages.lastOrNull()?.getOrNull()?.takeIf { it.scanned }
            ?.bars?.lastOrNull()?.beats?.lastOrNull()?.notes.orEmpty()
        val upright = angle != 0 && hints?.let { layout ->
            val frames = layout.recoverStaves(emptyList(), 1600)
            frames.isNotEmpty() && layout.matches(frames, 1600)
        } == true
        if (upright) uprightRotation[page - 1] = -angle
        val (original, signature) = recognizeRaster(page, 2_600, hints, preceding)
        var result = original
        if (hints?.confirmed == true && original.bars.any { it.issues.isNotEmpty() }) {
            val rescans = ArrayList<PdfTabResult>()
            for (edge in listOf(3_200, 2_950, 3_500)) {
                checkCancelled()
                try { rescans.add(recognizeRaster(page, edge, hints, preceding).first) }
                catch (_: PdfConversionException) { continue }
                result = PdfRasterRescan.combine(original, rescans, preceding)
                if (result.bars.none { it.issues.isNotEmpty() }) break
            }
        }
        if (hints?.confirmed == true) printed = true
        meter = signature
        rasterTempo = result.bpm ?: rasterTempo
        return result.copy(bpm = rasterTempo)
    }

    private fun geometry(page: Int, outlines: Boolean): PdfGeometry? =
        try { graphics?.geometry(page - 1, collectInkOutlines = outlines) }
        catch (error: InterruptedException) { throw error }
        catch (_: Exception) { null }

    private fun recognizeRaster(page: Int, edge: Int, hints: PdfRasterPrintLayout?, preceding: List<PdfTabNote>):
        Pair<PdfTabResult, PdfRasterTab.MeterResult> {
        val image = try { render(page, edge) }
        catch (_: java.io.IOException) { throw PdfConversionException("无法读取第 $page 页的谱面图像。") }
        catch (_: IllegalStateException) { throw PdfConversionException("无法渲染第 $page 页的谱面图像。") }
        return try {
            raster.recognize(AndroidRasterGlyphs.bitmapToInk(image), page, nextBar, meter, hints, preceding)
        } finally { image.recycle() }
    }

    private fun render(page: Int, longestEdge: Int): Bitmap = withRenderer { renderer ->
        renderer.openPage(page - 1).use { original ->
            val scale = longestEdge.toFloat() / maxOf(original.width, original.height)
            val bitmap = Bitmap.createBitmap((original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
                try {
                    eraseColor(Color.WHITE)
                    original.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (error: Exception) { recycle(); throw error }
            }
            val angle = uprightRotation[page - 1]
            if (angle == 0) bitmap else try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setRotate(angle.toFloat()) }, false)
            } finally { bitmap.recycle() }
        }
    }

    private fun <T> withRenderer(use: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use(use) }
}
