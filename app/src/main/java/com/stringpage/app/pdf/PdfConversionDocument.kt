package com.stringpage.app.pdf

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
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
    private val printed = try { graphics?.title()?.contains("Songsterr Tabs with Rhythm", ignoreCase = true) == true }
    catch (_: PdfConversionException) { false }
    private val vector by lazy { graphics?.let { PdfTabRecognition.Document(it) } }
    private val raster by lazy { PdfRasterTab(AndroidRasterGlyphs.load(assets)) }
    private val pages = ArrayList<Result<PdfTabResult>>()
    private var meter: PdfRasterTab.MeterResult? = null
    private var rasterTempo: Int? = null
    private var nextBar = 1

    fun preview(page: Int): Bitmap = render(page, 1_400)

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
        val geometry = try { graphics?.geometry(page - 1, collectInkOutlines = printed) }
        catch (error: InterruptedException) { throw error }
        catch (_: Exception) { null }
        if (geometry != null && !geometry.containsExternalDrawing && geometry.text.any { it.text in PdfGeometry.TAB_CLEFS }) {
            meter = null
            rasterTempo = null
            return checkNotNull(vector).read(page, geometry)
        }
        val large = try { render(page, 2_600) }
        catch (_: java.io.IOException) { throw PdfConversionException("无法读取第 $page 页的谱面图像。") }
        catch (_: IllegalStateException) { throw PdfConversionException("无法渲染第 $page 页的谱面图像。") }
        try {
            val hints = if (printed && geometry != null && graphics != null) {
                PdfRasterPrintLayout(graphics.pageWidth(page - 1), geometry.lines, geometry.inkOutlines)
            } else null
            val preceding = pages.lastOrNull()?.getOrNull()?.takeIf { it.scanned }
                ?.bars?.lastOrNull()?.beats?.lastOrNull()?.notes.orEmpty()
            val (result, signature) = raster.recognize(AndroidRasterGlyphs.bitmapToInk(large), page, nextBar, meter, hints, preceding)
            meter = signature
            rasterTempo = result.bpm ?: rasterTempo
            return result.copy(bpm = rasterTempo)
        } finally { large.recycle() }
    }

    private fun render(page: Int, longestEdge: Int): Bitmap = withRenderer { renderer ->
        renderer.openPage(page - 1).use { original ->
            val scale = longestEdge.toFloat() / maxOf(original.width, original.height)
            Bitmap.createBitmap((original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
                try {
                    eraseColor(Color.WHITE)
                    original.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (error: Exception) { recycle(); throw error }
            }
        }
    }

    private fun <T> withRenderer(use: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use(use) }
}
