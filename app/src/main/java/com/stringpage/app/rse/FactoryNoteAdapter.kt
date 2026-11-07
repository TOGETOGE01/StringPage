@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app.rse

import alphaTab.model.HarmonicType
import alphaTab.model.Note
import alphaTab.model.PickStroke

/** Bridges public score getters into the recovered InstrumentSkill rules.
 * Native StringedSkill's default constructor enables primary mode. Later custom
 * builders, ambiguous generated notes and omitted percussion definitions remain
 * disclosed limitations rather than guessed bank-name/program heuristics. */
object FactoryNoteAdapter {
    data class Adapted(val request: RseNoteCoordinate.Request, val incompleteSource: Boolean)

    fun adapt(event: ScorePerformance.NoteEvent, bankPitched: Boolean): Adapted {
        event.slideVoice?.let { slide ->
            val base=adapt(event.copy(midi=slide.sourceMidi+event.channelTransposition,
                sourceMatch=ScorePerformance.SourceMatch.ExactPitch,slideVoice=null),bankPitched)
            return base.copy(request=base.request.copy(coordinate=base.request.coordinate.copy(midi=event.midi,
                fret=maxOf(0,base.request.coordinate.fret))))
        }
        val note = event.sourceNote
        val staff = note?.beat?.voice?.bar?.staff
        val score = staff?.track?.score
        require(score == null || GpRseProperties.failure(score) == null) {
            "无法读取原谱的原厂音源设置，请重新保存 GP 文件后再试。"
        }
        val properties = score?.let(GpRseProperties::get)?.track(event.track)
        val dynamic = RseNoteCoordinate.dynamicFromString(note?.beat?.dynamics?.name ?: "MF")
        val kind = when {
            note?.isPercussion == true || note == null && !bankPitched -> RseNoteCoordinate.Kind.NonPitched
            note?.isStringed == true -> RseNoteCoordinate.Kind.Stringed
            else -> RseNoteCoordinate.Kind.Pitched
        }
        if (kind == RseNoteCoordinate.Kind.NonPitched) {
            val sourceArticulations = properties?.articulations.orEmpty()
            // Modern GPIF InstrumentArticulation is a flat index, exactly like
            // Track.percussionArticulations. Older GP6 Element/Variation paths
            // can only be resolved here when the output MIDI identifies one sound.
            val flat = note?.percussionArticulation?.toInt()?.let { sourceArticulations.getOrNull(it) }
                ?.takeIf { it.outputMidiNumber == event.midi }
            val candidates = (flat?.let(::listOf) ?: sourceArticulations.filter { it.outputMidiNumber == event.midi })
                .map { it.outputRseSound.take(3) }.filter { it.size == 3 }.distinct()
            val articulation = candidates.singleOrNull()
            return Adapted(RseNoteCoordinate.build(RseNoteCoordinate.RawInput(kind, event.midi,
                beatDynamic = dynamic, nonPitchedRseSound = articulation)), note == null || articulation == null)
        }
        if (kind == RseNoteCoordinate.Kind.Pitched) return Adapted(RseNoteCoordinate.build(
            RseNoteCoordinate.RawInput(kind, event.midi - event.channelTransposition,
                midiTransposition = event.channelTransposition, beatDynamic = dynamic)), note == null)

        requireNotNull(note); requireNotNull(staff)
        val sourceMidi = note.calculateRealValue(false, true).toInt()
        val generatedDelta = if (event.sourceMatch == ScorePerformance.SourceMatch.GeneratedPitch)
            event.midi - event.channelTransposition - sourceMidi else 0
        val plainMidi = note.calculateRealValue(false, false).toInt() + generatedDelta
        val input = RseNoteCoordinate.RawInput(kind, event.midi - event.channelTransposition,
            midiTransposition = event.channelTransposition, beatDynamic = dynamic,
            stringIndex = note.string.toInt() - 1, primaryStringedMode = true,
            playingStyle = GpRseProperties.nativePlayingStyleCore(properties?.rawPlayingStyle),
            beatIndex = note.beat.index.toInt(), soundingMidiWithoutHarmonic = plainMidi,
            tuningOpenMidi = Note.getStringTuning(staff, note.string).toInt(),
            tuningMidiAtDerivedFret = plainMidi, soundingMidiWithHarmonic = sourceMidi + generatedDelta,
            pickStroked = note.beat.pickStroke != PickStroke.None,
            rightHandFingering = RseNoteCoordinate.rightHandFingeringFromAlphaTabValue(note.rightHandFinger.value.toInt()),
            pickDirection = note.beat.pickStroke.value.toInt(), slapped = note.beat.slap, popped = note.beat.pop,
            harmonic = note.isHarmonic, harmonicType = if (note.harmonicType == HarmonicType.Natural) 0 else 1,
            harmonicFretEnum = RseNoteCoordinate.harmonicFretFromFloat(note.harmonicValue.toFloat()))
        return Adapted(RseNoteCoordinate.build(input), event.sourceMatch != ScorePerformance.SourceMatch.ExactPitch)
    }
}
