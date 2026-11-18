@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.model.BackingTrack
import alphaTab.model.Beat
import alphaTab.model.Duration
import alphaTab.model.DynamicValue
import alphaTab.model.GraceType
import alphaTab.model.JsonConverter
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.Staff
import alphaTab.model.Voice
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.stringpage.app.rse.GpRseProperties
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Publishes validated score edits as single undoable transactions. */
internal class BasicScoreEditor(
    private val context: Context,
    private val scoreProvider: () -> Score?,
    private val onChanged: (Score, String, ScoreStructure.Edit?) -> Unit,
    private val settingsProvider: () -> Settings = { Settings() }
) {
    /** All indices are zero based; the dialog presents them as one based. */
    data class NoteAddress(val track: Int, val staff: Int, val bar: Int, val voice: Int, val beat: Int, val note: Int)
    internal data class BeatAddress(val track: Int, val staff: Int, val bar: Int, val voice: Int, val beat: Int)
    internal enum class TechniqueGroup(val label: String) {
        Articulation("闷音与发音"), Bend("推弦与揉弦"), Connection("延音线与击勾弦"), Slide("滑音"),
        Harmonic("泛音与点弦")
    }
    internal enum class Technique(val label: String, val group: TechniqueGroup) {
        PalmMute("闷音", TechniqueGroup.Articulation),
        LetRing("延长发音（Let Ring）", TechniqueGroup.Articulation),
        Staccato("断奏", TechniqueGroup.Articulation),
        Ghost("轻触音（Ghost Note）", TechniqueGroup.Articulation),
        Dead("消音（X 音符）", TechniqueGroup.Articulation),
        HalfBend("半音推弦", TechniqueGroup.Bend),
        WholeBend("全音推弦", TechniqueGroup.Bend),
        Vibrato("揉弦", TechniqueGroup.Bend),
        WideVibrato("宽揉弦", TechniqueGroup.Bend),
        Tie("延音线到下一拍", TechniqueGroup.Connection),
        HammerPull("击弦／勾弦到下一拍", TechniqueGroup.Connection),
        ShiftSlide("换把滑音到下一拍", TechniqueGroup.Slide),
        LegatoSlide("连奏滑音到下一拍", TechniqueGroup.Slide),
        SlideFromBelow("从下方滑入", TechniqueGroup.Slide),
        SlideFromAbove("从上方滑入", TechniqueGroup.Slide),
        SlideOutDown("向下滑出", TechniqueGroup.Slide),
        SlideOutUp("向上滑出", TechniqueGroup.Slide),
        NaturalHarmonic("自然泛音", TechniqueGroup.Harmonic),
        OctaveHarmonic("人工泛音（高八度）", TechniqueGroup.Harmonic),
        LeftHandTap("左手点弦", TechniqueGroup.Harmonic);

        fun supports(note: Note) = note.isStringed || this in setOf(Staccato, Ghost, Vibrato, WideVibrato)
    }

    // The JSON object contains all serializable document data. Audio is deliberately
    // held by reference once: alphaTab's BackingTrackSerializer omits its raw bytes.
    private data class Snapshot(
        val json: alphaTab.collections.Map<String, Any?>?,
        val hadBackingTrack: Boolean,
        val audio: Uint8Array?,
        val bindings: GpRseProperties.EditBindings?
    )
    private data class Change(val before: Snapshot, val description: String, val edit: ScoreStructure.Edit? = null)
    private data class Clipboard(val score: Snapshot, val from: BeatAddress, val count: Int, val trackSlot: Int)
    private data class ElementIds(var beat: Double, var note: Double)
    private val undoHistory = ArrayDeque<Change>()
    private val redoHistory = ArrayDeque<Change>()
    private var boundScore: Score? = null
    private var externalBefore: Snapshot? = null
    private var clipboard: Clipboard? = null
    // Imported tracks retain their original preference slot while the draft
    // changes order. New tracks use unique, session-local negative slots.
    private val trackSlots = mutableListOf<Int>()
    private var nextTrackSlot = -1
    private var publishing = false
    internal val hasClipboard get() = clipboard != null

    fun beginExternalChange() {
        synchronizeSession()
        if (externalBefore == null) scoreProvider()?.let { externalBefore = snapshot(it) }
    }

    fun commitExternalChange(description: String) {
        externalBefore?.let { remember(undoHistory, Change(it, description)); redoHistory.clear() }
        externalBefore = null
    }

    val canUndo: Boolean get() { synchronizeSession(); return undoHistory.isNotEmpty() }
    val canRedo: Boolean get() { synchronizeSession(); return redoHistory.isNotEmpty() }
    val undoCount: Int get() { synchronizeSession(); return undoHistory.size }
    val redoCount: Int get() { synchronizeSession(); return redoHistory.size }

    /** Call for a newly opened document, including when opening the same URI again. */
    fun reset(score: Score?) {
        boundScore = score
        undoHistory.clear()
        redoHistory.clear()
        externalBefore = null
        clipboard = null
        trackSlots.clear()
        trackSlots.addAll(0 until (score?.tracks?.length?.toInt() ?: 0))
        nextTrackSlot = -1
    }

    internal fun trackSlot(index: Int): Int = trackSlots[index]

    private fun synchronizeSession() {
        val current = scoreProvider()
        if (!publishing && current !== boundScore) reset(current)
    }

    private fun snapshot(score: Score) = Snapshot(
        JsonConverter.scoreToJsObject(score), score.backingTrack != null, score.backingTrack?.rawAudioFile,
        GpRseProperties.captureEditBindings(score)
    )

    private fun restore(snapshot: Snapshot): Score {
        val result = JsonConverter.jsObjectToScore(snapshot.json, settingsProvider())
        result.backingTrack = if (snapshot.hadBackingTrack) (result.backingTrack ?: BackingTrack()).apply {
            rawAudioFile = snapshot.audio
        } else null
        snapshot.bindings?.let { GpRseProperties.restoreEditBindings(result, it) }
        return result
    }

    private fun publish(score: Score, description: String, edit: ScoreStructure.Edit?) {
        if (edit is ScoreStructure.TrackEdit) {
            if (edit.inserted) trackSlots.add(edit.index, edit.slot) else trackSlots.removeAt(edit.index)
        }
        boundScore = score
        publishing = true
        try { onChanged(score, description, edit) }
        finally { publishing = false }
    }

    private fun remember(history: ArrayDeque<Change>, change: Change) {
        history.addLast(change)
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        // Ordinary edits share the same audio bytes. Keep recent replacement
        // generations, without allowing a chain of large recordings in memory.
        val recordings = Collections.newSetFromMap(IdentityHashMap<Uint8Array, Boolean>())
        var bytes = 0.0
        val newest = history.descendingIterator()
        while (newest.hasNext()) {
            val raw = newest.next().before.audio ?: continue
            if (!recordings.add(raw)) continue
            bytes += raw.length
            if (recordings.size > 1 && bytes > RECORDING_HISTORY_BYTES) {
                newest.remove()
                while (newest.hasNext()) {
                    newest.next()
                    newest.remove()
                }
                break
            }
        }
    }

    private fun mutate(
        description: String, edit: ScoreStructure.Edit? = null, change: (Score) -> Boolean
    ): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: throw IllegalStateException("请先打开乐谱。")
        val before = snapshot(current)
        val candidate = restore(before)
        if (!change(candidate)) return false
        // Round-trip once more so finish() resolves derived pitch, ties and cached
        // lookups from clean objects rather than calling finish twice on one model.
        val finished = restore(snapshot(candidate))
        if (edit is ScoreStructure.TimingEdit) ScoreStructure.validatePlaybackOrder(finished)
        remember(undoHistory, Change(before, description, edit))
        redoHistory.clear()
        publish(finished, description, edit)
        return true
    }

    fun updateMetadata(title: String, artist: String): Boolean = mutate("修改曲名与作者") { score ->
        val nextTitle = title.trim()
        val nextArtist = artist.trim()
        require(nextTitle.length <= MAX_TEXT_LENGTH && nextArtist.length <= MAX_TEXT_LENGTH) {
            "曲名和作者分别最多 $MAX_TEXT_LENGTH 个字符。"
        }
        if (score.title == nextTitle && score.artist == nextArtist) false
        else { score.title = nextTitle; score.artist = nextArtist; true }
    }

    internal fun appendBar(): Boolean = insertBar(scoreProvider()?.masterBars?.length?.toInt() ?: 0)

    internal fun insertBar(index: Int): Boolean = mutate(
        "插入第 ${index + 1} 小节", ScoreStructure.BarEdit(index, inserted = true)
    ) { score ->
        val ids = nextElementIds(score)
        ScoreStructure.insertBar(score, index) { ids.beat++ }
        true
    }

    internal fun deleteBar(index: Int): Boolean = mutate(
        "删除第 ${index + 1} 小节", ScoreStructure.BarEdit(index, inserted = false)
    ) { score ->
        ScoreStructure.deleteBar(score, index)
        true
    }

    internal fun addTrack(instrument: ScoreStructure.Instrument): Boolean {
        synchronizeSession()
        val edit = ScoreStructure.TrackEdit(trackSlots.size, inserted = true, slot = nextTrackSlot--)
        return mutate("已添加${instrument.label}轨道", edit) { score ->
            val ids = nextElementIds(score)
            ScoreStructure.addTrack(score, instrument) { ids.beat++ }
            GpRseProperties.appendAuthoredTrack(score, edit.slot)
            true
        }
    }

    internal fun deleteTrack(index: Int): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: throw IllegalStateException("请先打开乐谱。")
        val track = current.tracks.at(index, "轨道")
        val name = track.name.ifBlank { "轨道 ${index + 1}" }
        val edit = ScoreStructure.TrackEdit(index, inserted = false, slot = trackSlot(index))
        return mutate("已删除 $name", edit) { score ->
            ScoreStructure.deleteTrack(score, index)
            true
        }
    }

    internal fun setBarTempo(bar: Int, bpm: Int): Boolean = mutate("第 ${bar + 1} 小节速度设为 $bpm BPM") { score ->
        ScoreStructure.setTempo(score.masterBars.at(bar, "小节"), bpm)
    }

    internal fun replaceRecording(clip: RecordingAudio.Clip?): Boolean =
        mutate(if (clip == null) "已移除内嵌录音" else "已导入内嵌录音", ScoreStructure.RecordingEdit) { score ->
            if (clip == null && score.backingTrack == null) return@mutate false
            score.backingTrack = clip?.let { BackingTrack().apply { rawAudioFile = clip.bytes } }
            for (bar in score.masterBars) bar.syncPoints = null
            if (clip != null) ScoreRecording.putPoint(score, ScoreRecording.Point(0, 0, 0.0, 0.0))
            true
        }

    internal fun setRecordingPoints(
        points: List<ScoreRecording.Point>, visits: List<ScoreRecording.Visit>, durationMs: Double
    ): Boolean = mutate("已保存录音同步点", ScoreStructure.RecordingSyncEdit) { score ->
        require(score.backingTrack?.rawAudioFile != null) { "请先导入录音。" }
        val sorted = ScoreRecording.ordered(points, visits, durationMs)
        if (ScoreRecording.points(score).toSet() == sorted.toSet()) return@mutate false
        for (bar in score.masterBars) bar.syncPoints = null
        for (point in sorted) ScoreRecording.putPoint(score, point)
        true
    }

    internal fun setRepeatMarks(bar: Int, marks: ScoreStructure.RepeatMarks): Boolean =
        mutate("更新第 ${bar + 1} 小节的反复记号", ScoreStructure.RepeatEdit) { score ->
            ScoreStructure.setRepeatMarks(score, bar, marks)
        }

    internal fun enableVoice(track: Int, staff: Int, bar: Int, voice: Int): Boolean =
        mutate("启用第 ${bar + 1} 小节的第 ${voice + 1} 声部") { score ->
            val sheet = score.tracks.at(track, "轨道").staves.at(staff, "谱表")
            val ids = nextElementIds(score)
            ScoreStructure.enableVoice(sheet, bar, voice) { ids.beat++ }
        }

    internal fun setTuning(track: Int, staff: Int, pitches: List<Int>, capo: Int): Boolean =
        mutate("已修改调弦和变调夹") { score ->
            ScoreStructure.configureStrings(score.tracks.at(track, "轨道").staves.at(staff, "谱表"), pitches, capo)
        }

    /** Modifies a fretted note and its tie chain, keeping every beat's duration. */
    fun updateFret(address: NoteAddress, fret: Int): Boolean = mutate("修改音符品位") { score ->
        val (staff, note) = resolveNote(score, address)
        requireEditable(staff, note)
        changeTiedFret(note, fret)
    }

    private fun changeTiedFret(note: Note, fret: Int): Boolean {
        require(fret in 0..MAX_FRET) { "品位请输入 0–$MAX_FRET。" }
        val chain = tieChain(note)
        chain.forEach { requireEditable(it.beat.voice.bar.staff, it) }
        if (chain.all { it.fret == fret.toDouble() }) return false
        chain.forEach { ScoreTechniques.changeFret(it, fret) }
        return true
    }

    internal fun updatePitch(address: NoteAddress, pitch: Int): Boolean = mutate("修改音高") { score ->
        require(pitch in 0..127) { "音高超出可记谱范围。" }
        val (_, note) = resolveNote(score, address)
        val chain = tieChain(note)
        for (member in chain) {
            require(!member.isStringed && !member.isPercussion) { "请使用品位编辑修改这个音符。" }
            require(member.beat.notes.none { it !== member && writtenPitch(it) == pitch }) { "这一拍已有相同音高的音符。" }
        }
        if (chain.all { writtenPitch(it) == pitch }) return@mutate false
        for (member in chain) {
            member.octave = (pitch / 12).toDouble()
            member.tone = (pitch % 12).toDouble()
        }
        true
    }

    private fun tieChain(note: Note): List<Note> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Note, Boolean>())
        var first = note
        while (first.tieOrigin != null) {
            require(visited.add(first)) { "延音链结构异常，无法修改这个音符。" }
            first = first.tieOrigin!!
        }
        visited.clear()
        val chain = mutableListOf<Note>()
        var next: Note? = first
        while (next != null) {
            val item = next
            require(visited.add(item)) { "延音链结构异常，无法修改这个音符。" }
            chain.add(item)
            next = item.tieDestination
        }
        return chain
    }

    internal fun putFret(address: BeatAddress, string: Int, fret: Int): Boolean = mutate("输入品位") { score ->
        var beat = editableBeat(score, address)
        require(string in 1..beat.voice.bar.staff.tuning.length.toInt()) { "请选择有效的琴弦。" }
        require(fret in 0..MAX_FRET) { "品位请输入 0–$MAX_FRET。" }
        if (beat.voice.isEmpty) {
            val ids = nextElementIds(score)
            ScoreStructure.enableVoice(beat.voice.bar.staff, address.bar, address.voice) { ids.beat++ }
            beat = editableBeat(score, address)
        }
        val existing = beat.notes.firstOrNull { it.string.toInt() == string }
        if (existing != null) changeTiedFret(existing, fret)
        else {
            beat.isEmpty = false
            val ids = nextElementIds(score)
            beat.addNote(Note().apply { id = ids.note; this.string = string.toDouble(); this.fret = fret.toDouble() })
            true
        }
    }

    internal fun putMidiChord(address: BeatAddress, attacks: List<MidiAttack>, preferredString: Int = 0): Boolean =
        mutate("写入 MIDI 音符") { score ->
            require(attacks.isNotEmpty() && attacks.all { it.pitch in 0..127 && it.velocity in 1..127 }) { "没有可写入的 MIDI 音符。" }
            var beat = editableBeat(score, address)
            require(beat.graceType == GraceType.None) { "请选择常规节拍后输入 MIDI 音符。" }
            val staff = beat.voice.bar.staff
            val pitches = attacks.map { it.pitch }.distinct().sorted()
            val velocity = attacks.map { it.velocity }.average()
            // Match the representative playback velocity of each GP dynamic level.
            val dynamics = INPUT_DYNAMICS[((velocity - 15) / 16).roundToInt().coerceIn(INPUT_DYNAMICS.indices)]
            val current = beat.notes.toList()
            if (current.size == pitches.size && current.none { it.isDead } &&
                current.map { it.realValue }.sorted() == pitches.map { it.toDouble() }) {
                if (beat.dynamics == dynamics) return@mutate false
                beat.dynamics = dynamics
                return@mutate true
            }
            val strings = staff.tuning.length.toInt()
            val transpose = staff.transpositionPitch.toInt()
            val open = (1..strings).map { (Note.getStringTuning(staff, it.toDouble()) + staff.capo).toInt() - transpose }
            val anchorNote = (beat.notes.toList() + beat.previousBeat?.notes?.toList().orEmpty()).firstOrNull {
                it.isStringed && it.string.toInt() == preferredString
            }
            val positioning = if (strings > 0) MidiFingering.assign(pitches, open, preferredString, anchorNote?.fret?.toInt())
            else null
            val written = pitches.map { it + transpose }
            if (strings == 0) require(written.all { it in 0..127 }) { "音高超出当前乐器的可记谱范围。" }
            val ids = nextElementIds(score)
            if (beat.voice.isEmpty) {
                ScoreStructure.enableVoice(staff, address.bar, address.voice) { ids.beat++ }
                beat = editableBeat(score, address)
            }
            val kept = mutableSetOf<Note>()
            val inserted = mutableListOf<Note>()
            for ((index, pitch) in pitches.withIndex()) {
                val position = positioning?.get(index)
                val matching = beat.notes.firstOrNull { note ->
                    !note.isDead && note.realValue == pitch.toDouble() &&
                        (position == null || note.string == position.string.toDouble() && note.fret == position.fret.toDouble())
                }
                if (matching != null) kept.add(matching)
                else inserted.add(Note().apply {
                    id = ids.note++
                    if (position != null) {
                        string = position.string.toDouble()
                        fret = position.fret.toDouble()
                    } else {
                        octave = (written[index] / 12).toDouble()
                        tone = (written[index] % 12).toDouble()
                    }
                })
            }
            for (note in beat.notes.toList()) if (note !in kept) {
                ScoreTechniques.detach(note)
                beat.removeNote(note)
            }
            beat.isEmpty = false
            beat.dynamics = dynamics
            inserted.forEach(beat::addNote)
            true
        }

    internal fun clearBeat(address: BeatAddress, string: Int? = null): Boolean = mutate(
        if (string == null) "写入休止符" else "删除音符"
    ) { score ->
        val beat = editableBeat(score, address)
        val notes = beat.notes.toList().filter { string == null || it.string.toInt() == string }
        if (notes.isEmpty()) false else {
            notes.forEach(ScoreTechniques::detach)
            notes.forEach(beat::removeNote)
            true
        }
    }

    internal fun toggleTechnique(address: BeatAddress, string: Int, technique: Technique): Boolean = mutate(technique.label) { score ->
        val note = editableBeat(score, address).notes.firstOrNull { it.string.toInt() == string }
            ?: throw IllegalArgumentException("请先输入当前琴弦的音符。")
        applyTechnique(note, technique)
    }

    internal fun toggleTechnique(address: NoteAddress, technique: Technique): Boolean = mutate(technique.label) { score ->
        applyTechnique(resolveNote(score, address).second, technique)
    }

    internal fun removeNote(address: NoteAddress): Boolean = mutate("删除音符") { score ->
        val (_, note) = resolveNote(score, address)
        ScoreTechniques.detach(note)
        note.beat.removeNote(note)
        true
    }

    private fun applyTechnique(note: Note, technique: Technique): Boolean {
        require(technique.supports(note)) { "这个技法仅适用于有弦号的音符。" }
        ScoreTechniques.toggle(note, technique)
        return true
    }

    /** Spends or restores time using adjacent rests, leaving other note starts and the bar boundary intact. */
    internal fun updateDuration(address: BeatAddress, duration: Duration): Boolean = mutate("调整时值") { score ->
        val beat = editableBeat(score, address)
        require(duration in EDITABLE_DURATIONS) { "请选择支持的时值。" }
        require(isPlainRhythm(beat)) { "连音组、附点和装饰音的时值暂需保留。" }
        val oldTicks = durationTicks(beat.duration)
        val newTicks = durationTicks(duration)
        if (newTicks == oldTicks) false else {
            val beats = beat.voice.beats
            val next = address.beat + 1
            var remaining = oldTicks - newTicks
            var absorbed = 0
            while (remaining < 0 && next + absorbed < beats.length) {
                val rest = beats[next + absorbed]
                if (!rest.isRest || rest.isEmpty || !isPlainRhythm(rest) || rest.text?.isNotEmpty() == true ||
                    rest.automations.length > 0 || rest.chordId != null || rest.hasWhammyBar) break
                remaining += durationTicks(rest.duration)
                absorbed++
            }
            require(remaining >= 0) { "后面没有足够的休止时值，延长会挤占其他音符。" }
            val rests = ArrayList<Beat>()
            val ids = nextElementIds(score)
            for (value in EDITABLE_DURATIONS) {
                val ticks = durationTicks(value)
                while (remaining >= ticks) {
                    rests.add(Beat().apply { id = ids.beat++; this.duration = value; isEmpty = false; voice = beat.voice })
                    remaining -= ticks
                }
            }
            require(remaining == 0) { "剩余时值无法拆成当前支持的休止符。" }
            beat.duration = duration
            beats.splice(next.toDouble(), absorbed.toDouble(), rests)
            true
        }
    }

    internal fun copyBars(from: BeatAddress, count: Int) {
        synchronizeSession()
        val loaded = scoreProvider() ?: return
        require(count in 1..MAX_CLIPBOARD_BARS) { "一次可复制 1–$MAX_CLIPBOARD_BARS 个小节。" }
        val voices = selectedVoices(loaded, from, count)
        requireClosedPhrases(voices)
        clipboard = Clipboard(snapshot(loaded).copy(audio = null), from, count, trackSlot(from.track))
    }

    internal fun pasteBars(to: BeatAddress): Boolean = mutate("粘贴小节") { score ->
        val clip = clipboard ?: return@mutate false
        val source = restore(clip.score)
        val copied = selectedVoices(source, clip.from, clip.count)
        val destination = selectedVoices(score, to, clip.count)
        requireClosedPhrases(destination)
        val sourceStaff = copied.first().bar.staff
        require(sourceStaff.tuning.toList() == destination.first().bar.staff.tuning.toList()) {
            "请粘贴到弦数和调弦相同的谱表。"
        }
        for ((incoming, outgoing) in copied.zip(destination)) {
            val a = incoming.bar.masterBar
            val b = outgoing.bar.masterBar
            require(a.timeSignatureNumerator == b.timeSignatureNumerator &&
                a.timeSignatureDenominator == b.timeSignatureDenominator && a.calculateDuration() == b.calculateDuration()) {
                "粘贴范围的拍号或小节长度不同。"
            }
            val sameStaff = clip.from.staff == to.staff && clip.trackSlot == trackSlot(to.track)
            require(sameStaff || incoming.beats.none { it.chordId != null }) { "带和弦图的小节只能粘贴到原谱表。" }
        }
        val ids = nextElementIds(score)
        for ((incoming, outgoing) in copied.zip(destination)) {
            outgoing.beats = incoming.beats
            for (beat in outgoing.beats) {
                beat.id = ids.beat++
                beat.voice = outgoing
                for (note in beat.notes) note.id = ids.note++
            }
        }
        true
    }

    private fun nextElementIds(score: Score): ElementIds {
        var beatId = 0.0
        var noteId = 0.0
        for (track in score.tracks) for (staff in track.staves) for (bar in staff.bars) for (voice in bar.voices) {
            for (beat in voice.beats) {
                beatId = maxOf(beatId, beat.id)
                for (note in beat.notes) noteId = maxOf(noteId, note.id)
            }
        }
        return ElementIds(beatId + 1, noteId + 1)
    }

    private fun selectedVoices(score: Score, from: BeatAddress, count: Int): List<Voice> {
        val staff = score.tracks.at(from.track, "轨道").staves.at(from.staff, "谱表")
        require(!staff.isPercussion) { "请选择可编辑的有音高谱表。" }
        return List(count) { offset -> staff.bars.at(from.bar + offset, "小节").voices.at(from.voice, "声部") }
    }

    private fun requireClosedPhrases(voices: List<Voice>) {
        val notes = Collections.newSetFromMap(IdentityHashMap<Note, Boolean>())
        for (voice in voices) for (beat in voice.beats) for (note in beat.notes) notes.add(note)
        require(notes.none { note -> ScoreTechniques.connectedNotes(note).any { it !in notes } }) {
            "有延音、滑音、连奏或击勾弦跨出选区，请把连接的小节一起选中。"
        }
    }

    private fun isPlainRhythm(beat: Beat) = beat.graceType == GraceType.None && !beat.hasTuplet &&
        beat.dots == 0.0 && beat.overrideDisplayDuration == null

    // GP duration values count subdivisions of a whole note. Use a fixed exact
    // grid here so even the shortest imported divisions can be validated.
    private fun durationTicks(duration: Duration): Int = when (duration) {
        Duration.QuadrupleWhole -> 1024
        Duration.DoubleWhole -> 512
        else -> 256 / duration.value.toInt()
    }

    private fun editableBeat(score: Score, address: BeatAddress): Beat {
        val staff = score.tracks.at(address.track, "轨道").staves.at(address.staff, "谱表")
        require(!staff.isPercussion) { "请选择有音高的谱表。" }
        return staff.bars.at(address.bar, "小节").voices.at(address.voice, "声部").beats.at(address.beat, "节拍")
    }

    fun undo(): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: return false
        val change = undoHistory.peekLast() ?: return false
        val replacement = restore(change.before)
        undoHistory.removeLast()
        remember(redoHistory, Change(snapshot(current), change.description, change.edit))
        publish(replacement, "撤销：${change.description}", change.edit?.reverse())
        return true
    }

    fun redo(): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: return false
        val change = redoHistory.peekLast() ?: return false
        val replacement = restore(change.before)
        redoHistory.removeLast()
        remember(undoHistory, Change(snapshot(current), change.description, change.edit))
        publish(replacement, "重做：${change.description}", change.edit)
        return true
    }

    private fun <T> alphaTab.collections.List<T>.at(index: Int, label: String): T {
        require(index >= 0 && index < length.toInt()) { "$label 超出当前乐谱范围。" }
        return this[index]
    }

    private fun resolveNote(score: Score, address: NoteAddress): Pair<Staff, Note> {
        val staff = score.tracks.at(address.track, "轨道").staves.at(address.staff, "谱表")
        require(!staff.isPercussion) { "打击乐谱表不能使用品位编辑。" }
        val beat = staff.bars.at(address.bar, "小节").voices.at(address.voice, "声部").beats.at(address.beat, "节拍")
        require(!beat.isRest && !beat.isEmpty && beat.notes.length > 0) { "当前节拍是空拍或休止，不能修改品位。" }
        return staff to beat.notes.at(address.note, "音符序号")
    }

    private fun requireEditable(staff: Staff, note: Note) {
        require(!staff.isPercussion && !note.isPercussion) { "打击乐不能使用品位编辑。" }
        require(note.isStringed && note.string >= 1 && note.string <= staff.tuning.length && note.fret >= 0) {
            "当前音符没有可编辑的弦号与品位。"
        }
    }

    fun show() {
        synchronizeSession()
        if (scoreProvider() == null) { notice("请先打开乐谱。"); return }
        val actions = mutableListOf("曲名与作者", "已有音符品位")
        if (canUndo) actions.add("撤销")
        if (canRedo) actions.add("重做")
        AlertDialog.Builder(context).setTitle("基础编辑")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "曲名与作者" -> showMetadataDialog()
                    "已有音符品位" -> showNoteDialog()
                    "撤销" -> undo()
                    "重做" -> redo()
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun form() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    private fun field(label: String, value: String, numeric: Boolean = false): EditText = EditText(context).apply {
        hint = label; contentDescription = label; setText(value); setSingleLine()
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }
    private fun addLabel(form: LinearLayout, label: String) {
        form.addView(TextView(context).apply { text = label; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
    }
    private fun notice(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private fun showMetadataDialog() {
        val score = scoreProvider() ?: return
        val form = form()
        addLabel(form, "曲名")
        val title = field("曲名", score.title); form.addView(title)
        addLabel(form, "作者 / 艺术家")
        val artist = field("作者 / 艺术家", score.artist); form.addView(artist)
        val dialog = AlertDialog.Builder(context).setTitle("曲名与作者").setView(form)
            .setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { updateMetadata(title.text.toString(), artist.text.toString()); dialog.dismiss() }
                catch (error: IllegalArgumentException) { notice(error.message ?: "无法修改。") }
            }
        }
        dialog.show()
    }

    private fun showNoteDialog() {
        val score = scoreProvider() ?: return
        val form = form()
        val labels = arrayOf("轨道", "谱表", "小节", "声部", "节拍", "音符序号")
        val selectors = Array(6) { Spinner(context) }
        for (index in selectors.indices) {
            addLabel(form, labels[index]); selectors[index].contentDescription = labels[index]
            form.addView(selectors[index], LinearLayout.LayoutParams(-1, dp(46)))
        }
        addLabel(form, "品位（0–$MAX_FRET）")
        val fret = field("品位", "0", true); form.addView(fret)
        val description = TextView(context).apply { textSize = 12f; setPadding(0, dp(10), 0, dp(8)) }
        form.addView(description)
        val scroll = ScrollView(context).apply { addView(form) }
        val dialog = AlertDialog.Builder(context).setTitle("修改已有音符品位").setView(scroll)
            .setPositiveButton("应用", null).setNegativeButton("取消", null).create()

        fun address() = NoteAddress(
            selectors[0].selectedItemPosition, selectors[1].selectedItemPosition,
            selectors[2].selectedItemPosition, selectors[3].selectedItemPosition,
            selectors[4].selectedItemPosition, selectors[5].selectedItemPosition
        )
        fun selectedTrack() = score.tracks.toList().getOrNull(selectors[0].selectedItemPosition.coerceAtLeast(0))
        fun selectedStaff() = selectedTrack()?.staves?.toList()?.getOrNull(selectors[1].selectedItemPosition.coerceAtLeast(0))
        fun selectedBar() = selectedStaff()?.bars?.toList()?.getOrNull(selectors[2].selectedItemPosition.coerceAtLeast(0))
        fun selectedVoice() = selectedBar()?.voices?.toList()?.getOrNull(selectors[3].selectedItemPosition.coerceAtLeast(0))
        fun selectedBeat() = selectedVoice()?.beats?.toList()?.getOrNull(selectors[4].selectedItemPosition.coerceAtLeast(0))

        fun refreshDescription() {
            try {
                val (staff, note) = resolveNote(score, address())
                requireEditable(staff, note)
                fret.setText(note.fret.toInt().toString())
                val stringNumber = (staff.tuning.length - note.string + 1).toInt()
                description.text = "当前第 $stringNumber 弦，${note.fret.toInt()} 品。同一延音链会一并修改；节奏保持原样。修改只影响谱面和合成播放，内嵌录音保持原样。"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            } catch (error: IllegalArgumentException) {
                description.text = error.message
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            }
        }

        fun refreshFrom(start: Int) {
            for (level in start..5) {
                val items = when (level) {
                    0 -> score.tracks.toList().mapIndexed { i, item -> "${i + 1} · ${item.name.ifBlank { "轨道" }}" }
                    1 -> selectedTrack()?.staves?.toList()?.mapIndexed { i, item -> "${i + 1}${if (item.isPercussion) " · 打击乐" else ""}" } ?: emptyList()
                    2 -> selectedStaff()?.bars?.toList()?.mapIndexed { i, _ -> "第 ${i + 1} 小节" } ?: emptyList()
                    3 -> selectedBar()?.voices?.toList()?.mapIndexed { i, _ -> "声部 ${i + 1}" } ?: emptyList()
                    4 -> selectedVoice()?.beats?.toList()?.mapIndexed { i, item -> "${i + 1}${if (item.isRest || item.isEmpty) " · 休止 / 空拍" else ""}" } ?: emptyList()
                    else -> selectedBeat()?.notes?.toList()?.mapIndexed { i, item -> "${i + 1} · 第 ${((selectedStaff()?.tuning?.length ?: 0.0) - item.string + 1).toInt()} 弦 / ${item.fret.toInt()} 品" } ?: emptyList()
                }
                selectors[level].isEnabled = items.isNotEmpty()
                selectors[level].adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                    if (items.isEmpty()) listOf("无可选项目") else items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                selectors[level].setSelection(0, false)
            }
            refreshDescription()
        }

        dialog.setOnShowListener {
            selectors.forEachIndexed { index, spinner ->
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (index < 5) refreshFrom(index + 1) else refreshDescription()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            refreshFrom(0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = fret.text.toString().toIntOrNull()
                if (value == null) { notice("请输入有效品位。"); return@setOnClickListener }
                try { updateFret(address(), value); dialog.dismiss() }
                catch (error: IllegalArgumentException) { notice(error.message ?: "无法修改。") }
            }
        }
        dialog.show()
    }

    companion object {
        internal const val MAX_CLIPBOARD_BARS = 16
        internal val EDITABLE_DURATIONS = listOf(Duration.Whole, Duration.Half, Duration.Quarter, Duration.Eighth,
            Duration.Sixteenth, Duration.ThirtySecond, Duration.SixtyFourth)
        const val HISTORY_LIMIT = 20
        const val MAX_FRET = 36
        fun writtenPitch(note: Note) = (note.octave * 12 + note.tone).toInt()
        private val INPUT_DYNAMICS = listOf(DynamicValue.PPP, DynamicValue.PP, DynamicValue.P, DynamicValue.MP,
            DynamicValue.MF, DynamicValue.F, DynamicValue.FF, DynamicValue.FFF)
        private const val MAX_TEXT_LENGTH = 1024
        private const val RECORDING_HISTORY_BYTES = 48 * 1024 * 1024
    }
}
