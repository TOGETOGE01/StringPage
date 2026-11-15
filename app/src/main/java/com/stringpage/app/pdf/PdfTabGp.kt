@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.pdf

import alphaTab.Settings
import alphaTab.collections.DoubleList
import alphaTab.model.Automation
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.BendPoint
import alphaTab.model.Duration
import alphaTab.model.GraceType
import alphaTab.model.HarmonicType
import alphaTab.model.MasterBar
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.SlideInType
import alphaTab.model.SlideOutType
import alphaTab.model.Track
import alphaTab.model.Tuning
import alphaTab.model.Voice
import alphaTab.model.VibratoType
import com.stringpage.app.ScoreExport

internal object PdfTabGp {
    private val durations = mapOf(1 to Duration.Whole, 2 to Duration.Half, 4 to Duration.Quarter,
        8 to Duration.Eighth, 16 to Duration.Sixteenth, 32 to Duration.ThirtySecond,
        64 to Duration.SixtyFourth)

    fun encode(title: String, bpm: Int, bars: List<PdfTabBar>): ByteArray = encodeTracks(
        title, bpm, listOf(PdfTabTrack("吉他", 6, PdfTabTuning.standard(6), bars)))

    fun encodeTracks(title: String, bpm: Int, tracks: List<PdfTabTrack>): ByteArray {
        pdfRequire(bpm in 20..400, "请输入 20 到 400 之间的 BPM。")
        pdfRequire(tracks.size in 1..15, "一次可导出 1 到 15 个声部。")
        val master = tracks.first().bars
        pdfRequire(master.isNotEmpty() && tracks.all { track ->
            track.bars.size == master.size && track.bars.zip(master).all { (bar, reference) ->
                bar.issues.isEmpty() && bar.beats.isNotEmpty() && bar.number == reference.number && bar.meter == reference.meter
            }
        }, "所选范围含有无法确认的小节，或声部未能对齐。")
        val score = Score().apply { this.title = title }
        for ((index, measure) in master.withIndex()) {
            score.addMasterBar(MasterBar().apply {
                timeSignatureNumerator = measure.meter.beats.toDouble()
                timeSignatureDenominator = measure.meter.unit.toDouble()
                val tempo = if (index == 0) bpm else measure.bpm
                if (tempo != null) tempoAutomations.push(Automation.buildTempoAutomation(false, 0.0, tempo.toDouble(), 2.0))
            })
        }
        tracks.forEachIndexed { index, source -> addTrack(score, index, source) }
        score.finish(Settings())
        return ScoreExport.guitarProBytes(score)
    }

    private fun addTrack(score: Score, index: Int, source: PdfTabTrack) {
        val tuning = source.tuning
        pdfRequire(tuning != null && tuning.size == source.strings && tuning.all { it in 0..96 }, "请确认 ${source.name} 的调弦。")
        val track = Track().apply {
            name = source.name
            shortName = source.name.take(10)
            playbackInfo.program = if (source.strings <= 5) 33.0 else 24.0
            val channel = if (index >= 9) index + 1 else index
            playbackInfo.primaryChannel = channel.toDouble()
            playbackInfo.secondaryChannel = channel.toDouble()
            ensureStaveCount(1.0)
        }
        score.addTrack(track)
        val staff = track.staves[0].apply {
            val pitches = DoubleList()
            tuning!!.forEach { pitches.push(it.toDouble()) }
            stringTuning = Tuning("", pitches, tuning == PdfTabTuning.standard(source.strings))
            showStandardNotation = false
        }
        var previous = emptyMap<Int, Int>()
        for (measure in source.bars) {
            val bar = Bar()
            val voice = Voice()
            staff.addBar(bar)
            bar.addVoice(voice)
            for (event in measure.beats) {
                val beat = Beat().apply {
                    duration = durations.getValue(event.denominator)
                    dots = event.dots.toDouble()
                    graceType = when (event.grace) {
                        PdfGrace.BEFORE_BEAT -> GraceType.BeforeBeat
                        PdfGrace.ON_BEAT -> GraceType.OnBeat
                        null -> GraceType.None
                    }
                    if (event.triplet) { tupletNumerator = 3.0; tupletDenominator = 2.0 }
                }
                voice.addBeat(beat)
                for (fretted in event.notes) {
                    pdfRequire(fretted.string in 1..source.strings)
                    beat.addNote(Note().apply {
                        string = fretted.string.toDouble()
                        fret = fretted.fret.toDouble()
                        isDead = fretted.dead
                        isPalmMute = fretted.palmMute
                        isTieDestination = fretted.tied && previous[fretted.string] == fretted.fret
                        isGhost = fretted.ghost
                        slideInType = when (fretted.slideIn) {
                            PdfSlideIn.BELOW -> SlideInType.IntoFromBelow
                            PdfSlideIn.ABOVE -> SlideInType.IntoFromAbove
                            null -> SlideInType.None
                        }
                        slideOutType = when (fretted.slideOut) {
                            PdfSlideOut.SHIFT -> SlideOutType.Shift
                            PdfSlideOut.LEGATO -> SlideOutType.Legato
                            PdfSlideOut.UP -> SlideOutType.OutUp
                            PdfSlideOut.DOWN -> SlideOutType.OutDown
                            null -> SlideOutType.None
                        }
                        vibrato = when (fretted.vibrato) {
                            PdfVibrato.SLIGHT -> VibratoType.Slight
                            PdfVibrato.WIDE -> VibratoType.Wide
                            null -> VibratoType.None
                        }
                        isHammerPullOrigin = fretted.hammerPull
                        isLetRing = fretted.letRing
                        fretted.harmonic?.let {
                            harmonicType = if (fretted.artificialHarmonic) HarmonicType.Artificial else HarmonicType.Natural
                            harmonicValue = it
                        }
                        fretted.bend?.let { bend ->
                            addBendPoint(BendPoint(0.0, bend.start.toDouble()))
                            bend.middle?.let { addBendPoint(BendPoint(30.0, it.toDouble())) }
                            addBendPoint(BendPoint(60.0, bend.end.toDouble()))
                        }
                    })
                }
                previous = event.notes.associate { it.string to it.fret }
            }
        }
    }
}
