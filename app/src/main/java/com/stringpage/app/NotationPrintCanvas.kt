@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.collections.List as AlphaList
import alphaTab.model.Color
import alphaTab.model.Font
import alphaTab.model.FontStyle
import alphaTab.model.FontWeight
import alphaTab.model.MusicFontSymbol
import alphaTab.platform.ICanvas
import alphaTab.platform.MeasuredText
import alphaTab.platform.TextAlign
import alphaTab.platform.TextBaseline
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

/** Records one notation system as vector drawing operations for Android PDF. */
internal class NotationPrintCanvas(context: Context, override var settings: Settings) : ICanvas {
    data class Segment(val picture: Picture, val ink: RectF)

    override var color = Color(0.0, 0.0, 0.0, 255.0)
    override var lineWidth = 1.0
    override var font = Font("serif", 11.0)
    override var textAlign = TextAlign.Left
    override var textBaseline = TextBaseline.Top
    private val music = Typeface.createFromAsset(context.assets, "Bravura.otf")
    private val fonts = HashMap<Pair<String, Int>, Typeface>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val musicPath = Path()
    private val ink = RectF()
    private val bounds = RectF()
    private val textBounds = Rect()
    private val transform = Matrix()
    private val rotations = ArrayDeque<Matrix>()
    private var recording: Picture? = null
    private lateinit var canvas: Canvas

    override fun beginRender(width: Double, height: Double) {
        val picture = Picture()
        recording = picture
        canvas = picture.beginRecording(ceil(width).toInt().coerceAtLeast(1) + 2, ceil(height).toInt().coerceAtLeast(1) + 2)
        path.reset()
        ink.setEmpty()
        transform.reset()
        rotations.clear()
    }

    override fun endRender(): Any? {
        val picture = recording ?: return null
        picture.endRecording()
        recording = null
        return if (ink.isEmpty) null else Segment(picture, RectF(ink))
    }
    override fun onRenderFinished(): Any? = null
    override fun destroy() { endRender() }
    override fun beginGroup(identifier: String) = Unit
    override fun endGroup() = Unit

    private fun styled(style: Paint.Style = Paint.Style.FILL): Paint {
        paint.setARGB(color.a.toInt(), color.r.toInt(), color.g.toInt(), color.b.toInt())
        paint.style = style
        paint.strokeWidth = lineWidth.toFloat()
        return paint
    }

