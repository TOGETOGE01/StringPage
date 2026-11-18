@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.collections.DoubleList
import alphaTab.model.Automation
import alphaTab.model.Bar
import alphaTab.model.Beat
import alphaTab.model.Clef
import alphaTab.model.Duration
import alphaTab.model.GraceType
import alphaTab.model.MasterBar
import alphaTab.model.Score
import alphaTab.model.Staff
import alphaTab.model.Track
import alphaTab.model.Tuning
import alphaTab.model.Voice
import com.stringpage.app.rse.GpRseProperties
import kotlin.math.exp
import kotlin.math.ln

internal object ScoreStructure {
    const val MAX_VOICES = 4

    sealed interface Edit {
        fun reverse(): Edit
    }

    sealed interface TimingEdit : Edit {
        fun map(old: Int): Int?
    }

    data object RepeatEdit : TimingEdit {
        override fun reverse() = this
        override fun map(old: Int) = old
    }

    data object RecordingEdit : Edit {
        override fun reverse() = this
    }

    data object RecordingSyncEdit : Edit {
        override fun reverse() = this
    }

    data class BarEdit(val index: Int, val inserted: Boolean) : TimingEdit {
        override fun reverse() = copy(inserted = !inserted)

        override fun map(old: Int): Int? = when {
            old < index -> old
            inserted -> old + 1
            old == index -> null
            else -> old - 1
        }
    }

    data class TrackEdit(val index: Int, val inserted: Boolean, val slot: Int) : Edit {
        override fun reverse() = copy(inserted = !inserted)

        fun map(old: Int): Int? = when {
            old < index -> old
            inserted -> old + 1
            old == index -> null
            else -> old - 1
        }
    }

    data class RepeatMarks(val start: Boolean, val count: Int, val endings: Int) {
        constructor(bar: MasterBar) : this(bar.isRepeatStart, bar.repeatCount.toInt(), bar.alternateEndings.toInt())
    }

    enum class Instrument(val label: String, val program: Int, val pitches: List<Int>, val clefs: List<Clef> = listOf(Clef.G2)) {
        STEEL("钢弦木吉他", 25, listOf(64, 59, 55, 50, 45, 40)),
        NYLON("尼龙弦吉他", 24, STEEL.pitches),
        ELECTRIC("清音电吉他", 27, STEEL.pitches),
        BASS("电贝斯", 33, listOf(43, 38, 33, 28), listOf(Clef.F4)),
        PIANO("钢琴", 0, emptyList(), listOf(Clef.G2, Clef.F4)),
        VIOLIN("小提琴", 40, emptyList()),
        CELLO("大提琴", 42, emptyList(), listOf(Clef.F4)),
        STRINGS("弦乐合奏", 48, emptyList())
    }

    fun newScore(title: String, instrument: Instrument, bpm: Int, settings: Settings): Score {
        val score = Score().apply { this.title = title.trim() }
        require(score.title.isNotEmpty() && score.title.length <= 120) { "曲名请输入 1–120 个字符。" }
        val bar = MasterBar().apply {
            timeSignatureNumerator = 4.0
            timeSignatureDenominator = 4.0
        }
        score.addMasterBar(bar)
        setTempo(bar, bpm)
        var beatId = 0.0
        addTrack(score, instrument) { beatId++ }
        score.finish(settings)
        return score
    }

