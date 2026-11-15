package com.stringpage.app.pdf

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

internal object AndroidRasterGlyphs {
    @Volatile private var cached: PdfRasterGlyphs? = null

    @Synchronized fun load(assets: AssetManager): PdfRasterGlyphs {
        cached?.let { return it }
        val music = Typeface.createFromAsset(assets, "Bravura.otf")
        val sans = listOf(Typeface.SANS_SERIF, Typeface.create("sans-serif-condensed", Typeface.NORMAL))
        val measure = 40f
        val glyphs = ArrayList<RasterGlyph>()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val bounds = Rect()
        fun glyph(alphabet: RasterAlphabet, token: String, name: String, font: Typeface, size: Float) {
            paint.typeface = font
            paint.textSize = size
            paint.getTextBounds(token, 0, token.length, bounds)
            if (bounds.isEmpty) return
            val width = bounds.width() + 6
            val height = bounds.height() + 6
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                Canvas(bitmap).drawText(token, 3f - bounds.left, 3f - bounds.top, paint)
                val patch = bitmapToInk(bitmap).filter(RasterBox(0, 0, width, height))
                val inkBounds = patch.bounds() ?: return
                val shape = patch.shape() ?: return
                glyphs.add(RasterGlyph(alphabet, name, shape, inkBounds.height / measure.toDouble()))
            } finally { bitmap.recycle() }
        }
        for (font in sans) for (value in PdfRasterGlyphs.frets) {
            glyph(RasterAlphabet.FRET, value, if (value == "x") "X" else value, font, measure * 1.1f)
        }
        for ((token, value) in PdfRasterGlyphs.rests) glyph(RasterAlphabet.REST, token, value, music, measure * 2.667f)
        for ((token, value) in PdfRasterGlyphs.flags) glyph(RasterAlphabet.FLAG, token, value, music, measure * 2.667f)
        for ((token, value) in PdfRasterGlyphs.meter) glyph(RasterAlphabet.METER, token, value, music, measure * 2.667f)
        glyph(RasterAlphabet.TUPLET, "3", "3", Typeface.create("serif", Typeface.ITALIC), measure * 1.41f)
        for (label in listOf("P.M.", "AH", "PH", "H", "P", "½", "¼", "full")) {
            glyph(RasterAlphabet.TECHNIQUE, label, label, Typeface.create("serif", Typeface.BOLD), measure * if (label == "P.M.") 1.25f else 1.1f)
        }
        return PdfRasterGlyphs(glyphs).also { cached = it }
    }

    fun bitmapToInk(bitmap: Bitmap): PdfRasterImage {
        val width = bitmap.width
        val height = bitmap.height
        val ink = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for ((x, color) in row.withIndex()) {
                val brightness = (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) shr 8
                ink[y * width + x] = (255 - brightness).toByte()
            }
        }
        return PdfRasterImage(width, height, ink)
    }
}
