@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.model.BackingTrack
import alphaTab.model.HarmonicType
import alphaTab.model.JsonConverter
import alphaTab.model.Note
import alphaTab.model.Score
import alphaTab.model.Staff
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
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Edits existing notation without changing its rhythmic structure. */
class BasicScoreEditor(
    private val context: Context,
    private val scoreProvider: () -> Score?,
    private val onChanged: (Score, String) -> Unit,
    private val settingsProvider: () -> Settings = { Settings() }
) {
    /** All indices are zero based; the dialog presents them as one based. */
    data class NoteAddress(val track: Int, val staff: Int, val bar: Int, val voice: Int, val beat: Int, val note: Int)

    // The JSON object contains all serializable document data. Audio is deliberately
    // held by reference once: alphaTab's BackingTrackSerializer omits its raw bytes.
    private data class Snapshot(
        val json: alphaTab.collections.Map<String, Any?>?,
        val hadBackingTrack: Boolean,
        val audio: Uint8Array?
    )
    private data class Change(val before: Snapshot, val description: String)
    private val undoHistory = ArrayDeque<Change>()
    private val redoHistory = ArrayDeque<Change>()
    private var boundScore: Score? = null
    private var externalBefore: Snapshot? = null

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
    }

    private fun synchronizeSession() {
        val current = scoreProvider()
        if (current !== boundScore) reset(current)
    }

    private fun snapshot(score: Score) = Snapshot(
        JsonConverter.scoreToJsObject(score), score.backingTrack != null, score.backingTrack?.rawAudioFile
    )

    private fun restore(snapshot: Snapshot): Score {
        val result = JsonConverter.jsObjectToScore(snapshot.json, settingsProvider())
        result.backingTrack = if (snapshot.hadBackingTrack) (result.backingTrack ?: BackingTrack()).apply {
            rawAudioFile = snapshot.audio
        } else null
        return result
    }

    private fun publish(score: Score, description: String) {
        boundScore = score
        onChanged(score, description)
    }

    private fun remember(history: ArrayDeque<Change>, change: Change) {
        history.addLast(change)
        while (history.size > HISTORY_LIMIT) history.removeFirst()
    }

    private fun mutate(description: String, change: (Score) -> Boolean): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: throw IllegalStateException("请先打开乐谱。")
        val before = snapshot(current)
        val candidate = restore(before)
        if (!change(candidate)) return false
        // Round-trip once more so finish() resolves derived pitch, ties and cached
        // lookups from clean objects rather than calling finish twice on one model.
        val finished = restore(snapshot(candidate))
        remember(undoHistory, Change(before, description))
        redoHistory.clear()
        publish(finished, description)
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

    /** Modifies a fretted note and its tie chain, keeping every beat's duration. */
    fun updateFret(address: NoteAddress, fret: Int): Boolean = mutate("修改音符品位") { score ->
        require(fret in 0..MAX_FRET) { "品位请输入 0–$MAX_FRET。" }
        val (staff, note) = resolveNote(score, address)
        requireEditable(staff, note)
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
            requireEditable(item.beat.voice.bar.staff, item)
            chain.add(item)
            next = item.tieDestination
        }
        if (chain.all { it.fret == fret.toDouble() }) false
        else { chain.forEach { it.fret = fret.toDouble() }; true }
    }

    fun undo(): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: return false
        val change = undoHistory.peekLast() ?: return false
        val replacement = restore(change.before)
        undoHistory.removeLast()
        remember(redoHistory, Change(snapshot(current), change.description))
        publish(replacement, "撤销：${change.description}")
        return true
    }

    fun redo(): Boolean {
        synchronizeSession()
        val current = scoreProvider() ?: return false
        val change = redoHistory.peekLast() ?: return false
        val replacement = restore(change.before)
        redoHistory.removeLast()
        remember(undoHistory, Change(snapshot(current), change.description))
        publish(replacement, "重做：${change.description}")
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
        require(note.harmonicType != HarmonicType.Natural) { "自然泛音暂不支持基础品位编辑。" }
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
        const val HISTORY_LIMIT = 20
        const val MAX_FRET = 36
        private const val MAX_TEXT_LENGTH = 1024
    }
}
