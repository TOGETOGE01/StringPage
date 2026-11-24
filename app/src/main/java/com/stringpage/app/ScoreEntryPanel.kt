@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.model.Beat
import alphaTab.model.MasterBar
import alphaTab.model.Note
import alphaTab.model.Score
import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

internal class ScoreEntryPanel(
    private val activity: Activity,
    private val editor: BasicScoreEditor,
    private val score: () -> Score?,
    private val activeTrack: () -> Int,
    private val currentBar: () -> Int,
    private val chooseMidi: () -> Unit,
    private val clearMidiGesture: () -> Unit,
    private val exit: () -> Unit,
    private val selectionChanged: () -> Unit = {}
) {
    internal data class Selection(val beat: BasicScoreEditor.BeatAddress, val string: Int, val note: Int? = null)
    private var selected: Selection? = null
    private var ready = false
    private var midiPort: String? = null
    private var finishedMidiEntry = false
    private val midiQueue = ArrayDeque<List<MidiAttack>>()
    private val label = text("点音符或品位数字可编辑", 13f)
    private val stayOnBeat = CheckBox(activity).apply {
        text = "留在本拍"
        setOnCheckedChangeListener { _, _ -> cancelMidiInput() }
    }
    private val undo = button("撤销") { edit { editor.undo() } }
    private val redo = button("重做") { edit { editor.redo() } }
    private val previous = button("上一拍") { move(-1) }
    private val next = button("下一拍") { move(1) }
    private val voice = button("声部") { chooseVoice() }
    private val string = button("选弦") { selectString() }
    private val fret = button("输入品位", primary = true) { enterFret() }
    private val rhythm = button("时值") { chooseDuration() }
    private val technique = button("技法") { chooseTechnique() }
    private val remove = button("删音符") { selectedNoteAddress()?.let { edit { editor.removeNote(it) } } }
    private val rest = button("休止") { selected?.let { current -> edit { editor.clearBeat(current.beat) } } }
    private val copy = button("复制小节") { copyBars() }
    private val paste = button("粘贴") { pasteBars() }
    private val structure = button("乐谱设置") { showStructure() }
    private val midi = button("MIDI", action = chooseMidi)
    val view = column().apply {
        visibility = View.GONE
        setPadding(dp(10), dp(5), dp(10), dp(6))
        setBackgroundColor(AppUi.panel)
        addView(row().apply {
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            addView(structure)
            addView(button("完成") { exit() })
        })
        addView(scrollingRow(voice, string, fret, rhythm, technique, remove, rest))
        addView(scrollingRow(previous, next, midi, stayOnBeat, undo, redo, copy, paste))
    }

    val isOpen get() = view.visibility == View.VISIBLE

    fun open() { view.visibility = View.VISIBLE; refresh() }

    internal fun saveSelection() = selected

    internal fun resolvedSelection(): Pair<Beat, Note?>? = selected?.let { current ->
        resolve(current.beat)?.let { it to selectedNote(current) }
    }

    internal fun restoreSelection(selection: Selection?) {
        selected = selection
        refresh()
    }

    fun close() {
        view.visibility = View.GONE
        selected = null
        cancelMidiInput()
        selectionChanged()
    }

    fun resetSelection() { selected = null; cancelMidiInput(); refresh() }

    fun applyBarEdit(edit: ScoreStructure.BarEdit) {
        cancelMidiInput()
        val selection = selected ?: return
        selected = edit.map(selection.beat.bar)?.let { selection.copy(beat = selection.beat.copy(bar = it)) }
    }

    fun applyTrackEdit(edit: ScoreStructure.TrackEdit) {
        cancelMidiInput()
        val selection = selected ?: return
        selected = edit.map(selection.beat.track)?.let { selection.copy(beat = selection.beat.copy(track = it)) }
    }

    fun setReady(value: Boolean) {
        ready = value
        if (isOpen) {
            refresh()
            if (value && midiQueue.isNotEmpty()) view.post(::drainMidi)
        }
    }

    fun cancelMidiInput() {
        midiQueue.clear()
        finishedMidiEntry = false
        clearMidiGesture()
    }

    fun setMidiPort(name: String?) {
        midiPort = name
        cancelMidiInput()
        midi.text = if (name == null) "MIDI" else "MIDI 已连接"
        midi.contentDescription = if (name == null) "选择 MIDI 输入" else "MIDI 已连接，$name"
    }

    fun offerMidi(chord: List<MidiAttack>) {
        if (!isOpen || midiPort == null || finishedMidiEntry || !view.hasWindowFocus()) return
        if (midiQueue.size >= 24) {
            message("录入暂忙，请稍候再弹。")
            return
        }
        midiQueue.addLast(chord)
        drainMidi()
    }

    private fun drainMidi() {
        if (!ready || !isOpen || midiQueue.isEmpty() || midiPort == null || !view.hasWindowFocus()) return
        if (selected == null) {
            val staff = currentStaff()
            val bar = staff?.bars?.toList()?.getOrNull(currentBar().coerceAtLeast(0))
            val beat = bar?.voices?.firstOrNull()?.beats?.firstOrNull()
            if (beat != null) select(beat, byMidi = true)
        }
        val location = selected
        if (location == null) {
            midiQueue.clear()
            message("请选择要录入的拍。")
            return
        }
        val chord = midiQueue.removeFirst()
        if (!edit { editor.putMidiChord(location.beat, chord, location.string) }) {
            midiQueue.clear()
            return
        }
        if (!stayOnBeat.isChecked && !move(1, byMidi = true)) {
            midiQueue.clear()
            finishedMidiEntry = true
            message("已到声部末尾，可插入小节或选择另一拍。")
        } else if (ready && midiQueue.isNotEmpty()) view.post(::drainMidi)
    }

    fun tap(beat: Beat, note: Note?) {
        if (!ready || !select(beat, note) || note == null) return
        if (note.isStringed) enterFret(direct = true) else enterPitch()
    }

    private fun select(beat: Beat, note: Note? = null, keepString: Boolean = false, byMidi: Boolean = false): Boolean {
        if (!isOpen || beat.voice.bar.staff.track.score !== score()) return false
        if (!byMidi) cancelMidiInput()
        val bar = beat.voice.bar
        val staff = bar.staff
        if (staff.isPercussion) {
            resetSelection()
            label.text = "请选择有音高的谱表"
            return false
        }
        val address = BasicScoreEditor.BeatAddress(staff.track.index.toInt(), staff.index.toInt(), bar.index.toInt(),
            beat.voice.index.toInt(), beat.index.toInt())
        val hit = note?.takeIf { it.isStringed } ?: beat.notes.firstOrNull { it.isStringed }
        val stringNumber = if (keepString) selected?.string ?: staff.tuning.length.toInt()
        else hit?.string?.toInt() ?: selected?.string ?: staff.tuning.length.toInt()
        val count = staff.tuning.length.toInt()
        val noteIndex = note?.let { beat.notes.toList().indexOf(it).takeIf { index -> index >= 0 } }
        selected = Selection(address, if (count == 0) 0 else stringNumber.coerceIn(1, count), noteIndex)
        refresh()
        return true
    }

    fun refresh() {
        val current = selected
        val beat = current?.let { resolve(it.beat) }
        val stringed = (current?.string ?: 0) > 0
        val note = current?.let(::selectedNote)
        if (beat == null) {
            selected = null
            label.text = "点音符或品位数字可编辑"
            voice.text = "声部"
        } else {
            val lane = current.beat.voice + 1
            val suffix = if (stringed) {
                val number = beat.voice.bar.staff.tuning.length.toInt() - current.string + 1
                val fret = note?.let { "${it.fret.toInt()} 品" } ?: "空弦位"
                string.text = "第 $number 弦"
                "$number 弦 · $fret"
            } else if (note != null) pitchLabel(BasicScoreEditor.writtenPitch(note))
            else if (beat.notes.length > 0) "${beat.notes.length.toInt()} 个音符" else "休止"
            label.text = "第 ${current.beat.bar + 1} 小节 · 声部 $lane · 第 ${current.beat.beat + 1} 拍 · $suffix"
            voice.text = "声部 $lane"
        }
        for (button in listOf(previous, next)) enable(button, ready && beat != null)
        for (button in listOf(fret, string)) enable(button, ready && beat != null && stringed)
        enable(rhythm, ready && beat?.isEmpty == false)
        for (button in listOf(technique, remove)) enable(button, ready && note != null)
        enable(rest, ready && (beat?.notes?.length ?: 0.0) > 0)
        enable(undo, ready && editor.canUndo)
        enable(redo, ready && editor.canRedo)
        enable(structure, ready && score() != null)
        enable(midi, score() != null)
        enable(copy, ready && beat != null)
        enable(paste, ready && beat != null && editor.hasClipboard)
        enable(voice, ready && currentStaff()?.isPercussion == false)
        selectionChanged()
    }

    private fun currentStaff(): alphaTab.model.Staff? {
        val address = selected?.beat
        return score()?.tracks?.toList()?.getOrNull(address?.track ?: activeTrack())
            ?.staves?.toList()?.getOrNull(address?.staff ?: 0)
    }

    private fun chooseVoice() {
        val staff = currentStaff() ?: return
        val current = selected
        val bar = (current?.beat?.bar ?: currentBar()).coerceIn(0, staff.bars.length.toInt() - 1)
        val index = staff.track.index.toInt()
        val staffIndex = staff.index.toInt()
        val measure = staff.bars[bar]
        val at = current?.let { resolve(it.beat)?.playbackStart } ?: 0.0
        val options = (0 until ScoreStructure.MAX_VOICES).map { lane ->
            val inactive = lane >= measure.voices.length || measure.voices[lane].isEmpty
            "第 ${lane + 1} 声部" + if (inactive) "（启用）" else ""
        }
        AlertDialog.Builder(activity).setTitle("第 ${bar + 1} 小节的声部")
            .setSingleChoiceItems(options.toTypedArray(), current?.beat?.voice ?: 0) { dialog, lane ->
                if (lane < measure.voices.length && !measure.voices[lane].isEmpty ||
                    edit { editor.enableVoice(index, staffIndex, bar, lane) }) {
                    val beats = score()?.tracks?.get(index)?.staves?.get(staffIndex)?.bars?.get(bar)
                        ?.voices?.get(lane)?.beats?.toList().orEmpty()
                    val beat = beats.lastOrNull { it.playbackStart <= at } ?: beats.firstOrNull()
                    if (beat != null) select(beat, keepString = true)
                    dialog.dismiss()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showStructure() {
        val loaded = score() ?: return
        val address = selected?.beat
        val barCount = loaded.masterBars.length.toInt()
        val bar = (address?.bar ?: currentBar()).coerceIn(0, barCount - 1)
        val track = address?.track ?: activeTrack()
        val staff = address?.staff ?: 0
        val currentStaff = loaded.tracks[track].staves[staff]
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "在第 ${bar + 1} 小节前插入" to { edit { editor.insertBar(bar) } },
            "在第 ${bar + 1} 小节后插入" to { edit { editor.insertBar(bar + 1) } },
            "在曲末添加小节" to { edit { editor.appendBar() } },
            "添加轨道" to { chooseInstrument() },
            "第 ${bar + 1} 小节速度" to { chooseBarTempo(loaded, bar) },
            "第 ${bar + 1} 小节反复记号" to { chooseRepeatMarks(loaded.masterBars[bar], bar) }
        )
        if (barCount > 1) actions.add("删除第 ${bar + 1} 小节" to { confirmDeleteBar(loaded, bar) })
        if (loaded.tracks.length > 1) actions.add("删除当前轨道" to { confirmDeleteTrack(loaded, track) })
        if (!currentStaff.isPercussion && currentStaff.tuning.length > 0) {
            actions.add("调弦与变调夹" to { chooseTuning(track, staff, currentStaff) })
        }
        AlertDialog.Builder(activity).setTitle("乐谱设置")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmDeleteTrack(score: Score, index: Int) {
        val name = score.tracks[index].name.ifBlank { "轨道 ${index + 1}" }
        AlertDialog.Builder(activity).setTitle("删除「$name」？")
            .setMessage("将删除这条轨道的谱面和混音设置。可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> edit { editor.deleteTrack(index) } }.show()
    }

    private fun confirmDeleteBar(score: Score, bar: Int) {
        val syncCount = score.masterBars[bar].syncPoints?.length?.toInt() ?: 0
        val confirmation = buildString {
            append("将从所有轨道删除这一小节。可撤销。")
            if (syncCount > 0) append("\n内嵌录音保留，此小节上的 $syncCount 个同步点会移除。")
        }
        AlertDialog.Builder(activity).setTitle("删除第 ${bar + 1} 小节？")
            .setMessage(confirmation).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> edit { editor.deleteBar(bar) } }.show()
    }

    private fun chooseInstrument() {
        val instruments = ScoreStructure.Instrument.entries
        AlertDialog.Builder(activity).setTitle("添加轨道")
            .setItems(instruments.map { it.label }.toTypedArray()) { _, index ->
                edit { editor.addTrack(instruments[index]) }
            }.setNegativeButton("取消", null).show()
    }

    private fun chooseBarTempo(loaded: Score, bar: Int) {
        val current = loaded.masterBars[bar].tempoAutomations.firstOrNull { it.ratioPosition == 0.0 }?.value
            ?: loaded.masterBars.toList().take(bar).asReversed()
                .firstNotNullOfOrNull { it.tempoAutomations.maxByOrNull { event -> event.ratioPosition }?.value }
            ?: loaded.tempo
        val bpm = numberField(current.toInt(), "BPM")
        numberDialog("第 ${bar + 1} 小节速度", "设置小节起点的速度（20–400 BPM）", listOf(bpm)) {
            val value = bpm.text.toString().toIntOrNull()
            require(value != null) { "请输入 BPM。" }
            editor.setBarTempo(bar, value)
        }
    }

    private fun chooseRepeatMarks(measure: MasterBar, bar: Int) {
        val repeat = ScoreStructure.RepeatMarks(measure)
        val start = CheckBox(activity).apply {
            text = "从这里开始反复"
            isChecked = repeat.start
        }
        val passes = numberField(repeat.count, "演奏遍数")
        val endings = (1..MasterBar.MaxAlternateEndings.toInt()).map { number ->
            CheckBox(activity).apply {
                text = "第 $number 房"
                isChecked = (repeat.endings and (1 shl (number - 1))) != 0
            }
        }
        val form = column().apply {
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(6), dp(20), dp(12))
            addView(start)
            addView(text("在结束小节填演奏遍数，填 0 可移除结束记号", 13f))
            addView(passes)
            addView(text("指定在哪几遍演奏这一小节", 13f))
            for (pair in endings.chunked(2)) addView(row().apply {
                for (choice in pair) addView(choice, LinearLayout.LayoutParams(0, dp(42), 1f))
            })
        }
        val dialog = AlertDialog.Builder(activity).setTitle("第 ${bar + 1} 小节反复记号")
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (edit {
                    val count = requireNotNull(passes.text.toString().toIntOrNull()) { "请输入演奏遍数。" }
                    val mask = endings.withIndex().sumOf { (index, choice) -> if (choice.isChecked) 1 shl index else 0 }
                    editor.setRepeatMarks(bar, ScoreStructure.RepeatMarks(start.isChecked, count, mask))
                }) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun chooseTuning(track: Int, staff: Int, current: alphaTab.model.Staff) {
        val strings = current.tuning.mapIndexed { index, pitch -> numberField(pitch.toInt(), "第 ${index + 1} 弦") }
        val capo = numberField(current.capo.toInt(), "变调夹品位")
        numberDialog("调弦与变调夹", "调弦以 MIDI 音高输入，中音 C 为 60。变调夹填 0 表示移除。", strings + capo) {
            val tuning = strings.map { requireNotNull(it.text.toString().toIntOrNull()) { "请填写每根弦的音高。" } }
            val capoFret = requireNotNull(capo.text.toString().toIntOrNull()) { "请填写变调夹品位。" }
            editor.setTuning(track, staff, tuning, capoFret)
        }
    }

    private fun numberField(value: Int, hint: String) = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        setSelectAllOnFocus(true)
        this.hint = hint
    }

    private fun numberDialog(title: String, instructions: String, fields: List<EditText>, change: () -> Boolean) {
        val form = column().apply {
            setPadding(dp(20), dp(6), dp(20), dp(12))
            isFocusableInTouchMode = true
            addView(text(instructions, 14f))
            for (field in fields) {
                val label = text(field.hint.toString(), 13f)
                field.id = View.generateViewId()
                label.labelFor = field.id
                addView(label)
                addView(field)
            }
        }
        val scroll = ScrollView(activity).apply { addView(form) }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (edit(change)) dialog.dismiss() }
        }
        dialog.show()
    }

    private fun copyBars() {
        val address = selected?.beat ?: return
        val barCount = resolve(address)?.voice?.bar?.staff?.bars?.length?.toInt() ?: return
        val lengths = (1..minOf(BasicScoreEditor.MAX_CLIPBOARD_BARS, barCount - address.bar)).toList()
        AlertDialog.Builder(activity).setTitle("从第 ${address.bar + 1} 小节开始复制")
            .setItems(lengths.map { "$it 个小节" }.toTypedArray()) { _, index ->
                if (edit { editor.copyBars(address, lengths[index]); true }) message("已复制 ${lengths[index]} 个小节。")
            }.setNegativeButton("取消", null).show()
    }

    private fun pasteBars() {
        val address = selected?.beat ?: return
        AlertDialog.Builder(activity).setTitle("从第 ${address.bar + 1} 小节开始粘贴？")
            .setMessage("会替换目标谱表中对应的节拍声部，可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("粘贴") { _, _ -> edit { editor.pasteBars(address) } }.show()
    }

    private fun selectString() {
        val current = selected ?: return
        val count = resolve(current.beat)?.voice?.bar?.staff?.tuning?.length?.toInt() ?: return
        AlertDialog.Builder(activity).setTitle("选择琴弦").setSingleChoiceItems(
            (1..count).map { "第 $it 弦" }.toTypedArray(), count - current.string
        ) { dialog, index ->
            selected = current.copy(string = count - index, note = null)
            refresh()
            dialog.dismiss()
        }.setNegativeButton("取消", null).show()
    }

    private fun enterFret(initialDigit: String? = null, direct: Boolean = false) {
        val current = selected ?: return
        if (current.string <= 0) return
        val note = selectedNote(current)
        val target = selectedNoteAddress()
        var value = initialDigit ?: note?.fret?.toInt()?.toString().orEmpty()
        var replace = initialDigit == null
        val readout = text(value.ifEmpty { "—" }, 24f).apply { gravity = Gravity.CENTER; minHeight = dp(46) }
        val keypad = column().apply { setPadding(dp(15), 0, dp(15), dp(10)); addView(readout) }
        fun digit(cap: String) {
            value = when {
                cap == "回删" -> value.dropLast(1)
                replace -> cap
                else -> (value + cap).take(2)
            }
            replace = false
            readout.text = value.ifEmpty { "—" }
        }
        for (caps in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("0", "回删"))) {
            keypad.addView(row().apply {
                for (cap in caps) addView(button(cap) { digit(cap) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(4); bottomMargin = dp(4)
                })
            })
        }
        val stringNumber = resolve(current.beat)?.voice?.bar?.staff?.tuning?.length?.toInt()?.minus(current.string)?.plus(1)
        val builder = AlertDialog.Builder(activity).setTitle(if (direct) "编辑品位 · 第 $stringNumber 弦" else "输入品位")
            .setView(keypad).setNegativeButton("取消", null).setPositiveButton(if (direct) "保存" else "写入", null)
        if (target != null) builder.setNeutralButton("技法", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            if (target != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { chooseTechnique() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = value.toIntOrNull()
                if (number == null || number !in 0..BasicScoreEditor.MAX_FRET) {
                    message("品位请输入 0–${BasicScoreEditor.MAX_FRET}。")
                } else if (edit {
                    if (target == null) editor.putFret(current.beat, current.string, number)
                    else editor.updateFret(target, number)
                }) {
                    dialog.dismiss()
                    if (!direct && !stayOnBeat.isChecked) move(1)
                }
            }
        }
        dialog.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when {
                key == KeyEvent.KEYCODE_DEL -> { digit("回删"); true }
                key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true
                }
                event.unicodeChar.toChar() in '0'..'9' -> { digit(event.unicodeChar.toChar().toString()); true }
                else -> false
            }
        }
        dialog.show()
    }

    private fun enterPitch() {
        val current = selected ?: return
        val target = selectedNoteAddress() ?: return
        val note = selectedNote(current) ?: return
        var pitch = BasicScoreEditor.writtenPitch(note)
        val readout = text(pitchLabel(pitch), 26f).apply { gravity = Gravity.CENTER; minHeight = dp(54) }
        val form = column().apply { setPadding(dp(16), 0, dp(16), dp(8)); addView(readout) }
        for ((down, up, step) in listOf(Triple("降半音", "升半音", 1), Triple("降八度", "升八度", 12))) {
            form.addView(row().apply {
                for ((caption, offset) in listOf(down to -step, up to step)) {
                    addView(button(caption) {
                        pitch = (pitch + offset).coerceIn(0, 127)
                        readout.text = pitchLabel(pitch)
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
                }
            })
        }
        val dialog = AlertDialog.Builder(activity).setTitle("编辑音高").setView(form)
            .setNeutralButton("技法", null)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { chooseTechnique() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (edit { editor.updatePitch(target, pitch) }) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun pitchLabel(pitch: Int): String {
        val names = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        return "${names[pitch.mod(12)]}${Math.floorDiv(pitch, 12) - 1}"
    }

    private fun chooseDuration() {
        val current = selected ?: return
        val durations = BasicScoreEditor.EDITABLE_DURATIONS
        val names = arrayOf("全音符", "二分音符", "四分音符", "八分音符", "十六分音符", "三十二分音符", "六十四分音符")
        AlertDialog.Builder(activity).setTitle("节拍时值")
            .setSingleChoiceItems(names, durations.indexOf(resolve(current.beat)?.duration)) { dialog, index ->
                edit { editor.updateDuration(current.beat, durations[index]) }
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun chooseTechnique() {
        val current = selected ?: return
        val note = selectedNote(current) ?: return
        val target = selectedNoteAddress() ?: return
        val options = BasicScoreEditor.Technique.entries.filter { it.supports(note) }.groupBy { it.group }
        val rows = buildList<Pair<String, BasicScoreEditor.Technique?>> {
            for ((group, techniques) in options) {
                add(group.label to null)
                for (item in techniques) {
                    add((if (ScoreTechniques.isApplied(note, item)) "${item.label} · 已应用" else item.label) to item)
                }
            }
        }
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, rows.map { it.first }) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position].second != null
        }
        val dialog = AlertDialog.Builder(activity).setTitle("音符技法").setAdapter(adapter, null)
            .setNegativeButton("返回", null).create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, index, _ ->
                val technique = rows[index].second
                if (technique != null && edit { editor.toggleTechnique(target, technique) }) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun selectedNote(current: Selection): Note? {
        val notes = resolve(current.beat)?.notes?.toList() ?: return null
        val indexed = current.note?.let { notes.getOrNull(it) }
        return if (current.string > 0) {
            indexed?.takeIf { it.string.toInt() == current.string }
                ?: notes.firstOrNull { it.string.toInt() == current.string }
        } else indexed?.takeUnless { it.isStringed }
    }

    private fun selectedNoteAddress(): BasicScoreEditor.NoteAddress? {
        val current = selected ?: return null
        val note = selectedNote(current) ?: return null
        val beat = current.beat
        val index = note.beat.notes.toList().indexOf(note).takeIf { it >= 0 } ?: return null
        return BasicScoreEditor.NoteAddress(beat.track, beat.staff, beat.bar, beat.voice, beat.beat, index)
    }

    private fun move(step: Int, byMidi: Boolean = false): Boolean {
        val current = selected ?: return false
        val beat = resolve(current.beat) ?: return false
        val address = current.beat.copy(beat = current.beat.beat + step)
        val adjacent = resolve(address) ?: run {
            val otherBar = current.beat.bar + step
            val voice = beat.voice.bar.staff.bars.toList().getOrNull(otherBar)?.voices?.toList()?.getOrNull(address.voice)
                ?: return false
            resolve(address.copy(bar = otherBar, beat = if (step > 0) 0 else voice.beats.length.toInt() - 1)) ?: return false
        }
        select(adjacent, keepString = true, byMidi = byMidi)
        return true
    }

    fun handleKey(key: Int, event: KeyEvent): Boolean {
        if (!isOpen || !ready || event.repeatCount != 0) return false
        when {
            event.isCtrlPressed && key == KeyEvent.KEYCODE_Z -> edit { if (event.isShiftPressed) editor.redo() else editor.undo() }
            event.isCtrlPressed && key == KeyEvent.KEYCODE_Y -> edit { editor.redo() }
            selected == null || event.isCtrlPressed || event.isAltPressed -> return false
            key == KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
            key == KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
            key == KeyEvent.KEYCODE_DEL -> selectedNoteAddress()?.let { edit { editor.removeNote(it) } }
            selected?.string == 0 -> if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) enterPitch() else return false
            key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER -> enterFret()
            event.unicodeChar.toChar() in '0'..'9' -> enterFret(event.unicodeChar.toChar().toString())
            else -> return false
        }
        return true
    }

    private fun resolve(address: BasicScoreEditor.BeatAddress): Beat? = runCatching {
        score()?.tracks?.get(address.track)?.staves?.get(address.staff)?.bars?.get(address.bar)
            ?.voices?.get(address.voice)?.beats?.get(address.beat)
    }.getOrNull()

    private fun edit(change: () -> Boolean): Boolean {
        if (!ready) { message("乐谱正在更新，请稍候。"); return false }
        return try { change(); refresh(); true }
        catch (error: IllegalArgumentException) { message(error.message ?: "无法修改这个音符。"); false }
    }

    private fun enable(button: Button, enabled: Boolean) { button.isEnabled = enabled; button.alpha = if (enabled) 1f else 0.42f }
    private fun scrollingRow(vararg controls: View) = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = true
        addView(row().apply {
            for ((index, control) in controls.withIndex()) addView(control, LinearLayout.LayoutParams(-2, dp(44)).apply {
                if (index > 0) marginStart = dp(5)
            })
        }, -2, -2)
    }
    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float) = TextView(activity).apply { text = value; textSize = size; setTextColor(AppUi.ink) }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 13f; isAllCaps = false; typeface = AppUi.medium
        minimumHeight = dp(44); minHeight = dp(44); minimumWidth = 0; minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(if (primary) AppUi.onAccent else AppUi.ink)
        background = AppUi.ripple(activity, if (primary) AppUi.accent else AppUi.control, 9)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun message(value: String) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int) = AppUi.dp(activity, value)
}
