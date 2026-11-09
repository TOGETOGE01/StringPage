@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.VibratoType
import java.util.IdentityHashMap

/** Original written notes and Core's effective bend fields. Display-only bend
 * continuations reuse their written origin; the source document is never edited.
 */
class NativeBendScoreGraph(score: Score) {
    data class State(val origin: Float,val middle: Float,val destination: Float,val type: Int,
        val valid: Boolean,val stopper: Boolean,val authored: GpBendData) {
        fun bend()=NativeBendInterpreter.Bend(type,origin.toDouble()/50.0,middle.toDouble()/50.0,destination.toDouble()/50.0,
            authored.originOffset/100f,authored.destinationOffset/100f,authored.middleOffset1/100f,authored.middleOffset2/100f)
    }
    companion object {
        /** The original validity repair compares integer semitone buckets. Its
         * cvttss2si64 result is reduced to uint32 before division by 100.
         */
        private fun bucket(value: Float): UInt {
            val scaled=value*2f+.01f
            val integer=if(!scaled.isFinite() || scaled>=9223372036854775808f || scaled< -9223372036854775808f)
                Long.MIN_VALUE else scaled.toLong()
            return integer.toUInt()/100u
        }
        private fun nonnegative(value: Float)=if(value>0f) value else 0f
        fun effective(data: GpBendData,tied: Boolean,previous: State?,stopper: Boolean): State {
            val origin=if(tied && previous!=null) { if(data.enabled) previous.destination else previous.origin } else nonnegative(data.origin)
            val destination=if(!data.enabled && tied && previous!=null) previous.destination
                else if(data.destination== -1f) origin else nonnegative(data.destination)
            var middle=if(data.middle== -1f) (origin+destination)*.5f else data.middle
            if(data.enabled && bucket(middle)<bucket(origin) && bucket(middle)<bucket(destination)) middle=destination
            val type=when {
                !data.enabled -> 0
                origin==middle && middle==destination -> if(tied) 3 else 2
                middle>=origin && destination>=middle -> if(tied || origin==0f) 1 else 6
                origin>=middle && middle>=destination -> if(tied) 4 else 7
                middle>=origin && middle>=destination -> 5
                else -> 0
            }
            return State(origin,middle,destination,type,data.enabled,!data.enabled && stopper,data)
        }
    }
    private val states=IdentityHashMap<Note,State>()
    private val plans=IdentityHashMap<Note,NativeBendInterpreter.Plan?>()
    val hasBends: Boolean
    init {
        for(track in score.tracks) for(staff in track.staves) for(bar in staff.bars) for(voice in bar.voices)
            for(beat in voice.beats) for(model in beat.notes) {
                val note=GpRseProperties.original(model)
                if(states.containsKey(note)) continue
                val source=GpRseProperties.note(note) ?: continue
                require(states.size<200000) { "乐谱的弯音信息过多。" }
                val previous=if(source.tieDestination) previous(note)?.let(states::get) else null
                states[note]=effective(source.bend,source.tieDestination,previous,
                    source.hopoOrigin || source.slideFlags!=0 || note.vibrato!=VibratoType.None)
            }
        hasBends=states.any { (note,state) -> !note.isPercussion && (state.valid || note.hasBend || note.bendOrigin!=null) }
    }
    private fun previous(note: Note): Note? {
        var beat=note.beat.previousBeat;var visited=0
        while(beat!=null) {
            require(++visited<=200000) { "Invalid written bend chain" }
            val candidate=beat.notes.firstOrNull { if(note.isStringed) it.string==note.string else it.calculateRealValue(false,true)==note.calculateRealValue(false,true) } ?: return null
            val original=GpRseProperties.original(candidate)
            if(original!==note) return original
            beat=beat.previousBeat
        }
        return null
    }
    private fun next(note: Note): Note? {
        var target=note.tieDestination;var visited=0
        while(target!=null && GpRseProperties.original(target)===note) {
            require(++visited<=200000) { "Invalid display bend continuation" };target=target.tieDestination
        }
        return target?.let(GpRseProperties::original)
    }
    fun state(note: Note)=states[GpRseProperties.original(note)]
    fun plan(model: Note): NativeBendInterpreter.Plan? {
        val note=GpRseProperties.original(model)
        if(note.isPercussion || !states.containsKey(note)) return null
        if(plans.containsKey(note)) return plans[note]
        val chain=ArrayList<NativeBendInterpreter.Note>()
        val visited=java.util.Collections.newSetFromMap(IdentityHashMap<Note,Boolean>())
        var current: Note?=note;var valid=false
        while(current!=null) {
            require(visited.add(current) && visited.size<=200000) { "Invalid tied bend chain" }
            val state=states[current] ?: return null
            val length=NativeSlideScoreGraph.length(current.beat)
            val ticks=NativeNumericAttribute.truncate(length.numerator.toFloat()/length.denominator.toFloat()*480f)
            valid=valid || state.valid
            chain.add(NativeBendInterpreter.Note(ticks,if(state.valid) state.bend() else null,
                if(state.stopper) state.destination/50f else null))
            current=next(current)
        }
        return (if(valid) NativeBendInterpreter.plan(chain) else null).also { plans[note]=it }
    }
}
