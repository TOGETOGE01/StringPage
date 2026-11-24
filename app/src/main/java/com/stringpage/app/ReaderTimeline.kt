package com.stringpage.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.SeekBar

internal class ReaderTimeline(context: Context) : SeekBar(context) {
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private var loop: Pair<Float, Float>? = null

    fun showLoop(start: Double, end: Double) {
        val next = if (start.isFinite() && end.isFinite() && end > start) {
            start.toFloat().coerceIn(0f, 1f) to end.toFloat().coerceIn(0f, 1f)
        } else null
        if (next != loop) {
            loop = next
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (start, end) = loop ?: return
        val trackWidth = (width - paddingLeft - paddingRight).toFloat()
        if (trackWidth <= 0f) return
        val density = resources.displayMetrics.density
        val a = paddingLeft + start * trackWidth
        val b = paddingLeft + end * trackWidth
        val y = height / 2f + 5 * density
        marker.color = AppUi.accent
        marker.strokeWidth = 1.5f * density
        canvas.drawLine(a, y, b, y, marker)
        canvas.drawLine(a, y - 3 * density, a, y + 2 * density, marker)
        canvas.drawLine(b, y - 3 * density, b, y + 2 * density, marker)
    }
}
