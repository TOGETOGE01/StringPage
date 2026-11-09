@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

import alphaTab.model.Beat
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.SlideInType
import alphaTab.model.SlideOutType
import java.util.IdentityHashMap

/** Written-note graph for the value-level slide interpreter. Display-only bend
 * continuations resolve to their original note and do not introduce extra beats.
 */
class NativeSlideScoreGraph(score: Score) {
    private val nodes = IdentityHashMap<Note,NativeSlideInterpreter.Note>()
    private val source = ArrayList<Note>()
    private val soundingLengths = IdentityHashMap<Note,Length>()
    val hasSlides: Boolean get() = nodes.values.any { it.kind != RseNoteCoordinate.Kind.NonPitched && it.flags and 63 != 0 }

    data class Length(val numerator: Int,val denominator: Int) {
        operator fun plus(other: Length): Length = normalized(numerator.toLong()*other.denominator+other.numerator.toLong()*denominator,
            denominator.toLong()*other.denominator)
        fun times(n: Int,d: Int) = normalized(numerator.toLong()*n,denominator.toLong()*d)
        companion object {
            private fun gcd(a: Long,b: Long): Long = if (b == 0L) kotlin.math.abs(a) else gcd(b,a%b)
            fun normalized(n: Long,d: Long): Length {
                require(n >= 0 && d > 0);val divisor=gcd(n,d)
                require(n/divisor <= Int.MAX_VALUE && d/divisor <= Int.MAX_VALUE) { "Slide duration exceeds the native rational range" }
                return Length((n/divisor).toInt(),(d/divisor).toInt())
            }
        }
    }
    companion object {
        fun flags(note: Note): Int = GpRseProperties.note(note)?.slideFlags ?: run {
            var flags=when(note.slideOutType) {
                SlideOutType.Shift -> 1;SlideOutType.Legato -> 2;SlideOutType.OutDown -> 4;SlideOutType.OutUp -> 8
                SlideOutType.PickSlideDown -> 64;SlideOutType.PickSlideUp -> 128;else -> 0
            }
            flags = flags or when(note.slideInType) { SlideInType.IntoFromBelow -> 16;SlideInType.IntoFromAbove -> 32;else -> 0 }
            flags
        }
        fun length(beat: Beat): Length {
            val value=beat.duration.value
            var length=if(value < 0) Length(-4*value,1) else Length.normalized(4,value.toLong())
            val dots=beat.dots.toInt();require(dots in 0..8)
            if(dots > 0) length=length.times((1 shl (dots+1))-1,1 shl dots)
            if(beat.hasTuplet) length=length.times(beat.tupletDenominator.toInt(),beat.tupletNumerator.toInt())
            return length
        }
    }

    init {
        for(track in score.tracks) for(staff in track.staves) for(bar in staff.bars) for(voice in bar.voices)
            for(beat in voice.beats) for(note in beat.notes) {
                val original=GpRseProperties.original(note)
                if(!nodes.containsKey(original)) {
                    require(source.size < 200000) { "乐谱的演奏音符过多。" }
                    source.add(original);nodes[original]=create(original)
                }
            }
        require(source.size <= 200000)
        for(note in source) nodes[note]!!.apply {
            previous=neighbor(note,false)?.let(nodes::get);next=neighbor(note,true)?.let(nodes::get)
            tieDestination=nextTie(note)?.let(nodes::get)
        }
    }
    private fun nextTie(note: Note): Note? {
        var next=note.tieDestination;var visited=0
        while(next != null && GpRseProperties.original(next) === note) {
            require(++visited <= 200000) { "Invalid display continuation chain" };next=next.tieDestination
        }
        return next?.let(GpRseProperties::original)
    }
    private fun neighbor(note: Note,forward: Boolean): Note? {
        var beat=if(forward) note.beat.nextBeat else note.beat.previousBeat
        var visited=0
        while(beat != null) {
            require(++visited <= 200000) { "Invalid written beat chain" }
            val candidate=beat.notes.firstOrNull { it.string == note.string } ?: return null
            val original=GpRseProperties.original(candidate)
            if(original !== note) return original
            beat=if(forward) beat.nextBeat else beat.previousBeat
        }
        return null
    }
    private fun soundingLength(note: Note): Length {
        soundingLengths[note]?.let { return it }
        val pending=ArrayList<Note>()
        val visited=java.util.Collections.newSetFromMap(IdentityHashMap<Note,Boolean>())
        var current: Note?=note
        while(current != null && !soundingLengths.containsKey(current)) {
            require(visited.add(current) && visited.size <= 200000) { "Invalid slide tie chain" }
            pending.add(current);current=nextTie(current)
        }
        var total=current?.let(soundingLengths::get) ?: Length(0,1)
        for(at in pending.indices.reversed()) { total=length(pending[at].beat)+total;soundingLengths[pending[at]]=total }
        return soundingLengths.getValue(note)
    }
    private fun create(note: Note): NativeSlideInterpreter.Note {
        val beatLength=length(note.beat)
        val total=soundingLength(note)
        val kind=when { note.isPercussion -> RseNoteCoordinate.Kind.NonPitched;note.isStringed -> RseNoteCoordinate.Kind.Stringed
            else -> RseNoteCoordinate.Kind.Pitched }
        return NativeSlideInterpreter.Note(note.calculateRealValue(false,true).toInt(),note.fret.toInt(),flags(note),kind,
            if(kind == RseNoteCoordinate.Kind.Stringed) Note.getStringTuning(note.beat.voice.bar.staff,note.string).toInt() else null,
            beatLength.numerator,beatLength.denominator,total.numerator,total.denominator,
            parameters=GpRseProperties.note(note)?.slideParameters ?: NativeSlideParameters())
    }
    fun get(note: Note): NativeSlideInterpreter.Note? = nodes[GpRseProperties.original(note)]
}
