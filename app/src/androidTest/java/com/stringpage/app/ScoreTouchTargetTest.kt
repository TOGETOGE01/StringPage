@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.stringpage.app

import alphaTab.model.Beat
import alphaTab.model.Note
import alphaTab.rendering.utils.BarBounds
import alphaTab.rendering.utils.BeatBounds
import alphaTab.rendering.utils.Bounds
import alphaTab.rendering.utils.BoundsLookup
import alphaTab.rendering.utils.MasterBarBounds
import alphaTab.rendering.utils.NoteBounds
import alphaTab.rendering.utils.StaffSystemBounds
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScoreTouchTargetTest {
    @Test fun aLedgerLineNoteBeatsTheStaffRectangleAndTabResolvesToTheSameNote() {
        val beat = Beat()
        val lower = Note().also(beat::addNote)
        val upper = Note().also(beat::addNote)
        val lookup = BoundsLookup()
        val system = StaffSystemBounds().apply { realBounds = Bounds(0.0, 0.0, 240.0, 140.0) }
        lookup.addStaffSystem(system)
        val measure = MasterBarBounds().apply { realBounds = Bounds(0.0, 0.0, 240.0, 140.0) }
        system.addBar(measure)
        val score = BarBounds().also(measure::addBar)
        val tab = BarBounds().also(measure::addBar)
        fun addGlyphs(staff: BarBounds, y: Double, upperY: Double, lowerY: Double) {
            val bounds = BeatBounds().apply {
                this.beat = beat
                onNotesX = 40.0
                realBounds = Bounds(30.0, y, 40.0, 30.0)
                visualBounds = realBounds
            }
            staff.addBeat(bounds)
            for ((note, top) in listOf(upper to upperY, lower to lowerY)) bounds.addNote(NoteBounds().apply {
                this.note = note
                noteHeadBounds = Bounds(40.0, top, 8.0, 6.0)
            })
        }
        addGlyphs(score, 20.0, 44.0, 62.0)
        addGlyphs(tab, 95.0, 103.0, 116.0)
        assertSame(lower, ScoreTouchTarget.find(lookup, 43.0, 66.0)?.note)
        assertSame(upper, ScoreTouchTarget.find(lookup, 41.0, 47.0)?.note)
        assertSame(lower, ScoreTouchTarget.find(lookup, 43.0, 120.0)?.note)
        assertNull(ScoreTouchTarget.find(lookup, 175.0, 65.0))
    }
}
