@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

import alphaTab.model.Note

/** HoPo and Tapping in their observed registry order. Remaining bend/slide and
 * other interpreters are separate; this does not model their combined timing.
 */
object NativeNoteTechnique {
    data class Effect(val cuePoint: Int, val modifiers: List<Float?>, val incompleteSource: Boolean) {
        val amplitude: Float get() = NativeNoteAmplitude.multiplyModifiers(modifiers)
    }

    fun previousFret(note: Note): Int? {
        val original = GpRseProperties.original(note)
        // The native lookup checks the immediately preceding beat. It does
        // not search past a rest or an intervening beat on another string.
        return original.beat.previousBeat?.notes?.firstOrNull { it.string == original.string }?.fret?.toInt()
    }

    fun apply(note: Note?, coordinateGain: Float = 1f, processType: Int = 4): Effect {
        require(coordinateGain.isFinite())
        val modifiers = MutableList<Float?>(NativeNoteAmplitude.MODIFIER_SLOTS) { null }
        modifiers[7] = coordinateGain
        val source = note?.let(GpRseProperties::note)
        val left = source?.leftHandTapped ?: (note?.isLeftHandTapped == true)
        val hopo = source?.hopoDestination ?: (note?.isHammerPullDestination == true)
        val ambiguousTap = source == null && note?.beat?.tap == true && note.beat.notes.length != 1.0
        val tap = source?.tapped ?: (note?.beat?.tap == true && !ambiguousTap)
        var cue = 2
        if (processType == 4) {
            if (left || hopo) {
                cue = 0
                if (left) modifiers[2] = .8f
                else note?.let { current -> previousFret(current)?.let { previous ->
                    modifiers[1] = if (current.fret < previous.toDouble()) .7f else .8f
                } }
            }
            if (tap) cue = 1
        }
        return Effect(cue,modifiers,ambiguousTap)
    }
}
