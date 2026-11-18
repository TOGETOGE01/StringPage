package com.stringpage.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

/** Shared native surfaces and vector icons. No external font or image assets are required. */
object AppUi {
    internal var theme = ReaderTheme.Clay
    private val colors get() = theme.palette
    val ink get() = ThemePalette.opaque(colors.ink)
    val muted get() = ThemePalette.opaque(colors.muted)
    val canvas get() = ThemePalette.opaque(colors.canvas)
    val panel get() = ThemePalette.opaque(colors.panel)
    val control get() = ThemePalette.opaque(colors.control)
    val line get() = ThemePalette.opaque(colors.line)
    val accent get() = ThemePalette.opaque(colors.accent)
    val onAccent get() = ThemePalette.opaque(colors.onAccent)
    val selected get() = ThemePalette.opaque(colors.selected)
    val score get() = ThemePalette.opaque(colors.score)
    val rail get() = ThemePalette.opaque(colors.rail)
    val railActive get() = ThemePalette.opaque(colors.railActive)
    val railSurface get() = ThemePalette.opaque(colors.railSurface)
    val railLine get() = ThemePalette.opaque(colors.railLine)
    val railInk get() = ThemePalette.opaque(colors.railInk)
    val railMuted get() = ThemePalette.opaque(colors.railMuted)
    val railAccent get() = ThemePalette.opaque(colors.railAccent)

