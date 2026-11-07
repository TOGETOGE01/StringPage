package com.stringpage.app.rse

import java.util.Locale
import kotlin.math.pow

/** Independent AMSoundFont XML-to-tree policy, separate from the raw inspector.
 * The same-pickup map keeps its last Wave; coordinate corrections come from the
 * last authored Wave even if another pickup is chosen. Native sortSamples sorts
 * object addresses, which are not portable. This builder deliberately preserves
 * authored order for tied samples instead of pretending to reproduce heap order.
 */
class NativeBankMetadata(val bank: RseSoundBank, private val engine: Engine = Engine.AmPreview) {
    enum class Engine { AmPreview, RseRuntime }
    data class Sample(val sourceIndex: Int, val articulation: NativeSampleTree.Articulation,
        val coordinate: SampleCoordinate, val waves: List<RseWave>,
        val pitchCorrection: Float, val amplitudeCorrection: Float)

    val pitched = if (engine == Engine.RseRuntime) RseBankMetadata.isPitched(bank.type) else isPitched(bank.type)
    val samples: List<Sample>
    val root: NativeSampleTree.Node
    val tree: NativeSampleTree

    private class BuildingNode(val id: Int) {
        val samples = ArrayList<Int>()
        val children = ArrayList<BuildingNode>()
        fun add(id: Int) = BuildingNode(id).also { children.add(it) }
        fun child(id: Int) = children.firstOrNull { it.id == id }
        fun freeze(): NativeSampleTree.Node = NativeSampleTree.Node(id, samples, children.map { it.freeze() })
    }

    init {
        val building = skeleton(pitched)
        val records = ArrayList<Sample>()
        for ((index, source) in bank.samples.withIndex()) {
            // parse() visits child Layers; Samples directly on SamplesTree are
            // parsed into its raw Layer but are not attached to the sample tree.
            if (source.nodePath.isEmpty()) continue
            if (skipSample(bank.id, source.waves.map { it.file })) continue
            require(source.nodePath.size <= 3) { "Unsupported sample tree depth" }
            var node: BuildingNode? = building
            val ids = IntArray(3) { -1 }
            for ((depth, text) in source.nodePath.withIndex()) {
                ids[depth] = enumValue(text, pitched, depth)
                node = node?.child(ids[depth])
            }
            val destination = node ?: continue
            val string = if (source.string == -1) -1 else source.string - 1
            var midi = source.midi
            var dynamic = if (engine == Engine.RseRuntime) RseBankMetadata.dynamicValue(source.dynamic) else dynamicValue(source.dynamic)
            var fret = -1
            if (pitched && bank.tuning.isNotEmpty() && (engine == Engine.AmPreview || ids[0] != 2)) {
                require(string in bank.tuning.indices) { "Sample string is outside bank tuning" }
                fret = midi - bank.tuning[string]
            }
            val waves = LinkedHashMap<String, RseWave>()
            source.waves.forEach { waves[it.pickup] = it }
            if (pitched && ids[0] == 2 && waves.isNotEmpty()) {
                naturalIntervals[ids[2]]?.let { interval ->
                    require(string in bank.tuning.indices) { "Harmonic sample requires a valid tuning" }
                    midi = bank.tuning[string] + interval
                }
                dynamic = if (engine == Engine.RseRuntime) 10 else 7
            }
            val last = source.waves.lastOrNull() ?: continue
            val rawPitch = last.repitch.toFloat().let { if (bank.id.endsWith('+')) -it else it }
            val pitch = rawPitch * 0.01f
            val amplitude = last.ratio.toFloat()
            require(pitch.isFinite() && amplitude.isFinite()) { "Sample correction is outside supported float range" }
            destination.samples.add(records.size)
            records.add(Sample(index, NativeSampleTree.Articulation(ids[0], ids[1], ids[2]),
                SampleCoordinate(string, midi, dynamic, optionValue(source.option), fret),
                waves.toSortedMap().values.toList(), pitch, amplitude))
        }
        samples = records.toList()
        root = building.freeze()
        tree = NativeSampleTree(root, samples.map { it.coordinate }, pitched)
    }

