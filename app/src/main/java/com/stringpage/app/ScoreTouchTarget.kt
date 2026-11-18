@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package com.stringpage.app

import alphaTab.model.Beat
import alphaTab.model.Note
import alphaTab.rendering.utils.Bounds
import alphaTab.rendering.utils.BoundsLookup
import kotlin.math.abs

internal data class ScoreTouchTarget(val beat: Beat, val note: Note?) {
    companion object {
        fun find(lookup: BoundsLookup, x: Double, y: Double): ScoreTouchTarget? {
            // A chord may have separate glyph bounds on the standard and tab staves.
            // Check nearby rendered glyphs before falling back to a beat or rest.
            var hit: Note? = null
            var hitCost = Double.POSITIVE_INFINITY
            var rest: Beat? = null
            var restCost = Double.POSITIVE_INFINITY
            for (system in lookup.staffSystems) {
                if (system.realBounds.verticalGap(y) > 16) continue
                for (measure in system.bars) {
                    if (measure.realBounds.horizontalGap(x) > 10) continue
                    for (bar in measure.bars) {
                        for (beat in bar.beats) {
                            // Ledger-line note heads can sit outside the beat's staff rectangle.
                            beat.notes?.let { notes ->
                                for (glyph in notes) {
                                    val box = glyph.noteHeadBounds
                                    val dx = box.horizontalGap(x)
                                    val dy = box.verticalGap(y)
                                    if (dx > 10 || dy > 8) continue
                                    val cost = dx * dx + dy * dy + abs(y - box.y - box.h / 2) * 0.1
                                    if (cost < hitCost) {
                                        hit = glyph.note
                                        hitCost = cost
                                    }
                                }
                            }
                            val box = beat.realBounds
                            if (box.horizontalGap(x) > 0 || box.verticalGap(y) > 0) continue
                            val cost = beat.visualBounds.verticalGap(y) * 2 + abs(x - beat.onNotesX)
                            if (cost < restCost) {
                                rest = beat.beat
                                restCost = cost
                            }
                        }
                    }
                }
            }
            return hit?.let { ScoreTouchTarget(it.beat, it) } ?: rest?.let { ScoreTouchTarget(it, null) }
        }

        private fun Bounds.horizontalGap(x: Double) = maxOf(this.x - x, x - this.x - w, 0.0)
        private fun Bounds.verticalGap(y: Double) = maxOf(this.y - y, y - this.y - h, 0.0)
    }
}
