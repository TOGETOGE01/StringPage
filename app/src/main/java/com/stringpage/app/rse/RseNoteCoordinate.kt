package com.stringpage.app.rse

/** GPRSE InstrumentSkill coordinate construction, independently ported from
 * ab800/ac490/ad250. Inputs are the original getters' results. Choosing the
 * instrument skill, Stringed's primary-mode flag, custom builder and missing GP
 * properties belongs to the caller; this module does not infer them from bank
 * names or MIDI programs. Complete score/runtime equivalence is not claimed.
 */
object RseNoteCoordinate {
    enum class Kind { Pitched, NonPitched, Stringed }
    data class Request(val articulation: NativeSampleTree.Articulation, val coordinate: SampleCoordinate)

    data class RawInput(
        val kind: Kind,
        val noteMidi: Int,
        val midiTransposition: Int = 0,
        val beatDynamic: Int = 9,
        val stringIndex: Int = -1,
        // Required explicitly for Stringed; the native flag's business meaning
        // is not yet resolved, so null must not silently pick a branch.
        val primaryStringedMode: Boolean? = null,
        val playingStyle: Int = 0,
        val beatIndex: Int = 0,
        val soundingMidiWithoutHarmonic: Int? = null,
        val tuningOpenMidi: Int? = null,
        val tuningMidiAtDerivedFret: Int? = null,
        val soundingMidiWithHarmonic: Int? = null,
        val pickStroked: Boolean = false,
        val rightHandFingering: Int = 0,
        val pickDirection: Int = 2,
        val slapped: Boolean = false,
        val popped: Boolean = false,
        val harmonic: Boolean = false,
        val harmonicType: Int = 7,
        val harmonicFretEnum: Int = 0,
        val nonPitchedRseSound: List<String>? = null,
        val delegate: Request? = null
    )

    fun build(input: RawInput): Request = when (input.kind) {
        Kind.Pitched -> {
            val base = input.delegate ?: Request(NativeSampleTree.Articulation(0,0,0), SampleCoordinate())
            base.copy(coordinate = base.coordinate.copy(midi = input.noteMidi + input.midiTransposition,
                dynamic = input.beatDynamic))
        }
        Kind.NonPitched -> input.delegate ?: run {
            var a=0;var t=0;var v=0
            input.nonPitchedRseSound?.let { names ->
                require(names.size >= 3) { "Incomplete RSE articulation" }
                unpitchedEnum(names[0],0).takeIf { it != -1 }?.let { a=it }
                unpitchedEnum(names[1],1).takeIf { it != -1 }?.let { t=it }
                unpitchedEnum(names[2],2).takeIf { it != -1 }?.let { v=it }
            }
            Request(NativeSampleTree.Articulation(a,t,v), SampleCoordinate(dynamic=input.beatDynamic))
        }
        Kind.Stringed -> stringed(input)
    }

    private fun stringed(input: RawInput): Request {
        val primary = requireNotNull(input.primaryStringedMode) { "Stringed skill mode must be explicit" }
        if (!primary) return Request(NativeSampleTree.Articulation(7,0,10),
            SampleCoordinate(input.stringIndex,input.noteMidi+input.midiTransposition,input.beatDynamic,0,-1))
        val fret = requireNotNull(input.soundingMidiWithoutHarmonic) - requireNotNull(input.tuningOpenMidi)
        var attack = -1;var technique = 0;var variation = -1
        when (input.playingStyle) {
            0 -> { attack=0;variation=0 }
            1 -> { attack=0;variation=input.beatIndex.inv() and 1 }
            2 -> { attack=1;variation=if(input.stringIndex < 3) 3 else 2 }
            3 -> if(input.stringIndex < 3) { attack=0;technique=4;variation=0 }
                else { attack=1;variation=2 }
            4 -> { attack=if(input.stringIndex < 2) 4 else 3;variation=10 }
        }
        if (input.pickStroked) {
            if (input.rightHandFingering==0) { attack=0;variation=if(input.pickDirection==2) 0 else 1 }
            else attack=1
        }
        if (input.slapped) attack=4 else if(input.popped) attack=3
        val midi: Int
        val dynamic: Int
        if (input.harmonic) {
            attack=2;technique=harmonicTechnique(input.harmonicType);variation=harmonicVariation(input.harmonicFretEnum)
            midi=requireNotNull(input.soundingMidiWithHarmonic);dynamic=9
        } else { midi=requireNotNull(input.tuningMidiAtDerivedFret);dynamic=input.beatDynamic }
        return Request(NativeSampleTree.Articulation(attack,technique,variation),
            SampleCoordinate(input.stringIndex,midi+input.midiTransposition,dynamic,0,fret))
    }

    fun harmonicTechnique(coreType: Int): Int = if(coreType==0) 2 else 0
    fun harmonicVariation(coreFretEnum: Int): Int = when(coreFretEnum) {
        0 -> 8;1,2 -> 6;3,4 -> 5;5,6,7 -> 7;else -> 5
    }
    private val frets = floatArrayOf(12f,7f,19f,5f,24f,4f,9f,16f,3.2f,2.7f,5.8f,9.6f,14.7f,21.7f,2.4f,8.2f,17f,0f)
    /** GPCore fretFromFloat compares float32 values exactly; an unknown returns18. */
    fun harmonicFretFromFloat(value: Float): Int = frets.indexOfFirst { it==value }.let { if(it<0) 18 else it }
    private val dynamics = listOf("NONE","PPPPPPP","PPPPPP","PPPPP","PPPP","PPP","PP","P","MP","MF","F","FF","FFF","FFFF","FFFFF","FFFFFF","FFFFFFF")
    /** Exact GPCore NoteDynamic strings; this differs from preview XML parsing. */
    fun dynamicFromString(value: String): Int = dynamics.indexOf(value).coerceAtLeast(0)
    private val unpitched = listOf(
        listOf("stick","brush","rod","pedal","mallet","hand","thumb","finger4","blow"),
        listOf("hit","scrape"),
        listOf("hit","pedal","return","handhit","roll","choke","edge","mid","bell","open","half","closed","slap","mute","tip","rimshot","sidestick","body","none"))
    fun unpitchedEnum(value: String, axis: Int): Int { require(axis in 0..2);return unpitched[axis].indexOf(value) }

    /** AlphaTab uses Unknown=-2, NoOrDead=-1 and Thumb..Little=0..4. */
    fun rightHandFingeringFromAlphaTabValue(value: Int): Int = if(value in 0..4) value+1 else 0
}
