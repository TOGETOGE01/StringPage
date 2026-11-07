package com.stringpage.app.rse

import java.util.Locale

/** Metadata policy used by the real GPRSE SoundBank parser (d6b70/d8ae0),
 * after SoundFontV7 has supplied its raw Layer. This explicitly selects the
 * runtime policy instead of AMSoundFont's distinct preview-parser behavior.
 * Shared Layer exclusions, wave map, corrections and node factories are reused;
 * runtime dynamics and harmonic coordinates are verified against GPRSE itself.
 */
class RseBankMetadata(val bank: RseSoundBank) {
    private val metadata = NativeBankMetadata(bank, NativeBankMetadata.Engine.RseRuntime)
    val pitched = metadata.pitched
    val samples = metadata.samples
    val root = metadata.root
    val tree = metadata.tree

    companion object {
        private val pitchedTypes = setOf("accordion", "acousticBass", "acousticPiano", "aerophone", "bagpipe", "bandoneon", "banjo", "bassSynthesizer", "bassoon", "bottleBow", "bowed", "brass", "brassSynthesizer", "celesta", "cello", "choir", "chordophone", "clarinet", "contrabass", "contrabassoon", "electricBass", "electricGuitar", "electricOrgan", "electricPiano", "electricSitar", "electrophone", "englishHorn", "flugelHorn", "flute", "frenchHorn", "fretlessBass", "harmonica", "harp", "harpsichord", "keySynthesizer", "leadSynthesizer", "mandolin", "mellotronBrass", "mellotronCello", "mellotronChoir", "mellotronFlute", "mellotronStrings", "mellotronViolas", "nylonGuitar", "oboe", "padSynthesizer", "piccolo", "pipeOrgan", "pitched", "pitchedIdiophone", "pitchedMembraphone", "recorder", "reed", "resonatorGuitar", "saxophone", "seqSynthesizer", "steelGuitar", "stringed", "synthBass", "timpani", "trombone", "trumpet", "tuba", "ukulele", "uprightBass", "vibraphone", "viola", "violin", "vocal", "voice", "woodwind", "xylophone")
        private val unpitchedTypes = setOf("agogo", "bellTree", "bongo", "cabasa", "castanets", "china", "claves", "conga", "cowbell", "crash", "cuica", "cymbal", "drum", "grancassa", "guiro", "handClap", "hiHat", "idiophone", "jingleBell", "kickDrum", "maraca", "melodicTom", "membraphone", "piatti", "reverseCymbal", "ride", "shaker", "snare", "splash", "surdo", "synthDrum", "taikoDrum", "tambourine", "timbale", "tinkelBell", "tom", "triangle", "unpitched", "vibraslap", "whistle", "woodblock")
        /** The original GPCore registry recognizes exact case-sensitive names. */
        fun isPitched(type: String): Boolean = when (type) {
            in pitchedTypes -> true
            in unpitchedTypes -> false
            else -> throw IllegalArgumentException("音色库使用未识别的 RSE 乐器类型：$type")
        }
        fun dynamicValue(text: String): Int = RseNoteCoordinate.dynamicFromString(text.uppercase(Locale.ROOT))
        fun enumValue(text: String, pitched: Boolean, axis: Int): Int = NativeBankMetadata.enumValue(text, pitched, axis)
        fun optionValue(text: String): Int = NativeBankMetadata.optionValue(text)
        fun skipSample(bankId: String, files: Iterable<String>): Boolean = NativeBankMetadata.skipSample(bankId, files)
        fun waveGain(wave: RseWave): Float = NativeBankMetadata.waveGain(wave)
    }
}
