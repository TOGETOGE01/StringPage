package com.stringpage.app.rse

/** Value-level SlideInterpreter predicate and generation kernel. Timing fields
 * are native Core getter values; ticks use the 480-quarter clock. Rendering,
 * score-model adaptation and the rest of the interpreter registry are separate.
 */
object NativeSlideInterpreter {
    const val SHIFT = 1; const val LEGATO = 2
    const val OUT_DOWN = 4; const val OUT_UP = 8
    const val IN_BELOW = 16; const val IN_ABOVE = 32

    class Note(
        val midi: Int, val fret: Int, val flags: Int,
        val kind: RseNoteCoordinate.Kind = RseNoteCoordinate.Kind.Stringed,
        val openMidi: Int? = null,
        val beatNumerator: Int = 1, val beatDenominator: Int = 1,
        val soundingNumerator: Int = 1, val soundingDenominator: Int = 1,
        val relativeDuration: Float = 1f, val relativeOffset: Int = 0
    ) {
        var previous: Note? = null; var next: Note? = null; var tieDestination: Note? = null
        fun has(flag: Int) = flags and flag != 0
        init { require(beatDenominator > 0 && soundingDenominator > 0 && relativeDuration.isFinite()) }
    }
    data class Segment(val midi: Int, val start: Int, val end: Int,
        val state: NativeFadePolicy.State, val engine: Int = 0)
    data class Plan(val applies: Boolean, val root: Segment, val children: List<Segment>)

    /** Core checkSlideErrors. Open-fret errors apply independently of the
     * next-note errors; combinations can disable more than one phase. */
    fun errors(note: Note): Int {
        if (note.kind == RseNoteCoordinate.Kind.NonPitched || note.flags == 0) return 0
        var result = 0
        val fret = if (note.kind == RseNoteCoordinate.Kind.Pitched) note.midi else note.fret
        if (fret == 0) {
            if (note.has(OUT_DOWN)) result = result or 8
            if (note.has(IN_BELOW)) result = result or 4
        }
        if (note.has(SHIFT or LEGATO)) {
            val next = note.next
            if (next == null) result = result or 1
            else if ((if (next.kind == RseNoteCoordinate.Kind.Pitched) next.midi else next.fret) == fret) result = result or 2
        }
        return result
    }

    fun applies(note: Note): Boolean {
        note.previous?.let { previous ->
            if (previous.has(LEGATO or SHIFT) && errors(previous) and 2 == 0) return true
        }
        var current: Note? = note
        val visited = HashSet<Note>()
        while (current != null) {
            require(visited.add(current) && visited.size <= 200000) { "Invalid slide tie chain" }
            if (current.has(IN_BELOW or IN_ABOVE) && errors(current) and 4 == 0) return true
            if (current.has(OUT_DOWN or OUT_UP or LEGATO or SHIFT) && errors(current) and 11 == 0) return true
            current = current.tieDestination
        }
        return false
    }

    private fun permittedOffset(note: Note, requested: Int): Int =
        if (note.kind == RseNoteCoordinate.Kind.Stringed) maxOf(requested,requireNotNull(note.openMidi)-note.midi) else requested

    fun plan(note: Note, start: Int, end: Int, initial: NativeFadePolicy.State = NativeFadePolicy.State(),
        automationMidi: Int = note.midi, initialEngine: Int = 2): Plan {
        var root = Segment(automationMidi,start,end,initial,initialEngine)
        if (!applies(note)) return Plan(false,root,emptyList())
        val children = ArrayList<Segment>()
        note.previous?.let { previous ->
            if (previous.has(LEGATO)) root = root.copy(state=NativeFadePolicy.legatoDestination(root.state,previous.midi,root.midi)!!,engine=0)
            else if (previous.has(SHIFT)) root = root.copy(state=NativeFadePolicy.shift(root.state,true,false)!!,engine=2)
        }
        if (note.has(IN_BELOW or IN_ABOVE)) {
            val distance = permittedOffset(note,if (note.has(IN_BELOW)) -3 else 3)
            val firstTick = (240f*.8f).toInt()
            if (firstTick != 240) {
                val times = NativeSlideTiming.ticks(distance,0,firstTick,240,start-240,0)
                val midis = NativeSlideTiming.midis(distance,0,note.midi)
                val group = midis.mapIndexed { at,midi -> Segment(midi,times[at],times[at+1],root.state) }.toMutableList()
                group.add(root)
                if (group.size > 1) {
                    group[0] = group[0].copy(state=NativeFadePolicy.shift(group[0].state,false,true)!!)
                    for (at in 1 until group.size) {
                        val segment = group[at]
                        group[at] = segment.copy(state=NativeFadePolicy.generatedSegment(segment.state,true,at != group.lastIndex,
                            group[at-1].midi,segment.midi)!!)
                    }
                }
                children.addAll(group.dropLast(1)); root = group.last().copy(engine=0)
            }
        }
        var last = note
        val visited = HashSet<Note>()
        while (last.tieDestination != null) {
            require(visited.add(last) && visited.size <= 200000) { "Invalid slide tie chain" }
            last = last.tieDestination!!
        }
        if (last.has(OUT_DOWN or OUT_UP or LEGATO or SHIFT) && errors(last) and 11 == 0) {
            val first: Int; val requested: Int
            when {
                last.has(OUT_DOWN) -> { first = -1; requested = -4 }
                last.has(OUT_UP) -> { first = 1; requested = 4 }
                else -> { requested = last.next?.let { it.midi-last.midi } ?: 0; first = if (requested > 0) 1 else -1 }
            }
            val distance = permittedOffset(last,requested)
            val direction = if (requested == 0 && !last.has(OUT_DOWN or OUT_UP)) 0 else first
            if (direction != 0) {
                val ratio = last.beatNumerator.toFloat()/last.beatDenominator.toFloat()
                val beatFrames = last.relativeDuration*NativeSlideTiming.QUARTER_TICKS.toFloat()*ratio
                val from = (.8f*beatFrames).toInt()+last.relativeOffset
                val to = beatFrames.toInt()+last.relativeOffset
                val sounding = (note.soundingNumerator.toFloat()/note.soundingDenominator.toFloat()*NativeSlideTiming.QUARTER_TICKS.toFloat()).toInt()
                val beat = (ratio*NativeSlideTiming.QUARTER_TICKS.toFloat()).toInt()
                val offset = sounding-beat+start
                val times = NativeSlideTiming.ticks(first,distance,from,to,offset,if (note.next == null) 0 else NativeSlideTiming.AUTO_BRUSH_REDUCED_TICKS)
                val midis = NativeSlideTiming.midis(first,distance,root.midi)
                val group = mutableListOf(root)
                group.addAll(midis.mapIndexed { at,midi -> Segment(midi,times[at],times[at+1],root.state) })
                if (group.size > 1) {
                    group[0] = group[0].copy(end=group[1].start,state=NativeFadePolicy.shift(group[0].state,false,true)!!)
                    for (at in 1 until group.size) {
                        val segment = group[at]
                        group[at] = segment.copy(state=NativeFadePolicy.generatedSegment(segment.state,true,true,group[at-1].midi,segment.midi)!!)
                    }
                }
                root = group[0].copy(engine=0);children.addAll(group.drop(1))
            }
        }
        return Plan(true,root,children)
    }
}
