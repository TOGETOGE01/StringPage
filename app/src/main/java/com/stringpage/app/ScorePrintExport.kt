@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.LayoutMode
import alphaTab.Settings
import alphaTab.StaveProfile
import alphaTab.SystemsLayoutMode
import alphaTab.collections.DoubleList
import alphaTab.model.Color
import alphaTab.model.JsonConverter
import alphaTab.model.Score
import alphaTab.rendering.ScoreRenderer
import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

internal object ScorePrintExport {
    enum class Paper(val label: String, val width: Int, val height: Int) {
        A4("A4", 595, 842), LETTER("Letter", 612, 792)
    }

    data class Options(val paper: Paper, val tracks: List<Int>, val bars: IntRange, val profile: StaveProfile)

    fun write(context: Context, score: Score, options: Options, output: OutputStream, cancelled: () -> Boolean = { false }) {
        require(options.tracks.isNotEmpty() && options.tracks.all { it in 0 until score.tracks.length.toInt() }) { "请选择要打印的轨道。" }
        require(!options.bars.isEmpty() && options.bars.first >= 1 && options.bars.last <= score.masterBars.length) { "请选择有效的小节范围。" }
        val settings = Settings().apply {
            // A lightweight initial canvas keeps the renderer independent of
            // the reader view's native Skia initialization.
            core.engine = "svg"
            core.enableLazyLoading = false
            display.layoutMode = LayoutMode.Page
            display.systemsLayoutMode = SystemsLayoutMode.Automatic
            display.staveProfile = options.profile
            display.scale = 0.88
            display.startBar = options.bars.first.toDouble()
            display.barCount = options.bars.count().toDouble()
            val ink = Color(0.0, 0.0, 0.0, 255.0)
            val muted = Color(46.0, 46.0, 46.0, 255.0)
            display.resources.apply {
                mainGlyphColor = ink
                scoreInfoColor = ink
                secondaryGlyphColor = muted
                barNumberColor = muted
                staffLineColor = muted
                barSeparatorColor = ink
            }
        }
        val json = checkNotNull(JsonConverter.scoreToJsObject(score))
        json.delete("backingtrack")
        val copy = JsonConverter.jsObjectToScore(json, settings)
        val frettedTracks = options.tracks.filter { track -> copy.tracks[track].staves.any { it.tuning.length > 0 && !it.isPercussion } }
        require(options.profile != StaveProfile.Tab || frettedTracks.isNotEmpty()) { "所选轨道没有六线谱，请选择五线谱。" }
        val selected = if (options.profile == StaveProfile.Tab) frettedTracks else options.tracks
        for (index in selected) for (staff in copy.tracks[index].staves) {
            staff.showStandardNotation = options.profile != StaveProfile.Tab
            staff.showTablature = options.profile != StaveProfile.Score && !staff.isPercussion && staff.tuning.length > 0
        }
        val trackIndices = DoubleList()
        selected.forEach { trackIndices.push(it.toDouble()) }
        val margin = 33f
        val footerHeight = 24f
        val glyphInset = 8f
        val paper = options.paper
        val contentWidth = paper.width - 2 * margin
        val contentHeight = paper.height - 2 * margin - footerHeight
        val layoutWidth = contentWidth - 2 * glyphInset
        val layoutHeight = contentHeight - 2 * glyphInset
        val renderer = ScoreRenderer(settings)
        renderer.canvas?.destroy()
        renderer.canvas = NotationPrintCanvas(context, settings)
        renderer.width = layoutWidth.toDouble()
        try {
            val document = PdfDocument()
            try {
                var page: PdfDocument.Page? = null
                var pageNumber = 0
                var used = 0f
                var previousBottom = 0.0
                var failure: Throwable? = null
                val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 9f
                    textAlign = Paint.Align.RIGHT
                }
                fun finishPage() {
                    val current = page ?: return
                    current.canvas.drawText(pageNumber.toString(), paper.width - margin, paper.height - margin, footer)
                    document.finishPage(current)
                    page = null
                }
                renderer.error.on { failure = it }
                renderer.partialRenderFinished.on { segment ->
                    check(!cancelled()) { "已取消导出。" }
                    val rendered = segment.renderResult as? NotationPrintCanvas.Segment ?: return@on
                    val picture = rendered.picture
                    if (segment.height <= 0.0 || segment.width <= 0.0) return@on
                    val ink = rendered.ink
                    val leftOverhang = minOf(0.0, segment.x + ink.left)
                    val rightEdge = maxOf(layoutWidth.toDouble(), segment.x + maxOf(segment.width, ink.right.toDouble()))
                    val topOverhang = minOf(0f, ink.top)
                    val block = maxOf(segment.height.toFloat(), ink.bottom) - topOverhang
                    val scale = minOf(1.0, layoutWidth / (rightEdge - leftOverhang), (layoutHeight / block).toDouble()).toFloat()
                    val height = block * scale
                    var gap = if (used == 0f) 0f else (segment.y - previousBottom).coerceIn(7.0, 32.0).toFloat() * scale
                    if (page == null || used + gap + height > layoutHeight + 0.001f) {
                        finishPage()
                        page = document.startPage(PdfDocument.PageInfo.Builder(paper.width, paper.height, ++pageNumber).create())
                        used = 0f
                        gap = 0f
                    }
                    val canvas = checkNotNull(page).canvas
                    val left = margin + glyphInset + (segment.x - leftOverhang).toFloat() * scale
                    val top = margin + glyphInset + used + gap - topOverhang * scale
                    val checkpoint = canvas.save()
                    canvas.clipRect(margin, margin, margin + contentWidth, margin + contentHeight)
                    canvas.drawPicture(picture, RectF(left, top, left + picture.width * scale, top + picture.height * scale))
                    canvas.restoreToCount(checkpoint)
                    used += height + gap
                    previousBottom = segment.y + segment.height
                }
                try {
                    renderer.renderScore(copy, trackIndices)
                    failure?.let { throw it }
                    require(pageNumber > 0) { "所选范围没有可打印的谱面。" }
                } finally { finishPage() }
                check(!cancelled()) { "已取消导出。" }
                document.writeTo(output)
            } finally { document.close() }
        } finally { renderer.destroy() }
    }
}