    fun insertBar(score: Score, index: Int, nextBeatId: () -> Double) {
        val count = score.masterBars.length.toInt()
        require(index in 0..count) { "超出可插入的小节范围。" }
        val reference = (index - 1).coerceAtLeast(0)
        val previous = score.masterBars[reference]
        val measure = MasterBar().apply {
            timeSignatureNumerator = previous.timeSignatureNumerator
            timeSignatureDenominator = previous.timeSignatureDenominator
            timeSignatureCommon = previous.timeSignatureCommon
            tripletFeel = previous.tripletFeel
            beamingRules = previous.beamingRules
        }
        val rests = restDurations(measure)
        if (index == 0) {
            val events = previous.tempoAutomations
            for (event in events) if (event.ratioPosition == 0.0) measure.tempoAutomations.push(event)
            for (i in events.length.toInt() - 1 downTo 0) {
                if (events[i].ratioPosition == 0.0) events.splice(i.toDouble(), 1.0, emptyList())
            }
        }
        score.masterBars.splice(index.toDouble(), 0.0, listOf(measure))
        val edit = BarEdit(index, inserted = true)
        for (track in score.tracks) {
            remapLineBreaks(track, edit)
            for (staff in track.staves) {
                val preceding = staff.bars[reference]
                val priorSlots = if (index == 0) 1 else staff.bars[index - 1].voices.length.toInt()
                val followingSlots = if (index == count) priorSlots else staff.bars[index].voices.length.toInt()
                val restBar = makeRestBar(rests, preceding, maxOf(priorSlots, followingSlots, 1), nextBeatId)
                staff.bars.splice(index.toDouble(), 0.0, listOf(restBar))
            }
        }
    }

    fun deleteBar(score: Score, index: Int) {
        val count = score.masterBars.length.toInt()
        require(count > 1) { "乐谱至少要保留一个小节。" }
        require(index in 0 until count) { "请选择要删除的小节。" }
        val removed = score.masterBars[index]
        val ahead = if (index + 1 < count) score.masterBars[index + 1] else null
        val prior = if (index > 0) score.masterBars[index - 1] else null
        val inheritedTempo = removed.tempoAutomations.maxByOrNull { it.ratioPosition }
        if (ahead != null && inheritedTempo != null && ahead.tempoAutomations.none { it.ratioPosition == 0.0 }) {
            inheritedTempo.ratioPosition = 0.0
            ahead.tempoAutomations.splice(0.0, 0.0, listOf(inheritedTempo))
        }
        // Keep a repeat attached to the remaining music in its original group.
        // A repeat contained entirely in the removed bar disappears with it.
        if (removed.isRepeatStart && !removed.isRepeatEnd && ahead != null && ahead.repeatGroup === removed.repeatGroup) {
            ahead.isRepeatStart = true
        }
        if (removed.isRepeatEnd && !removed.isRepeatStart && prior != null &&
            prior.repeatGroup === removed.repeatGroup && !prior.isRepeatEnd) {
            prior.repeatCount = removed.repeatCount
        }
        val edit = BarEdit(index, inserted = false)
        for (track in score.tracks) {
            remapLineBreaks(track, edit)
            for (staff in track.staves) {
                val cut = staff.bars[index]
                for (voice in cut.voices) for (beat in voice.beats) for (note in beat.notes) {
                    ScoreTechniques.detach(note)
                }
                staff.bars.splice(index.toDouble(), 1.0, emptyList())
            }
        }
        score.masterBars.splice(index.toDouble(), 1.0, emptyList())
    }

    private fun remapLineBreaks(track: Track, edit: BarEdit) {
        val remapped = track.lineBreaks?.mapNotNull { edit.map(it.toInt())?.toDouble() } ?: return
        track.lineBreaks = alphaTab.core.ecmaScript.Set(remapped)
    }

    fun addTrack(score: Score, instrument: Instrument, nextBeatId: () -> Double) {
        val used = score.tracks.flatMap { track ->
            listOf(track.playbackInfo.primaryChannel.toInt(), track.playbackInfo.secondaryChannel.toInt())
        }.toSet()
        val spare = (0..15).filter { it != 9 && it !in used }
        require(spare.size >= 2) { "没有足够的 MIDI 通道为新轨道分配独立播放。" }
        val rests = score.masterBars.map(::restDurations)
        val reference = score.tracks.firstOrNull()?.staves?.firstOrNull()
        val names = score.tracks.toList().map { it.name }.toSet()
        val trackName = generateSequence(1) { it + 1 }.map {
            if (it == 1) instrument.label else "${instrument.label} $it"
        }.first { it !in names }
        val track = Track().apply {
            name = trackName
            shortName = trackName.take(10)
            playbackInfo.program = instrument.program.toDouble()
            playbackInfo.primaryChannel = spare[0].toDouble()
            playbackInfo.secondaryChannel = spare[1].toDouble()
            ensureStaveCount(instrument.clefs.size.toDouble())
        }
        score.addTrack(track)
        for ((staffIndex, staff) in track.staves.withIndex()) {
            staff.showTablature = instrument.pitches.isNotEmpty()
            staff.showStandardNotation = true
            if (instrument.pitches.isNotEmpty()) configureStrings(staff, instrument.pitches, 0)
            for ((index, durations) in rests.withIndex()) {
                val bar = makeRestBar(durations, reference?.bars?.get(index), 1, nextBeatId)
                bar.clef = instrument.clefs[staffIndex]
                staff.addBar(bar)
            }
        }
    }

