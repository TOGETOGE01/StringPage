package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.ln

internal enum class RasterAlphabet { FRET, REST, FLAG, METER, TECHNIQUE, TUPLET, PRINT_DIGIT, PRINT_TUPLET }

internal data class RasterGlyph(
    val alphabet: RasterAlphabet,
    val value: String,
    val shape: RasterShape,
    val heightInSpaces: Double
)

internal class PdfRasterGlyphs(glyphs: List<RasterGlyph>) {
    private val alphabets = glyphs.groupBy { it.alphabet }

    data class Match(val value: String, val distance: Double, val separation: Double)

    fun match(alphabet: RasterAlphabet, patch: RasterPatch, heightInSpaces: Double): Match? {
        val shape = patch.shape() ?: return null
        val results = HashMap<String, Double>()
        for (glyph in alphabets[alphabet].orEmpty()) {
            val sizeError = abs(ln(heightInSpaces / glyph.heightInSpaces))
            if (sizeError > 0.8) continue
            val error = shape.distance(glyph.shape) + 0.27 * sizeError
            if (error < (results[glyph.value] ?: Double.POSITIVE_INFINITY)) results[glyph.value] = error
        }
        val sorted = results.entries.sortedBy { it.value }
        val best = sorted.firstOrNull() ?: return null
        return Match(best.key, best.value, (sorted.getOrNull(1)?.value ?: 2.0) - best.value)
    }

    companion object {
        val frets: List<String> = (0..36).map(Int::toString) + listOf("X", "x") +
            (1..24).map { "<$it>" } + listOf("<2.4>", "<2.7>", "<3.2>", "<3.8>", "<4.4>", "<5.8>", "<6.2>", "<8.2>") +
            (0..24).map { "($it)" }
        val rests = ('\ue4e3'..'\ue4e9').mapIndexed { i, glyph -> glyph.toString() to (1 shl i).toString() }
        val flags = ('\ue241'..'\ue247' step 2).mapIndexed { i, glyph -> glyph.toString() to (i + 1).toString() }
        val meter = ('\ue080'..'\ue089').mapIndexed { i, glyph -> glyph.toString() to i.toString() }
    }
}
