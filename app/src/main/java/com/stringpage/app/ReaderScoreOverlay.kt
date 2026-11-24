@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.model.Beat
import alphaTab.model.Note
import alphaTab.rendering.utils.Bounds
import alphaTab.rendering.utils.BoundsLookup
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Score annotations share the renderer's coordinates without taking pointer input. */
internal class ReaderScoreOverlay(private val score: AlphaTabView) : View(score.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val density = resources.displayMetrics.density
    private var loop: IntRange? = null
    private var pendingStart: Int? = null
    private var editBeat: Beat? = null
    private var editNote: Note? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun updateLoop(range: IntRange?, start: Int?) {
        loop = range
        pendingStart = start
        invalidate()
    }

    fun updateEditSelection(beat: Beat?, note: Note?) {
        editBeat = beat
        editNote = note
        invalidate()
    }

    fun measureAt(viewX: Float, viewY: Float): Int? {
        val coordinates = coordinates() ?: return null
        if (!coordinates.clip.contains(viewX.toInt(), viewY.toInt())) return null
        val x = (viewX - coordinates.left) / coordinates.scale
        val y = (viewY - coordinates.top) / coordinates.scale
        for (system in coordinates.lookup.staffSystems) {
            val systemBox = system.realBounds
            if (y !in systemBox.y..(systemBox.y + systemBox.h)) continue
            for (bar in system.bars) {
                val bounds = bar.realBounds
                if (x in bounds.x..(bounds.x + bounds.w)) return bar.index.toInt() + 1
            }
        }
        return null
    }

    /** Leave a little of the next system visible near the end of the current system. */
    fun revealNextSystem(barNumber: Int) {
        val coordinates = coordinates() ?: return
        val viewport = score.findViewById<ScrollView>(net.alphatab.R.id.innerScroll) ?: return
        val current = coordinates.lookup.findMasterBarByIndex((barNumber - 1).toDouble()) ?: return
        val systems = coordinates.lookup.staffSystems
        val row = current.staffSystemBounds ?: return
        if (row.bars.length == 0.0 || current.index < row.bars.last().index - 1) return
        val index = systems.indexOf(row).toInt()
        if (index < 0 || index + 1 >= systems.length.toInt()) return
        val next = systems[index + 1].realBounds
        val nextTop = coordinates.top + next.y * coordinates.scale
        val visibleBottom = coordinates.clip.bottom
        val desired = nextTop + 52 * density - visibleBottom
        val currentBottom = coordinates.top + (row.realBounds.y + row.realBounds.h) * coordinates.scale
        val keepVisible = minOf(84 * density, coordinates.clip.height() * 0.36f)
        val available = currentBottom - coordinates.clip.top - keepVisible
        val delta = minOf(desired, available).roundToInt()
        if (delta > 4 * density) viewport.smoothScrollTo(0, viewport.scrollY + delta)
    }

    override fun onDraw(canvas: Canvas) {
        if (loop == null && pendingStart == null && editBeat == null) return
        val coordinates = coordinates() ?: return
        canvas.save()
        canvas.clipRect(coordinates.clip)
        canvas.translate(coordinates.left, coordinates.top)
        canvas.scale(coordinates.scale, coordinates.scale)
        val dp = density / coordinates.scale
        val visibleTop = (coordinates.clip.top - coordinates.top) / coordinates.scale
        val visibleBottom = (coordinates.clip.bottom - coordinates.top) / coordinates.scale
        val range = loop
        for (system in coordinates.lookup.staffSystems) {
            val band = system.realBounds
            if (band.y + band.h < visibleTop || band.y > visibleBottom) continue
            for (bar in system.bars) {
                val number = bar.index.toInt() + 1
                if (range != null && number in range) {
                    val b = bar.realBounds
                    paint.style = Paint.Style.FILL
                    paint.color = (AppUi.accent and 0xffffff) or 0x13000000
                    if (b.w > 0 && b.h > 0) canvas.drawRect(rect(b), paint)
                    if (number == range.first) drawEndpoint(canvas, b, "A", false, dp)
                    if (number == range.last) drawEndpoint(canvas, b, "B", true, dp)
                } else if (number == pendingStart) drawEndpoint(canvas, bar.realBounds, "A", false, dp)
            }
        }
        editBeat?.let { selected ->
            coordinates.lookup.findBeats(selected)?.forEach { beat ->
                val glyph = editNote?.let { note -> beat.notes?.firstOrNull { it.note === note } }
                val b = glyph?.noteHeadBounds ?: if (editNote == null) beat.visualBounds else return@forEach
                rect(b).inset(-2 * dp, -2 * dp)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * dp
                paint.color = AppUi.accent
                canvas.drawRoundRect(box, 3 * dp, 3 * dp, paint)
            }
        }
        canvas.restore()
    }

    private fun drawEndpoint(canvas: Canvas, b: Bounds, text: String, right: Boolean, dp: Float) {
        val edge = (b.x + if (right) b.w else 0.0).toFloat()
        val top = b.y.toFloat()
        paint.color = AppUi.accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * dp
        canvas.drawLine(edge, top, edge, (b.y + b.h).toFloat(), paint)
        paint.style = Paint.Style.FILL
        val left = if (right) edge - 18 * dp else edge
        box.set(left, top - 15 * dp, left + 18 * dp, top)
        canvas.drawRoundRect(box, 3 * dp, 3 * dp, paint)
        paint.color = AppUi.onAccent
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 10 * dp
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, box.centerX(), top - 4 * dp, paint)
    }

    private fun rect(b: Bounds): RectF = box.apply {
        set(b.x.toFloat(), b.y.toFloat(), (b.x + b.w).toFloat(), (b.y + b.h).toFloat())
    }

    private data class Coordinates(val lookup: BoundsLookup, val clip: Rect, val left: Float, val top: Float, val scale: Float)

    private fun coordinates(): Coordinates? {
        val lookup = score.api.boundsLookup ?: return null
        val viewport = score.findViewById<View>(net.alphatab.R.id.outerScroll) ?: return null
        val surface = score.findViewById<View>(net.alphatab.R.id.renderSurface) ?: return null
        if (viewport.measuredWidth <= 0 || score.api.container.width <= 0) return null
        val offset = IntArray(2).also(::getLocationOnScreen)
        val origin = IntArray(2).also(surface::getLocationOnScreen)
        val viewportOrigin = IntArray(2).also(viewport::getLocationOnScreen)
        val clip = Rect()
        if (!viewport.getLocalVisibleRect(clip)) return null
        clip.offset(viewportOrigin[0] - offset[0], viewportOrigin[1] - offset[1])
        return Coordinates(lookup, clip, (origin[0] - offset[0]).toFloat(), (origin[1] - offset[1]).toFloat(),
            (viewport.measuredWidth / score.api.container.width).toFloat())
    }
}
