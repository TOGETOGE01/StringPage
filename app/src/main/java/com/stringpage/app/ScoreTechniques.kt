@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.model.BendPoint
import alphaTab.model.BendType
import alphaTab.model.GraceType
import alphaTab.model.HarmonicType
import alphaTab.model.Note
import alphaTab.model.SlideInType
import alphaTab.model.SlideOutType
import alphaTab.model.VibratoType
import com.stringpage.app.BasicScoreEditor.Technique
import com.stringpage.app.rse.GpRseProperties
import com.stringpage.app.rse.NativeSlideScoreGraph

internal object ScoreTechniques {
    fun isApplied(note: Note, technique: Technique): Boolean = when (technique) {
        Technique.HalfBend -> hasBend(note, 2.0)
        Technique.WholeBend -> hasBend(note, 4.0)
        Technique.PalmMute -> note.isPalmMute
        Technique.LetRing -> note.isLetRing
        Technique.Ghost -> note.isGhost
        Technique.Dead -> note.isDead
        Technique.Staccato -> note.isStaccato
        Technique.Vibrato -> note.vibrato == VibratoType.Slight
        Technique.WideVibrato -> note.vibrato == VibratoType.Wide
        Technique.Tie -> note.isTieOrigin
        Technique.HammerPull -> note.isHammerPullOrigin
        Technique.ShiftSlide -> note.slideOutType == SlideOutType.Shift
        Technique.LegatoSlide -> note.slideOutType == SlideOutType.Legato
        Technique.SlideFromBelow -> note.slideInType == SlideInType.IntoFromBelow
        Technique.SlideFromAbove -> note.slideInType == SlideInType.IntoFromAbove
        Technique.SlideOutDown -> note.slideOutType == SlideOutType.OutDown
        Technique.SlideOutUp -> note.slideOutType == SlideOutType.OutUp
        Technique.NaturalHarmonic -> note.harmonicType == HarmonicType.Natural
        Technique.OctaveHarmonic -> note.harmonicType == HarmonicType.Artificial && note.harmonicValue == 12.0
        Technique.LeftHandTap -> note.isLeftHandTapped
    }

    fun toggle(note: Note, technique: Technique) {
        val enable = !isApplied(note, technique)
        when (technique) {
            Technique.HalfBend -> setBend(note, 2.0.takeIf { enable })
            Technique.WholeBend -> setBend(note, 4.0.takeIf { enable })
            Technique.PalmMute -> note.isPalmMute = enable
            Technique.LetRing -> note.isLetRing = enable
            Technique.Ghost -> note.isGhost = enable
            Technique.Dead -> {
                require(!enable || connectedNotes(note).isEmpty()) { "请先移除音符的连接奏法。" }
                note.isDead = enable
            }
            Technique.Staccato -> {
                note.isStaccato = enable
                GpRseProperties.updateEditedConnection(note) {
                    it.copy(accentFlags = if (enable) (it.accentFlags ?: 0) or 1 else it.accentFlags?.and(1.inv()))
                }
            }
            Technique.Vibrato -> note.vibrato = if (enable) VibratoType.Slight else VibratoType.None
            Technique.WideVibrato -> note.vibrato = if (enable) VibratoType.Wide else VibratoType.None
            Technique.Tie -> if (enable) connectTie(note) else clearTie(note)
            Technique.HammerPull -> if (enable) connectHammerPull(note) else clearHammerPull(note)
            Technique.ShiftSlide -> setSlideOut(note, if (enable) SlideOutType.Shift else SlideOutType.None)
            Technique.LegatoSlide -> setSlideOut(note, if (enable) SlideOutType.Legato else SlideOutType.None)
            Technique.SlideOutUp -> setSlideOut(note, if (enable) SlideOutType.OutUp else SlideOutType.None)
            Technique.SlideOutDown -> setSlideOut(note, if (enable) SlideOutType.OutDown else SlideOutType.None)
            Technique.SlideFromBelow -> setSlideIn(note, if (enable) SlideInType.IntoFromBelow else SlideInType.None)
            Technique.SlideFromAbove -> setSlideIn(note, if (enable) SlideInType.IntoFromAbove else SlideInType.None)
            Technique.NaturalHarmonic -> setHarmonic(note, if (enable) HarmonicType.Natural else HarmonicType.None)
            Technique.OctaveHarmonic -> setHarmonic(note, if (enable) HarmonicType.Artificial else HarmonicType.None)
            Technique.LeftHandTap -> {
                require(!enable || !note.isDead) { "请先移除消音，再添加点弦。" }
                note.isLeftHandTapped = enable
                GpRseProperties.updateEditedConnection(note) { it.copy(leftHandTapped = enable) }
            }
        }
    }

    fun changeFret(note: Note, fret: Int) {
        note.fret = fret.toDouble()
        if (note.harmonicType == HarmonicType.Natural) {
            note.harmonicValue = note.fret
            requireNaturalNode(note)
        }
    }

    private fun setHarmonic(note: Note, type: HarmonicType) {
        require(!note.isTieOrigin && !note.isTieDestination) { "请先移除延音线，再修改泛音。" }
        require(type == HarmonicType.None || !note.isDead) { "请先移除消音，再添加泛音。" }
        note.harmonicType = type
        note.harmonicValue = when (type) {
            HarmonicType.Natural -> note.fret
            HarmonicType.Artificial -> 12.0
            else -> 0.0
        }
        if (type == HarmonicType.Natural) requireNaturalNode(note)
    }

