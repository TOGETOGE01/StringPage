package com.stringpage.app.rse

/** Numeric XProperty conversions used by the original RSE attribute lookup.
 * GPIF numeric nodes consume a C numeric prefix; string-valued attributes use
 * whole-string conversion. Missing/unknown nodes leave earlier values intact.
 */
data class NativeNumericAttribute(val floatValue: Float, val intValue: Int) {
    companion object {
        private val numeric = Regex("[+-]?(?:0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)(?:[pP][+-]?[0-9]+)?|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|inf(?:inity)?|nan(?:\\([^)]*\\))?)",RegexOption.IGNORE_CASE)
        fun decimalPrefix(raw: String): Int = GpNoteSourceGraph.slideFlagsFromString(raw)
        private fun number(text: String, prefix: Boolean): Double {
            val value=if(prefix) text.dropWhile { it in " \t\r\n\u000b\u000c" } else text
            val token=if(prefix) numeric.find(value)?.takeIf { it.range.first==0 }?.value else numeric.matchEntire(value)?.value
            if(token==null)return 0.0
            val lower=token.lowercase(java.util.Locale.ROOT)
            if("nan" in lower)return Double.NaN
            if("inf" in lower)return if(lower.startsWith('-')) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            val parsed=if("0x" in lower && 'p' !in lower) token+"p0" else token
            return parsed.toDoubleOrNull() ?: 0.0
        }
        /** cvttss2si/cvttsd2si produce INT_MIN for non-finite/out-of-range input. */
        fun truncate(value: Float): Int = if(!value.isFinite() || value >= 2147483648.0f || value < -2147483648.0f) Int.MIN_VALUE else value.toInt()
        private fun truncate(value: Double): Int = if(!value.isFinite() || value >= 2147483648.0 || value < -2147483648.0) Int.MIN_VALUE else value.toInt()
        fun read(type: String,text: String): NativeNumericAttribute? {
            if(text.isEmpty() || text.all { it in " \t\r\n" })return null
            return when(type) {
                "Int" -> decimalPrefix(text).let { NativeNumericAttribute(it.toFloat(),it) }
                "Float" -> number(text,true).toFloat().takeUnless { it.isNaN() }?.let { NativeNumericAttribute(it,truncate(it)) }
                "Double" -> number(text,true).takeUnless { it.isNaN() }?.let { NativeNumericAttribute(it.toFloat(),truncate(it)) }
                "String" -> NativeNumericAttribute(number(text,false).toFloat(),text.toIntOrNull() ?: 0)
                "Bool" -> (if(text=="false") 0 else 1).let { NativeNumericAttribute(it.toFloat(),it) }
                else -> null
            }
        }
    }
}

/** Per-original-note attributes. Middle controls are retained even though the
 * native integer MIDI/time vector builders only consume their endpoints.
 */
data class NativeSlideParameters(
    val inStart: Float=.8f, val inMiddle: Float=.4f,
    val inRange: Int=3, val inTicks: Int=240,
    val outStart: Float=.8f, val outMiddle: Float=.4f, val outRange: Int=3
) {
    companion object {
        val IDS: IntRange = 0x29031009..0x2903100f
        fun from(values: Map<Int,NativeNumericAttribute>) = NativeSlideParameters(
            values[0x29031009]?.floatValue ?: .8f,values[0x2903100a]?.floatValue ?: .4f,
            values[0x2903100b]?.intValue ?: 3,values[0x2903100c]?.intValue ?: 240,
            values[0x2903100d]?.floatValue ?: .8f,values[0x2903100e]?.floatValue ?: .4f,
            values[0x2903100f]?.intValue ?: 3)
    }
}