    private fun recordBounds(stroke: Boolean = false) {
        if (stroke) bounds.inset(-paint.strokeWidth / 2f, -paint.strokeWidth / 2f)
        if (bounds.isEmpty || paint.alpha == 0) return
        transform.mapRect(bounds)
        ink.union(bounds)
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double, stroke: Boolean) {
        val style = styled(if (stroke) Paint.Style.STROKE else Paint.Style.FILL)
        bounds.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        canvas.drawRect(bounds, style)
        recordBounds(stroke)
    }
    private fun circle(x: Double, y: Double, radius: Double, stroke: Boolean) {
        val style = styled(if (stroke) Paint.Style.STROKE else Paint.Style.FILL)
        bounds.set((x - radius).toFloat(), (y - radius).toFloat(), (x + radius).toFloat(), (y + radius).toFloat())
        canvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), style)
        recordBounds(stroke)
    }
    override fun fillRect(x: Double, y: Double, w: Double, h: Double) = rect(x, y, w, h, false)
    override fun strokeRect(x: Double, y: Double, w: Double, h: Double) = rect(x, y, w, h, true)
    override fun fillCircle(x: Double, y: Double, radius: Double) = circle(x, y, radius, false)
    override fun strokeCircle(x: Double, y: Double, radius: Double) = circle(x, y, radius, true)
    override fun beginPath() = path.reset()
    override fun closePath() = path.close()
    override fun moveTo(x: Double, y: Double) = path.moveTo(x.toFloat(), y.toFloat())
    override fun lineTo(x: Double, y: Double) = path.lineTo(x.toFloat(), y.toFloat())
    override fun quadraticCurveTo(cpx: Double, cpy: Double, x: Double, y: Double) =
        path.quadTo(cpx.toFloat(), cpy.toFloat(), x.toFloat(), y.toFloat())
    override fun bezierCurveTo(cp1x: Double, cp1y: Double, cp2x: Double, cp2y: Double, x: Double, y: Double) =
        path.cubicTo(cp1x.toFloat(), cp1y.toFloat(), cp2x.toFloat(), cp2y.toFloat(), x.toFloat(), y.toFloat())
    private fun drawPath(shape: Path, stroke: Boolean = false) {
        canvas.drawPath(shape, styled(if (stroke) Paint.Style.STROKE else Paint.Style.FILL))
        @Suppress("DEPRECATION")
        shape.computeBounds(bounds, true)
        recordBounds(stroke)
    }
    override fun fill() = drawPath(path)
    override fun stroke() = drawPath(path, true)
    override fun beginRotate(centerX: Double, centerY: Double, angle: Double) {
        canvas.save()
        rotations.addLast(Matrix(transform))
        canvas.translate(centerX.toFloat(), centerY.toFloat())
        canvas.rotate(angle.toFloat())
        transform.preTranslate(centerX.toFloat(), centerY.toFloat())
        transform.preRotate(angle.toFloat())
    }
    override fun endRotate() {
        canvas.restore()
        transform.set(rotations.removeLast())
    }

    private fun textPaint() = styled().apply {
        val bold = font.weight == FontWeight.Bold
        val italic = font.style == FontStyle.Italic
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        typeface = fonts.getOrPut(font.family to style) { Typeface.create(font.family, style) }
        textSize = font.size.toFloat()
        textAlign = when (this@NotationPrintCanvas.textAlign) {
            TextAlign.Left -> Paint.Align.LEFT
            TextAlign.Center -> Paint.Align.CENTER
            TextAlign.Right -> Paint.Align.RIGHT
        }
    }

    override fun measureText(text: String): MeasuredText {
        if (text.isEmpty()) return MeasuredText(0.0, 0.0)
        val style = textPaint()
        style.getTextBounds(text, 0, text.length, textBounds)
        return MeasuredText(textBounds.width().toDouble(), textBounds.height().toDouble())
    }

    override fun fillText(text: String, x: Double, y: Double) {
        val style = textPaint()
        val baseline = when (textBaseline) {
            TextBaseline.Top -> -style.ascent() * 0.8f
            TextBaseline.Middle -> -(style.ascent() + style.descent()) / 2f
            TextBaseline.Bottom -> -style.descent()
            TextBaseline.Alphabetic -> 0f
        }
        val textY = y.toFloat() + baseline
        canvas.drawText(text, x.toFloat(), textY, style)
        style.getTextBounds(text, 0, text.length, textBounds)
        val factor = when (style.textAlign) {
            Paint.Align.LEFT -> 0f
            Paint.Align.CENTER -> 0.5f
            Paint.Align.RIGHT -> 1f
        }
        val anchor = x.toFloat() - if (factor == 0f) 0f else style.measureText(text) * factor
        bounds.set(textBounds)
        bounds.offset(anchor, textY)
        recordBounds()
    }

    override fun fillMusicFontSymbol(x: Double, y: Double, scale: Double, symbol: MusicFontSymbol, centerAtPosition: Boolean?) {
        drawGlyphs(x, y, scale, if (symbol == MusicFontSymbol.None) "" else symbol.value.toChar().toString(), centerAtPosition)
    }
    override fun fillMusicFontSymbols(x: Double, y: Double, scale: Double, symbols: AlphaList<MusicFontSymbol>, centerAtPosition: Boolean?) {
        val text = buildString { for (symbol in symbols) if (symbol != MusicFontSymbol.None) append(symbol.value.toChar()) }
        drawGlyphs(x, y, scale, text, centerAtPosition)
    }
    private fun drawGlyphs(x: Double, y: Double, scale: Double, text: String, centered: Boolean?) {
        if (text.isEmpty()) return
        val style = styled().apply {
            typeface = music
            textSize = (34 * scale).toFloat()
            textAlign = if (centered == true) Paint.Align.CENTER else Paint.Align.LEFT
        }
        // Musical glyphs stay vector outlines and don't depend on a PDF
        // viewer interpreting a subset of the notation font.
        musicPath.reset()
        style.getTextPath(text, 0, text.length, x.toFloat(), y.toFloat(), musicPath)
        drawPath(musicPath)
    }
}
