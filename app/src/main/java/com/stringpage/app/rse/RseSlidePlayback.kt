@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

import alphaTab.Settings
import alphaTab.model.JsonConverter
import alphaTab.model.Score
import alphaTab.model.SlideInType
import alphaTab.model.SlideOutType
import alphaTab.model.BendType

/** Compile a private score snapshot without replaced MIDI bends, then expand
 * recovered RSE slide voices and per-note bend functors. Other MIDI-derived timing/pitch behavior remains
 * an adapter; this is not the complete native tick scheduler.
 */
object RseSlidePlayback {
    fun compile(score: Score,settings: Settings = Settings(),cancelled: () -> Boolean = { false }): ScorePerformance {
        fun checkActive() { check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消准备。" } }
        checkActive()
        val hasBends=NativeBendScoreGraph(score).hasBends
        if (!NativeSlideScoreGraph(score).hasSlides && !hasBends) return ScorePerformance.compile(score,settings,cancelled)
        val original=ScorePerformance.compile(score,settings,cancelled)
        // Existing MIDI emission does not identify an authored string for an
        // ambiguous unison. Keep that track's existing pitch path until the
        // emission identity is recovered, rather than silently flattening it.
        val ambiguousTracks=original.notes.filter { it.sourceMatch==ScorePerformance.SourceMatch.Ambiguous }.map { it.track }.toSet()
        checkActive()
        val json=checkNotNull(JsonConverter.scoreToJsObject(score))
        // alphaTab's generated JSON keys are lowercase, including compound names.
        json.delete("backingtrack")
        val snapshot=JsonConverter.jsObjectToScore(json,settings)
        GpRseProperties.transfer(score,snapshot)
        val graph=NativeSlideScoreGraph(snapshot)
        val bends=NativeBendScoreGraph(snapshot)
        for((trackIndex,track) in snapshot.tracks.withIndex()) for(staff in track.staves) for(bar in staff.bars) for(voice in bar.voices)
            for(beat in voice.beats) for(note in beat.notes) {
                checkActive()
                if(hasBends && trackIndex !in ambiguousTracks && !note.isPercussion && GpRseProperties.note(note)!=null) {
                    note.bendType=BendType.None;note.bendPoints=null;note.maxBendPoint=null
                    note.bendOrigin=null;note.isContinuedBend=false
                }
                if(graph.get(note)?.let { it.kind != RseNoteCoordinate.Kind.NonPitched && NativeSlideInterpreter.applies(it) } == true) {
                    val flags=NativeSlideScoreGraph.flags(note)
                    if(flags and 15 != 0) note.slideOutType=SlideOutType.None
                    if(flags and 48 != 0) note.slideInType=SlideInType.None
                    note.slideOrigin=null;note.slideTarget=null
                }
            }
        val base=ScorePerformance.compile(snapshot,settings,cancelled)
        val notes=ArrayList<ScorePerformance.NoteEvent>()
        var partial=0
        for(rawEvent in base.notes) {
            checkActive()
            val event=if(hasBends && rawEvent.track !in ambiguousTracks) rawEvent.copy(nativeBend=rawEvent.sourceNote?.let(bends::plan)) else rawEvent
            val source=event.sourceNote;val node=source?.let(graph::get)
            if(source?.hasBend==true && event.nativeBend==null) partial++
            if(node == null || node.kind == RseNoteCoordinate.Kind.NonPitched || !NativeSlideInterpreter.applies(node)) {
                val accent=source?.let { GpRseProperties.note(it)?.accentFlags ?: if(it.isStaccato) 1 else 0 } ?: 0
                val end=if(event.nativeBend!=null && node!=null) NativeStaccato.endTick(accent,
                    NativeNumericAttribute.truncate(node.soundingNumerator.toFloat()/node.soundingDenominator.toFloat()*480f),
                    (event.startTick/2.0).toInt()) else null
                notes.add(if(end==null) event else event.copy(endTick=end*2.0,endMs=base.tickToMs(end*2.0)));continue
            }
            val technique=NativeNoteTechnique.apply(source)
            val initial=NativeFadePolicy.State(startingCuePoint=technique.cuePoint)
            val plan=NativeSlideInterpreter.plan(node,(event.startTick/2.0).toInt(),(event.endTick/2.0).toInt(),initial)
            if(source.isHarmonic || source.hasBend || node.flags and 63.inv() != 0) partial++
            fun append(segment: NativeSlideInterpreter.Segment,generated: Boolean) {
                val from=segment.start*2.0;val to=segment.end*2.0
                if(to <= from) { partial++;return }
                notes.add(event.copy(midi=segment.midi+event.channelTransposition,startTick=from,endTick=to,
                    startMs=base.tickToMs(from),endMs=base.tickToMs(to),
                    sourceMatch=if(generated) ScorePerformance.SourceMatch.GeneratedPitch else event.sourceMatch,
                    slideVoice=ScorePerformance.SlideVoice(segment.state,node.midi,generated)))
            }
            val accent=GpRseProperties.note(source)?.accentFlags ?: if(source.isStaccato) 1 else 0
            val duration=(node.soundingNumerator.toFloat()/node.soundingDenominator.toFloat()*NativeSlideTiming.QUARTER_TICKS.toFloat()).toInt()
            val staccato=NativeStaccato.endTick(accent,duration,plan.root.start)
            append(if(staccato == null) plan.root else plan.root.copy(end=staccato),false)
            plan.children.forEach { append(it,true) }
            require(notes.size <= 200000) { "滑音生成的演奏音符过多。" }
        }
        val shift=maxOf(0.0,-(notes.minOfOrNull { it.startTick } ?: 0.0))
        val initialBpm=base.tempos.first().bpm
        val leadMs=shift*60000.0/(960.0*initialBpm)
        val syncOffset=(base.tickShift+shift-original.tickShift)*60000.0/(960.0*initialBpm)
        val firstCue=(base.tickShift+shift)*60000.0/(960.0*initialBpm)
        val expanded=notes.sortedWith(compareBy<ScorePerformance.NoteEvent> { it.startTick }.thenBy { it.id })
            .mapIndexed { index,event -> event.copy(id=index,startTick=event.startTick+shift,endTick=event.endTick+shift,
                startMs=event.startMs+leadMs,endMs=event.endMs+leadMs) }
        val end=maxOf(base.endTick,notes.maxOfOrNull { it.endTick } ?: 0.0)+shift
        val tempos=base.tempos.map { it.copy(tick=it.tick+shift,timeMs=it.timeMs+leadMs) }.toMutableList()
        if(shift > 0) tempos.add(0,ScorePerformance.TempoPoint(0.0,0.0,initialBpm))
        return ScorePerformance(expanded,base.pitches.map { it.copy(tick=it.tick+shift,timeMs=it.timeMs+leadMs) },
            base.controls.map { if(shift > 0 && it.tick <= base.tickShift) it.copy(tick=0.0,timeMs=0.0)
                else it.copy(tick=it.tick+shift,timeMs=it.timeMs+leadMs) },
            base.programs.map { if(shift > 0 && it.tick <= base.tickShift) it.copy(tick=0.0,timeMs=0.0)
                else it.copy(tick=it.tick+shift,timeMs=it.timeMs+leadMs) },tempos,
            base.bars.map { it.copy(startTick=it.startTick+shift,endTick=it.endTick+shift) },end,
            base.tickToMs(end-shift)+leadMs,base.tickShift+shift,syncOffset,firstCue,partial)
    }
}
