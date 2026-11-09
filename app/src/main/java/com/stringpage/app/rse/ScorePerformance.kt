@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app.rse

import alphaTab.Settings
import alphaTab.midi.ControllerType
import alphaTab.midi.IMidiFileHandler
import alphaTab.midi.MidiFileGenerator
import alphaTab.model.Note
import alphaTab.model.Score

/** Uses the same public MIDI generator as the score cursor: repeats, ties, swing,
 * grace notes and generated bends are expanded once, before the audio thread.
 * Source-note ambiguity is retained rather than assigning the wrong guitar string.
 */
class ScorePerformance internal constructor(
    val notes: List<NoteEvent>, val pitches: List<PitchEvent>,
    val controls: List<ControlEvent>, val programs: List<ProgramEvent>,
    val tempos: List<TempoPoint>, val bars: List<BarOccurrence>,
    val endTick: Double, val durationMs: Double, val tickShift: Double,
    val rseSyncOffsetMs: Double = 0.0, val rseFirstCueMs: Double = 0.0,
    val rsePartialTechniques: Int = 0
) {
    enum class SourceMatch { ExactPitch, GeneratedPitch, Ambiguous, Missing }
    data class NoteEvent(val id: Int, val track: Int, val channel: Int, val midi: Int, val velocity: Int,
        val startTick: Double, val endTick: Double, val startMs: Double, val endMs: Double,
        val sourceNote: Note?, val sourceMatch: SourceMatch, val channelTransposition: Int = 0,
        val slideVoice: SlideVoice? = null,val nativeBend: NativeBendInterpreter.Plan? = null)
    data class SlideVoice(val state: NativeFadePolicy.State, val sourceMidi: Int, val generated: Boolean)
    data class PitchEvent(val track: Int, val channel: Int, val midi: Int?, val value: Double, val tick: Double, val timeMs: Double)
    data class ControlEvent(val track: Int, val channel: Int, val controller: Int, val value: Int, val tick: Double, val timeMs: Double)
    data class ProgramEvent(val track: Int, val channel: Int, val program: Int, val tick: Double, val timeMs: Double)
    data class TempoPoint(val tick: Double, val timeMs: Double, val bpm: Double)
    data class BarOccurrence(val index: Int, val occurrence: Int, val startTick: Double, val endTick: Double)

    fun tickToMs(tick: Double): Double = tickToMs(tempos, tick)
    fun msToTick(timeMs: Double): Double {
        val point = tempos.lastOrNull { it.timeMs <= timeMs } ?: tempos.first()
        return point.tick + (timeMs - point.timeMs) * point.bpm * TICKS_PER_QUARTER / 60000.0
    }

    companion object {
        // alphaTab 1.8.4 MidiFile.division, also verified against generated SMF tests.
        private const val TICKS_PER_QUARTER = 960.0
        private fun tickToMs(tempos: List<TempoPoint>, tick: Double): Double {
            val point = tempos.lastOrNull { it.tick <= tick } ?: tempos.first()
            return point.timeMs + (tick - point.tick) * 60000.0 / (point.bpm * TICKS_PER_QUARTER)
        }
        fun compile(score: Score, settings: Settings = Settings(), cancelled: () -> Boolean = { false }): ScorePerformance {
            val handler = Collector(cancelled)
            val savedTimers = ArrayList<Pair<alphaTab.model.Beat, Double?>>()
            for (track in score.tracks) for (staff in track.staves) for (bar in staff.bars)
                for (voice in bar.voices) for (beat in voice.beats) savedTimers.add(beat to beat.timer)
            val generator = MidiFileGenerator(score, settings, handler)
            generator.applyTranspositionPitches = false
            try { generator.generate() } finally { savedTimers.forEach { (beat, timer) -> beat.timer = timer } }
            val tempoEvents = handler.tempos.sortedBy { it.first }.groupBy { it.first }.map { it.value.last() }
            val tempos = ArrayList<TempoPoint>()
            var tick = 0.0; var time = 0.0; var bpm = 120.0
            if (tempoEvents.none { it.first <= 0.0 }) tempos.add(TempoPoint(0.0, 0.0, bpm))
            for ((at, value) in tempoEvents) {
                time += (at - tick) * 60000.0 / (bpm * TICKS_PER_QUARTER)
                tick = at; bpm = if (value.isFinite() && value > 0.0) value else 120.0
                tempos.add(TempoPoint(tick, time, bpm))
            }
            if (tempos.isEmpty()) tempos.add(TempoPoint(0.0, 0.0, 120.0))
            val counts = HashMap<Int, Int>()
            val bars = generator.tickLookup.masterBars.toList().map { bar ->
                val index = bar.masterBar.index.toInt(); val occurrence = counts[index] ?: 0
                counts[index] = occurrence + 1
                BarOccurrence(index, occurrence, bar.start + handler.shift, bar.end + handler.shift)
            }
            // Beat windows are indexed by track + unfolded bar occurrence. Ties do not
            // create new generated notes, so they retain their origin's source Note.
            data class Window(val start: Double, val end: Double, val source: Note)
            val windows = HashMap<Pair<Int, Int>, MutableList<Window>>()
            for ((barNumber, bar) in bars.withIndex()) for ((trackIndex, track) in score.tracks.withIndex()) for (staff in track.staves) {
                if (bar.index >= staff.bars.length) continue
                for (voice in staff.bars[bar.index].voices) for (beat in voice.beats) for (note in beat.notes) {
                    windows.getOrPut(trackIndex to barNumber) { ArrayList() }.add(Window(
                        bar.startTick + beat.playbackStart,
                        bar.startTick + beat.playbackStart + beat.playbackDuration, note))
                }
            }
            fun transpose(channel: Int) = (generator.transpositionPitches.get(channel.toDouble()) ?: 0.0).toInt()
            val notes = handler.notes.sortedBy { it.start }.mapIndexed { id, raw ->
                var low = 0; var high = bars.size
                while (low < high) { val middle = (low + high) ushr 1
                    if (bars[middle].startTick <= raw.start) low = middle + 1 else high = middle }
                val currentBar = (low - 1).coerceAtLeast(0)
                val candidates = ((currentBar - 1).coerceAtLeast(0)..(currentBar + 1).coerceAtMost(bars.lastIndex))
                    .flatMap { windows[raw.track to it].orEmpty() }
                    .filter { raw.start + 0.01 >= it.start && raw.start < it.end - 0.01 }
                val exact = candidates.filter { kotlin.math.abs(it.source.calculateRealValue(false, true) - raw.midi) < 0.01 }
                val unique = exact.map { it.source }.distinctBy { it.id }
                val all = candidates.map { it.source }.distinctBy { it.id }
                val match = when { unique.size == 1 -> SourceMatch.ExactPitch; unique.size > 1 -> SourceMatch.Ambiguous
                    all.size == 1 -> SourceMatch.GeneratedPitch; all.size > 1 -> SourceMatch.Ambiguous; else -> SourceMatch.Missing }
                val source = when (match) { SourceMatch.ExactPitch -> unique.first(); SourceMatch.GeneratedPitch -> all.first(); else -> null }
                NoteEvent(id, raw.track, raw.channel, (raw.midi + transpose(raw.channel)).coerceIn(0, 127), raw.velocity,
                    raw.start, raw.end, tickToMs(tempos, raw.start), tickToMs(tempos, raw.end), source, match, transpose(raw.channel))
            }
            val pitches = handler.pitches.map { raw -> PitchEvent(raw.track, raw.channel,
                raw.key?.let { (it + transpose(raw.channel)).coerceIn(0,127) }, raw.value, raw.tick, tickToMs(tempos, raw.tick)) }.sortedBy { it.timeMs }
            val controls = handler.controls.map { raw -> ControlEvent(raw.track, raw.channel, raw.controller, raw.value,
                raw.tick, tickToMs(tempos, raw.tick)) }.sortedBy { it.timeMs }
            val programs = handler.programs.map { raw -> ProgramEvent(raw.track, raw.channel, raw.program,
                raw.tick, tickToMs(tempos, raw.tick)) }.sortedBy { it.timeMs }
            val end = maxOf(handler.endTick, notes.maxOfOrNull { it.endTick } ?: 0.0)
            require(end.isFinite() && end >= 0 && notes.size <= 200000) { "乐谱演奏事件过多。" }
            return ScorePerformance(notes, pitches, controls, programs, tempos, bars, end, tickToMs(tempos, end), handler.shift)
        }
    }

    private data class RawNote(val track: Int, val channel: Int, val midi: Int, val velocity: Int, val start: Double, val end: Double)
    private data class RawPitch(val track: Int, val channel: Int, val key: Int?, val value: Double, val tick: Double)
    private data class RawControl(val track: Int, val channel: Int, val controller: Int, val value: Int, val tick: Double)
    private data class RawProgram(val track: Int, val channel: Int, val program: Int, val tick: Double)
    private class Collector(private val cancelled: () -> Boolean) : IMidiFileHandler {
        val notes = ArrayList<RawNote>(); val pitches = ArrayList<RawPitch>()
        val controls = ArrayList<RawControl>(); val programs = ArrayList<RawProgram>()
        val tempos = ArrayList<Pair<Double, Double>>()
        var shift = 0.0; var endTick = 0.0
        private var events = 0
        private fun next() {
            check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消准备。" }
            require(++events <= 300000) { "演奏事件过多，请缩短反复段或乐谱。" }
        }
        override fun addTimeSignature(tick: Double, timeSignatureNumerator: Double, timeSignatureDenominator: Double) { next() }
        override fun addRest(track: Double, tick: Double, channel: Double) { next() }
        override fun addNote(track: Double, start: Double, length: Double, key: Double, velocity: Double, channel: Double) {
            next(); require(start.isFinite() && length.isFinite() && length >= 0 && key.isFinite() && velocity.isFinite())
            notes.add(RawNote(track.toInt(), channel.toInt(), key.toInt().coerceIn(0,127), velocity.toInt().coerceIn(0,127), start + shift, start + shift + length))
        }
        override fun addControlChange(track: Double, tick: Double, channel: Double, controller: ControllerType, value: Double) {
            next()
            controls.add(RawControl(track.toInt(), channel.toInt(), controller.value, value.toInt().coerceIn(0,127), tick + shift))
        }
        override fun addProgramChange(track: Double, tick: Double, channel: Double, program: Double) {
            next()
            programs.add(RawProgram(track.toInt(), channel.toInt(), program.toInt(), tick + shift))
        }
        override fun addTempo(tick: Double, tempo: Double) { next(); tempos.add(tick + shift to tempo) }
        override fun addBend(track: Double, tick: Double, channel: Double, value: Double) {
            next()
            pitches.add(RawPitch(track.toInt(), channel.toInt(), null, kotlin.math.floor(value).coerceAtMost(16384.0), tick + shift))
        }
        override fun addNoteBend(track: Double, tick: Double, channel: Double, key: Double, value: Double) {
            next()
            pitches.add(RawPitch(track.toInt(), channel.toInt(), key.toInt(), value, tick + shift))
        }
        override fun finishTrack(track: Double, tick: Double) { endTick = maxOf(endTick, tick + shift) }
        override fun addTickShift(tickShift: Double) { shift = tickShift }
    }
}
