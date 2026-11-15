package com.stringpage.app.pdf

import kotlin.math.abs

internal object PdfTabTuning {
    private val standardGuitar = listOf(64, 59, 55, 50, 45, 40, 35, 30, 25)
    private val standardBass = listOf(43, 38, 33, 28)
    private val printedPitch = Regex("""^\s*=\s*([A-Ga-g])([#♯b♭]?)(-?\d)?\s*$""")
    private val namedPitch = Regex("""^([A-Ga-g])([#♯b♭]?)(-?\d)$""")
    private val pitches = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun standard(strings: Int): List<Int> = if (strings <= 5) {
        (standardBass + 23).take(strings)
    } else standardGuitar.take(strings)

    fun read(geometry: PdfGeometry, staves: List<PdfTabStaff>): List<PdfTabTrack> {
        val limit = staves.first().let { it.notation?.top ?: it.tab.top }
        val text = geometry.text.filter { it.y < limit - 4 }
        val labels = text.filter { it.text.trim().equals("Standard tuning", true) ||
            printedPitch.matches(it.text) || it.text.trim().split(Regex("\\s+")).let { words -> words.size in 4..9 && words.all(namedPitch::matches) } }
            .sortedBy { it.x }
        // The header prints one tuning block per track. Multiple altered strings are stacked in a block.
        val blocks = ArrayList<MutableList<PdfText>>()
        for (label in labels) {
            val block = blocks.lastOrNull()?.takeIf { abs(it.first().x - label.x) < 14 }
            if (block == null) blocks.add(arrayListOf(label)) else block.add(label)
        }
        return staves.mapIndexed { index, staff ->
            val strings = staff.tab.rows.size
            val block = blocks.takeIf { it.size == staves.size }?.get(index)
            val name = block?.firstOrNull()?.let { label ->
                text.filter { span -> label.y - span.y in 5.0..15.0 && label.x - span.x in -3.0..15.0 &&
                    abs(span.size - label.size) < 2 && span.text.any(Char::isLetter) && !span.font.contains("Bravura", true) }
                    .maxByOrNull { it.y }?.text?.trim()
            }?.take(70).orEmpty().ifBlank { if (staves.size == 1) "吉他" else "吉他 ${index + 1}" }
            PdfTabTrack(name, strings, block?.let { parseBlock(geometry, it, strings) }, emptyList())
        }
    }

    private fun parseBlock(geometry: PdfGeometry, block: List<PdfText>, strings: Int): List<Int>? {
        val defaults = standard(strings)
        val full = block.singleOrNull()?.text?.trim()?.split(Regex("\\s+"))
            ?.takeIf { it.size == strings }?.mapNotNull { parseNamedPitch(it) }
        if (full != null && full.size == strings) return full
        if (block.singleOrNull()?.text?.trim()?.equals("Standard tuning", true) == true) return defaults
        val tuning = defaults.toMutableList()
        val used = HashSet<Int>()
        for (label in block) {
            val match = printedPitch.matchEntire(label.text) ?: return null
            val glyph = geometry.text.singleOrNull {
                it.text.length == 1 && it.text[0] in '\ue834'..'\ue83c' && label.x - it.right in -1.0..5.0 && abs(label.y - it.y) < 3
            } ?: return null
            val string = glyph.text[0] - '\ue834'
            if (string !in 0 until strings || !used.add(string)) return null
            val pc = pitchClass(match.groupValues[1], match.groupValues[2])
            tuning[string] = match.groupValues[3].toIntOrNull()?.let { (it + 1) * 12 + pc }
                ?: (tuning[string] - 6..tuning[string] + 5).first { ((it % 12) + 12) % 12 == (pc + 12) % 12 }
        }
        return tuning.takeIf { it.all { midi -> midi in 0..96 } }
    }

    fun parseNamedPitch(text: String): Int? {
        val match = namedPitch.matchEntire(text) ?: return null
        val octave = match.groupValues[3].toInt()
        return ((octave + 1) * 12 + pitchClass(match.groupValues[1], match.groupValues[2])).takeIf { it in 0..96 }
    }

    fun name(pitch: Int): String = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[pitch % 12] + (pitch / 12 - 1)

    private fun pitchClass(letter: String, accidental: String): Int = pitches.getValue(letter.first().uppercaseChar()) + when (accidental) {
        "#", "♯" -> 1
        "b", "♭" -> -1
        else -> 0
    }
}