    val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val display = Typeface.create("serif", Typeface.BOLD)

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context, color: Int, radius: Int = 12, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        border?.let { setStroke(dp(context, 1), it) }
    }
    fun ripple(context: Context, color: Int, radius: Int = 12, border: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(colors.accent or 0x27000000), surface(context, color, radius, border),
        surface(context, Color.WHITE, radius)
    )
    fun icon(context: Context, name: String, color: Int = ink, size: Int = 18): Drawable = LineIcon(name, color).apply {
        setBounds(0, 0, dp(context, size), dp(context, size))
    }
    fun iconButton(button: Button, name: String, color: Int = ink, size: Int = 18) {
        button.setCompoundDrawables(icon(button.context, name, color, size), null, null, null)
        button.compoundDrawablePadding = dp(button.context, 8)
    }

    private class LineIcon(private val name: String, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = 1.7f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            fun line(x: Float, y: Float, endX: Float, endY: Float) = canvas.drawLine(x, y, endX, endY, paint)
            fun path(vararg points: Float, close: Boolean = false) {
                val p = Path(); p.moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1])
                if (close) p.close()
                canvas.drawPath(p, paint)
            }
            when (name) {
                "play" -> { paint.style = Paint.Style.FILL; path(8f, 5f, 19f, 12f, 8f, 19f, close = true); paint.style = Paint.Style.STROKE }
                "pause" -> { line(9f, 6f, 9f, 18f); line(15f, 6f, 15f, 18f) }
                "restart" -> { line(5f, 6f, 5f, 18f); path(18f, 6f, 8f, 12f, 18f, 18f, close = true) }
                "file" -> { path(7f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 5f, 21f, 5f, 3f, 7f, 3f); path(14f, 3f, 14f, 8f, 19f, 8f); line(9f, 13f, 15f, 13f); line(9f, 17f, 15f, 17f) }
                "strings" -> {
                    line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(16f, 6f, 2.6f, paint)
                    canvas.drawCircle(9f, 12f, 2.6f, paint)
                    canvas.drawCircle(13f, 18f, 2.6f, paint)
                    paint.style = Paint.Style.STROKE
                }
                "score" -> {
                    for (y in 6..18 step 3) line(2f, y.toFloat(), 22f, y.toFloat())
                    path(9.5f, 15f, 9.5f, 5f, 17.5f, 3.5f, 17.5f, 12f)
                    paint.style = Paint.Style.FILL
                    canvas.drawOval(5f, 13.4f, 10f, 16.8f, paint)
                    canvas.drawOval(13f, 10.4f, 18f, 13.8f, paint)
                    paint.style = Paint.Style.STROKE
                }
                "import" -> { path(4f, 9f, 4f, 20f, 20f, 20f, 20f, 9f); line(12f, 3f, 12f, 14f); path(8f, 10f, 12f, 14f, 16f, 10f) }
                "clock" -> { canvas.drawCircle(12f, 12f, 9f, paint); path(12f, 7f, 12f, 12f, 16f, 14f) }
                "full" -> { path(4f, 9f, 4f, 4f, 9f, 4f); path(15f, 4f, 20f, 4f, 20f, 9f); path(20f, 15f, 20f, 20f, 15f, 20f); path(9f, 20f, 4f, 20f, 4f, 15f) }
                "more" -> { paint.style = Paint.Style.FILL; for (x in listOf(5f, 12f, 19f)) canvas.drawCircle(x, 12f, 1.5f, paint); paint.style = Paint.Style.STROKE }
                "audio" -> { line(4f, 10f, 4f, 14f); line(8f, 6f, 8f, 18f); line(12f, 3f, 12f, 21f); line(16f, 7f, 16f, 17f); line(20f, 10f, 20f, 14f) }
                "track" -> { for (y in listOf(6f, 12f, 18f)) { canvas.drawCircle(4f, y, 1f, paint); line(9f, y, 21f, y) } }
                "mix" -> { for ((x, y) in listOf(5f to 8f, 12f to 16f, 19f to 10f)) { line(x, 3f, x, y - 3f); line(x, y + 3f, x, 21f); canvas.drawCircle(x, y, 2.5f, paint) } }
                "theme" -> {
                    canvas.drawRoundRect(3f, 4f, 21f, 20f, 3f, 3f, paint)
                    line(10f, 4f, 10f, 20f); line(14f, 9f, 17f, 9f); line(14f, 14f, 18f, 14f)
                }
                "loop" -> { path(4f, 10f, 4f, 6f, 20f, 6f); path(17f, 3f, 20f, 6f, 17f, 9f); path(20f, 14f, 20f, 18f, 4f, 18f); path(7f, 15f, 4f, 18f, 7f, 21f) }
                "zoom" -> { canvas.drawCircle(10f, 10f, 6f, paint); line(14.5f, 14.5f, 21f, 21f); line(7f, 10f, 13f, 10f); line(10f, 7f, 10f, 13f) }
                "location" -> {
                    canvas.drawCircle(12f, 12f, 6f, paint); canvas.drawCircle(12f, 12f, 2f, paint)
                    line(12f, 2f, 12f, 6f); line(12f, 18f, 12f, 22f); line(2f, 12f, 6f, 12f); line(18f, 12f, 22f, 12f)
                }
                "check" -> path(5f, 12f, 10f, 17f, 20f, 7f)
                "chevron" -> path(9f, 5f, 16f, 12f, 9f, 19f)
                "down" -> path(5f, 9f, 12f, 16f, 19f, 9f)
                "back" -> { path(11f, 5f, 4f, 12f, 11f, 19f); line(4f, 12f, 20f, 12f) }
                "edit" -> {
                    path(4f, 16f, 15f, 5f, 19f, 9f, 8f, 20f, 3f, 21f, close = true)
                    line(12.5f, 7.5f, 16.5f, 11.5f)
                }
                "undo", "redo" -> {
                    if (name == "redo") { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
                    path(10f, 4f, 4f, 10f, 10f, 16f)
                    canvas.drawPath(Path().apply { moveTo(4f, 10f); lineTo(14f, 10f); quadTo(21f, 10f, 21f, 17f) }, paint)
                }
                "export" -> { path(4f, 13f, 4f, 20f, 20f, 20f, 20f, 13f); line(12f, 16f, 12f, 3f); path(8f, 7f, 12f, 3f, 16f, 7f) }
                "info" -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 11f, 12f, 16f); canvas.drawCircle(12f, 7.5f, 0.6f, paint) }
                "trash" -> { path(5f, 7f, 6f, 21f, 18f, 21f, 19f, 7f); line(3f, 7f, 21f, 7f); path(8f, 7f, 8f, 3f, 16f, 3f, 16f, 7f); line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f) }
                "tempo" -> { path(6f, 20f, 10f, 4f, 14f, 4f, 18f, 20f, 6f, 20f); line(12f, 15f, 19f, 6f); line(9f, 16f, 15f, 16f) }
            }
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