    companion object {
        private val unpitchedTypes = setOf("agogo", "bellTree", "bongo", "cabasa", "castanets", "china",
            "claves", "conga", "cowbell", "crash", "cuica", "cymbal", "drum", "grancassa", "guiro",
            "handClap", "hiHat", "idiophone", "jingleBell", "kickDrum", "maraca", "melodicTom",
            "membraphone", "piatti", "reverseCymbal", "ride", "shaker", "snare", "splash", "surdo",
            "synthDrum", "taikoDrum", "tambourine", "timbale", "tinkelBell", "tom", "triangle",
            "unpitched", "vibraslap", "whistle", "woodblock")
        private val pitchedEnums = listOf(
            listOf("pick", "finger", "harmonic", "pop", "slap", "key", "blow", "bow", "mallet"),
            listOf("sustain", "pizzicato", "natural", "hit"),
            listOf("down", "up", "index", "thumb", "middle", "5th", "7th", "9th", "12th", "4:5", "variationa", "variationb", "novibrato", "hit"))
        private val unpitchedEnums = listOf(
            listOf("stick", "brush", "rod", "pedal", "mallet", "hand", "thumb", "finger4", "blow"),
            listOf("hit", "scrape"),
            listOf("hit", "pedal", "return", "handhit", "roll", "choke", "edge", "mid", "bell", "open", "half", "closed", "slap", "mute", "tip", "rimshot", "sidestick", "body", "none"))
        private val dynamics = listOf("NONE", "PPPPPPP", "PPPPPP", "PPPPP", "PPPP", "PPP", "PP", "P", "MP", "MF", "F", "FF", "FFF", "FFFF", "FFFFF", "FFFFFF", "FFFFFFF")
        private val options = listOf("none", "slow", "medium", "fast", "xfast", "shs", "shf", "mds", "mdf", "lns", "lnf")
        private val naturalIntervals = mapOf(5 to 24, 6 to 19, 7 to 28, 8 to 12)
        private val excludedFiles = mapOf(
            "Eastern-APiano" to setOf("EasternGrandC7-Key-Sus-none-07-none-081-none.ogg"),
            "Concerto-Nylon" to setOf("EspnNyl-Fng-Sus-Thumb-01-6E40-040-none-none.ogg"),
            "Rick12-Guitar" to setOf("Ric12-Pck-Sus-Down-01-6E40-040-BR-none.ogg", "Ric12-Pck-Sus-Down-01-6E40-040-NK-none.ogg"))

        fun isPitched(type: String): Boolean = type !in unpitchedTypes
        fun enumValue(text: String, pitched: Boolean, axis: Int): Int {
            require(axis in 0..2)
            return (if (pitched) pitchedEnums else unpitchedEnums)[axis].indexOf(text)
        }
        fun dynamicValue(text: String): Int {
            val upper = text.uppercase(Locale.ROOT)
            return dynamics.indexOfFirst { upper < it }.let { if (it < 0) 0 else it }
        }
        fun optionValue(text: String): Int = options.indexOf(text).coerceAtLeast(0)
        fun skipSample(bankId: String, files: Iterable<String>): Boolean =
            excludedFiles[bankId]?.let { excluded -> files.any { it in excluded } } ?: false
        fun waveGain(wave: RseWave): Float {
            val exponent = wave.gain.toFloat() / 20f
            return 10.0.pow(exponent.toDouble()).toFloat()
        }

        private fun skeleton(pitched: Boolean): BuildingNode {
            val root = BuildingNode(if (pitched) 0 else 1)
            fun branch(attack: Int, technique: Int, variations: List<Int>) {
                val a = root.child(attack) ?: root.add(attack)
                val t = a.child(technique) ?: a.add(technique)
                variations.forEach { t.add(it) }
            }
            if (pitched) {
                branch(0, 0, listOf(0, 1)); branch(1, 0, listOf(2, 3, 4)); branch(1, 1, emptyList())
                branch(2, 2, listOf(5, 6, 7, 8, 9)); branch(3, 0, listOf(10, 11)); branch(4, 0, listOf(10, 11))
                branch(5, 0, emptyList()); branch(6, 0, listOf(12)); branch(7, 0, listOf(12)); branch(8, 3, listOf(13))
            } else {
                branch(0, 0, listOf(0, 2, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 18)); branch(0, 1, listOf(2))
                branch(1, 0, listOf(0, 5, 6, 7, 8, 9, 10, 11, 13)); branch(2, 0, listOf(0, 5, 6, 7, 8, 9, 10, 11, 13))
                branch(3, 0, listOf(0, 1)); branch(4, 0, listOf(0)); branch(5, 0, listOf(0, 12, 13, 2, 3, 4))
                branch(6, 0, listOf(17)); branch(7, 0, listOf(17)); branch(8, 0, listOf(0))
            }
            return root
        }
    }
}