    private fun requireNaturalNode(note: Note) {
        require(note.harmonicPitch > 0.0) { "请选择有自然泛音的触弦位置，例如第 5、7 或 12 品。" }
    }

    private fun connectTie(note: Note) {
        val next = nextNote(note)
        require(note.fret == next.fret && note.realValue == next.realValue) { "延音线需要下一拍同弦、同品、同音高的音符。" }
        require(!next.hasBend) { "请先处理目标音符的推弦，再添加延音线。" }
        note.tieDestination = next
        next.tieOrigin = note
        next.isTieDestination = true
        GpRseProperties.updateEditedConnection(next) { it.copy(tieDestination = true) }
    }

    private fun connectHammerPull(note: Note) {
        val next = nextNote(note)
        require(note.fret != next.fret) { "击勾弦需要下一拍同弦、不同品位的音符。" }
        note.isHammerPullOrigin = true
        note.hammerPullDestination = next
        next.hammerPullOrigin = note
        GpRseProperties.updateEditedConnection(note) { it.copy(hopoOrigin = true) }
        GpRseProperties.updateEditedConnection(next) { it.copy(hopoDestination = true) }
    }

    private fun nextNote(note: Note, replacingSlide: Boolean = false): Note {
        val current = note.beat
        val next = current.nextBeat?.takeIf { !it.isEmpty && it.graceType == GraceType.None }
            ?.notes?.firstOrNull { it.isStringed && it.string == note.string }
        require(next != null && current.graceType == GraceType.None) { "请在下一拍同一根弦上输入目标音符。" }
        require(!note.isDead && !next.isDead) { "连接奏法需要两个有音高的音符。" }
        require(!note.isTieOrigin && !note.isHammerPullOrigin &&
            (replacingSlide || note.slideTarget == null) && !next.isTieDestination &&
            next.hammerPullOrigin == null && (next.slideOrigin == null || replacingSlide && next.slideOrigin === note)) {
            "已有连接奏法，请先移除再改用其他奏法。"
        }
        return next
    }

    private fun setSlideOut(note: Note, type: SlideOutType) {
        val next = if (type == SlideOutType.Shift || type == SlideOutType.Legato) {
            nextNote(note, replacingSlide = true).also {
                require(note.fret != it.fret) { "滑音需要下一拍同弦、不同品位的音符。" }
            }
        } else null
        note.slideTarget?.slideOrigin = null
        note.slideOutType = type
        note.slideTarget = next
        next?.slideOrigin = note
        updateSlide(note, 0xcf)
    }

    private fun setSlideIn(note: Note, type: SlideInType) {
        note.slideInType = type
        updateSlide(note, 0x30)
    }

    private fun updateSlide(note: Note, mask: Int) {
        val flags = NativeSlideScoreGraph.modelFlags(note)
        GpRseProperties.updateEditedConnection(note) { it.copy(slideFlags = (it.slideFlags and mask.inv()) or (flags and mask)) }
    }

    private fun hasBend(note: Note, value: Double): Boolean {
        val points = note.bendPoints?.toList().orEmpty()
        return points.size == 2 && points[0].offset == 0.0 && points[0].value == 0.0 &&
            points[1].offset == 60.0 && points[1].value == value
    }

    private fun setBend(note: Note, value: Double?) {
        require(!note.isTieDestination && !note.isTieOrigin) { "此推弦工具适用于单独的音符。" }
        note.bendPoints = null
        note.maxBendPoint = null
        note.bendType = BendType.None
        if (value != null) {
            note.addBendPoint(BendPoint(0.0, 0.0))
            note.addBendPoint(BendPoint(60.0, value))
        }
        GpRseProperties.updateEditedBend(note, value)
    }

    private fun clearTie(note: Note) {
        note.tieDestination?.let { target ->
            target.tieOrigin = null
            target.isTieDestination = false
            GpRseProperties.updateEditedConnection(target) { it.copy(tieDestination = false) }
        }
        note.tieDestination = null
    }

    private fun clearHammerPull(note: Note) {
        note.hammerPullDestination?.let { target ->
            target.hammerPullOrigin = null
            GpRseProperties.updateEditedConnection(target) { it.copy(hopoDestination = false) }
        }
        note.hammerPullDestination = null
        note.isHammerPullOrigin = false
        GpRseProperties.updateEditedConnection(note) { it.copy(hopoOrigin = false) }
    }

    fun connectedNotes(note: Note) = listOfNotNull(note.tieOrigin, note.tieDestination, note.hammerPullOrigin,
        note.hammerPullDestination, note.slideOrigin, note.slideTarget, note.slurOrigin, note.slurDestination)

    /** Clears linked endpoints before a note or an entire measure is removed. */
    fun detach(note: Note) {
        note.tieOrigin?.let(::clearTie)
        if (note.tieDestination != null) clearTie(note)
        note.hammerPullOrigin?.let(::clearHammerPull)
        if (note.hammerPullDestination != null) clearHammerPull(note)
        note.slideOrigin?.let { setSlideOut(it, SlideOutType.None) }
        if (note.slideTarget != null) setSlideOut(note, SlideOutType.None)
        note.slurOrigin?.slurDestination = null
        note.slurDestination?.let { target ->
            target.slurOrigin = null
            target.isSlurDestination = false
        }
    }
}