    fun deleteTrack(score: Score, index: Int) {
        val count = score.tracks.length.toInt()
        require(count > 1) { "乐谱至少要保留一条轨道。" }
        require(index in 0 until count) { "请选择要删除的轨道。" }
        GpRseProperties.removeAuthoredTrack(score, index)
        score.tracks.splice(index.toDouble(), 1.0, emptyList())
    }

    fun enableVoice(staff: Staff, bar: Int, voice: Int, nextBeatId: () -> Double): Boolean {
        require(!staff.isPercussion) { "请选择可编辑的有音高谱表。" }
        require(voice in 0 until MAX_VOICES && bar in 0 until staff.bars.length.toInt()) { "请选择有效的声部和小节。" }
        val target = staff.bars[bar]
        if (voice < target.voices.length && !target.voices[voice].isEmpty) return false
        val rests = restDurations(target.masterBar)
        for (measure in staff.bars) while (measure.voices.length <= voice) {
            addUnusedVoice(measure, nextBeatId)
        }
        val lane = target.voices[voice]
        lane.beats = alphaTab.collections.List()
        addRests(lane, rests, nextBeatId)
        return true
    }

    fun setTempo(bar: MasterBar, bpm: Int): Boolean {
        require(bpm in 20..400) { "速度请输入 20–400 BPM。" }
        val events = bar.tempoAutomations
        val starts = events.toList().filter { it.ratioPosition == 0.0 }
        if (starts.size == 1 && starts[0].value == bpm.toDouble()) return false
        val event = starts.firstOrNull()
        if (event == null) {
            events.splice(0.0, 0.0, listOf(Automation.buildTempoAutomation(false, 0.0, bpm.toDouble(), 2.0)))
        } else {
            event.value = bpm.toDouble()
            for (i in events.length.toInt() - 1 downTo 0) {
                if (events[i] !== event && events[i].ratioPosition == 0.0) events.splice(i.toDouble(), 1.0, emptyList())
            }
        }
        return true
    }

    fun setRepeatMarks(score: Score, index: Int, marks: RepeatMarks): Boolean {
        require(index in 0 until score.masterBars.length.toInt()) { "请选择有效小节。" }
        require(marks.count == 0 || marks.count in 2..127) { "反复次数请输入 0 或 2–127。" }
        val availableEndings = (1 shl MasterBar.MaxAlternateEndings.toInt()) - 1
        require(marks.endings in 0..availableEndings) { "请选择有效的房子编号。" }
        val bar = score.masterBars[index]
        if (RepeatMarks(bar) == marks) return false
        bar.isRepeatStart = marks.start
        bar.repeatCount = marks.count.toDouble()
        bar.alternateEndings = marks.endings.toDouble()
        return true
    }

    fun validatePlaybackOrder(score: Score) {
        // Estimate an upper bound by multiplying the repeat counts over each
        // written bar. Separate sections don't multiply one another.
        val bars = score.masterBars.length.toInt()
        val changes = DoubleArray(bars + 1)
        for (bar in score.masterBars) if (bar.isRepeatEnd && bar.repeatCount > 1) {
            val end = bar.index.toInt()
            val start = (bar.repeatGroup.opening?.index?.toInt() ?: 0).coerceIn(0, end)
            val factor = ln(bar.repeatCount)
            changes[start] += factor
            changes[end + 1] -= factor
        }
        val budget = maxOf(100_000, bars * 16)
        var factor = 0.0
        var estimatedBars = 0.0
        for (index in 0 until bars) {
            factor += changes[index]
            estimatedBars += exp(factor)
            require(estimatedBars <= budget) { "这组反复展开后过长，请减少演奏遍数。" }
        }
    }

    fun configureStrings(staff: Staff, pitches: List<Int>, capo: Int): Boolean {
        require(!staff.isPercussion && pitches.isNotEmpty() && pitches.all { it in 0..96 }) { "调弦请输入 MIDI 音高 0–96。" }
        require(staff.tuning.length == 0.0 || pitches.size == staff.tuning.length.toInt()) { "调弦必须保留原有弦数。" }
        require(capo in 0..24) { "变调夹请输入 0–24 品。" }
        val currentPitches = staff.tuning.toList().map { it.toInt() }
        if (staff.capo == capo.toDouble() && currentPitches == pitches) return false
        if (currentPitches != pitches) {
            val values = DoubleList()
            pitches.forEach { values.push(it.toDouble()) }
            staff.stringTuning = Tuning("", values, Instrument.entries.any { it.pitches == pitches })
        }
        staff.capo = capo.toDouble()
        return true
    }

    private data class Rest(val duration: Duration, val dots: Double = 0.0, val tuplet: Pair<Double, Double>? = null)

    private fun restDurations(master: MasterBar): List<Rest> {
        if (master.isAnacrusis) {
            // Match the written pickup rhythm, including dotted beats and tuplets,
            // so a new rest voice can't lengthen the pickup in the other tracks.
            val index = master.index.toInt()
            val source = master.score.tracks.toList().asSequence()
                .flatMap { it.staves.toList().asSequence() }
                .flatMap { it.bars.toList().getOrNull(index)?.voices?.toList().orEmpty().asSequence() }
                .filter { !it.isEmpty }
                .maxByOrNull { it.calculateDuration() }
            if (source != null) return source.beats.toList()
                .filter { !it.isEmpty && it.graceType == GraceType.None }
                .map { beat ->
                    Rest(beat.duration, beat.dots,
                        if (beat.hasTuplet) beat.tupletNumerator to beat.tupletDenominator else null)
                }
                .takeIf { it.isNotEmpty() }
                ?: listOf(Rest(Duration.Quarter))
        }
        val denominator = master.timeSignatureDenominator.toInt()
        val numerator = master.timeSignatureNumerator.toInt()
        require(denominator in 1..64 && denominator.countOneBits() == 1 && numerator in 1..128) {
            "这个拍号暂时无法生成休止小节。"
        }
        return buildList {
            var remaining = numerator * (64 / denominator)
            for (duration in BasicScoreEditor.EDITABLE_DURATIONS) {
                val units = 64 / duration.value.toInt()
                while (remaining >= units) {
                    add(Rest(duration))
                    remaining -= units
                }
            }
        }
    }

    private fun makeRestBar(rests: List<Rest>, keyFrom: Bar?, voiceCount: Int, nextBeatId: () -> Double): Bar {
        val bar = Bar().apply {
            if (keyFrom != null) {
                keySignature = keyFrom.keySignature
                keySignatureType = keyFrom.keySignatureType
                clef = keyFrom.clef
                clefOttava = keyFrom.clefOttava
            }
        }
        val voice = Voice()
        bar.addVoice(voice)
        addRests(voice, rests, nextBeatId)
        // The note renderer chains each voice to the same slot in the next bar.
        repeat(voiceCount - 1) { addUnusedVoice(bar, nextBeatId) }
        return bar
    }

    private fun addRests(voice: Voice, rests: List<Rest>, nextBeatId: () -> Double) {
        for (rest in rests) voice.addBeat(Beat().apply {
            id = nextBeatId()
            duration = rest.duration
            dots = rest.dots
            rest.tuplet?.let { (numerator, denominator) ->
                tupletNumerator = numerator
                tupletDenominator = denominator
            }
            isEmpty = false
        })
    }

    private fun addUnusedVoice(bar: Bar, nextBeatId: () -> Double) {
        val voice = Voice()
        bar.addVoice(voice)
        voice.addBeat(Beat().apply { id = nextBeatId(); isEmpty = true })
    }
}
