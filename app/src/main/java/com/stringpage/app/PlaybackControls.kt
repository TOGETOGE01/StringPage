@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabApiBase
import alphaTab.AlphaTabView
import alphaTab.midi.MidiFile
import alphaTab.model.Score
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Practice settings for the synthesized score. Full-band recordings are never presented as stems.
 * Call reset(newScore) when accepting a new score, then reapply() from every playerReady event.
 * Mixing metadata is written to the score; practice transposition/metronome/count-in are not edits.
 */
class PlaybackControls(
    private val context: Context,
    private val apiProvider: () -> AlphaTabApiBase<AlphaTabView>,
    private val isSynthesizer: () -> Boolean,
    private val switchToSynthesizer: () -> Unit,
    private val beforeMixChange: () -> Unit = {},
    private val afterMixChange: () -> Unit = {},
    private val onMixSessionEnded: () -> Unit = {},
    private val externalPlaybackDescription: () -> String = { "声部混音、预备拍和播放移调可在按谱合成中使用。" },
    private val documentKey: () -> String? = { null },
    private val reloadTone: () -> Boolean = { false },
    private val liveGuitarTracks: () -> Set<Int> = { emptySet() },
    private val openAmp: (Int) -> Unit = {},
    private val trackSlot: (Int) -> Int = { it },
    private val onMetronomeChange: () -> Unit = {},
    private val onMetronomeUiChange: () -> Unit = {}
) {
    data class TrackMix(val volume: Double, val muted: Boolean, val solo: Boolean)
    data class Snapshot(
        val tracks: List<TrackMix>,
        val transposition: Int,
        val metronomeEnabled: Boolean,
        val metronomeVolume: Double,
        val countInEnabled: Boolean,
        val customMetronomeBpm: Int? = null,
        val metronomeRhythm: MetronomeRhythm = MetronomeRhythm.Meter
    )

    private var score: Score? = null
    private var toneMidi: PracticeToneMidi? = null
    private val tonePrefs by lazy { context.getSharedPreferences("practice-guitar-tones", Context.MODE_PRIVATE) }
    private var toneDocument: String? = null
    private val tones = mutableListOf<GuitarTone>()
    private val sessionTones = mutableMapOf<Int, GuitarTone>()
    private val tracks = mutableListOf<TrackMix>()
    private var transposition = 0
    private var metronomeEnabled = false
    private var metronomeVolume = 0.7
    private var countInEnabled = false
    private val metronome = ReaderMetronome()
    private var scoreBpmTenths = 1200
    private var metronomeTempoButton: Button? = null
    private var metronomeDialog: AlertDialog? = null
    private var dialog: AlertDialog? = null
    private var toneDialog: AlertDialog? = null
    private var openWhenReady = false
    private var updatingWidgets = false
    private data class TrackWidgets(val slider: SeekBar, val volume: TextView, val mute: CheckBox, val solo: CheckBox, val tone: Button?)
    private val widgets = mutableMapOf<Int, TrackWidgets>()

    fun reset(newScore: Score, preservePractice: Boolean = false) {
        close()
        metronome.reset()
        score = newScore
        if (!preservePractice) sessionTones.clear()
        toneMidi = PracticeToneMidi(newScore)
        toneDocument = documentKey()?.let { key ->
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        tones.clear()
        tracks.clear()
        for ((index, track) in newScore.tracks.withIndex()) {
            tracks.add(TrackMix((track.playbackInfo.volume / 16.0).coerceIn(0.0, 1.0),
                track.playbackInfo.isMute && !track.playbackInfo.isSolo, track.playbackInfo.isSolo))
            val saved = preferenceKey(index)?.let { tonePrefs.getString(it, null) }
            tones.add(if (toneMidi?.canChooseTone(index) == true)
                GuitarTone.entries.firstOrNull { it.name == saved } ?: sessionTones[trackSlot(index)] ?: GuitarTone.Original
            else GuitarTone.Original)
        }
        if (!preservePractice) {
            transposition = 0
            metronomeEnabled = false
            metronomeVolume = 0.7
            countInEnabled = false
            metronome.settings = MetronomeSettings()
        }
        metronome.volume = if (metronomeEnabled) metronomeVolume else 0.0
        updateMetronomeBpm(newScore.tempo)
    }

    fun snapshot(): Snapshot {
        val settings = metronome.settings
        return Snapshot(tracks.toList(), transposition, metronomeEnabled, metronomeVolume, countInEnabled,
            settings.bpm, settings.rhythm)
    }
    internal fun snapshotTones() = tones.toList()

    internal fun restoreSession(snapshot: Snapshot, savedTones: List<GuitarTone>) {
        transposition = snapshot.transposition
        metronomeEnabled = snapshot.metronomeEnabled
        metronomeVolume = snapshot.metronomeVolume
        countInEnabled = snapshot.countInEnabled
        metronome.settings = MetronomeSettings(snapshot.customMetronomeBpm, snapshot.metronomeRhythm)
        metronome.volume = if (metronomeEnabled) metronomeVolume else 0.0
        if (snapshot.tracks.size == tracks.size) {
            tracks.clear()
            tracks.addAll(snapshot.tracks)
        }
        for ((index, tone) in savedTones.withIndex()) if (index in tones.indices && canChooseTone(index)) {
            tones[index] = tone
            sessionTones[trackSlot(index)] = tone
        }
    }

    private fun preferenceKey(index: Int): String? {
        val document = toneDocument ?: return null
        val slot = trackSlot(index)
        return if (slot >= 0) "$document/$slot" else null
    }

    internal fun canChooseTone(index: Int) = toneMidi?.canChooseTone(index) == true

    internal fun applyPlaybackMidi(midi: MidiFile, time: PreparedScoreTime) {
        val api = apiProvider()
        if (api.score !== score) return
        val synth = isSynthesizer()
        if (synth) toneMidi?.apply(midi, api.settings, tones)
        api.tickCache?.masterBars?.let { metronome.prepare(midi, it, time, synth) }
    }

    internal fun mediaMetronome(map: MetronomeTimeMap) = metronome.clock(map)
    internal fun mediaMetronomeVolume() = metronome.volume

    internal data class MetronomeUi(val enabled: Boolean, val bpm: String, val fixed: Int?, val rhythm: MetronomeRhythm, val volume: Int)
    internal fun metronomeUi() = MetronomeUi(metronomeEnabled, metronome.settings.bpm?.toString() ?: scoreBpmLabel(),
        metronome.settings.bpm, metronome.settings.rhythm, (metronomeVolume * 100).roundToInt())

    internal fun updateMetronomeBpm(bpm: Double) {
        if (!bpm.isFinite() || bpm <= 0.0) return
        val tenths = (bpm * 10).roundToInt()
        if (tenths == scoreBpmTenths) return
        scoreBpmTenths = tenths
        if (metronome.settings.bpm == null) {
            metronomeTempoButton?.text = metronomeTempoLabel()
            onMetronomeUiChange()
        }
    }

    internal fun setMetronomeBpm(bpm: Int?): Boolean {
        if (score == null || (bpm != null && bpm !in MetronomeSettings.bpmRange)) return false
        metronome.settings = metronome.settings.copy(bpm = bpm)
        metronomeTempoButton?.text = metronomeTempoLabel()
        onMetronomeUiChange()
        return true
    }

    internal fun setMetronomeRhythm(rhythm: MetronomeRhythm): Boolean {
        if (score == null) return false
        metronome.settings = metronome.settings.copy(rhythm = rhythm)
        onMetronomeUiChange()
        return true
    }

    internal fun showTone(index: Int) {
        if (!canChooseTone(index)) return
        toneDialog?.dismiss()
        if (!isSynthesizer()) {
            toneDialog = AlertDialog.Builder(context).setTitle("吉他音色")
                .setMessage("切换至按谱合成后可选择声部音色。")
                .setPositiveButton("切换至按谱合成") { _, _ -> openWhenReady = true; switchToSynthesizer() }
                .setNegativeButton("取消", null).show()
            return
        }
        val track = score?.tracks?.get(index) ?: return
        val name = track.name.ifBlank { "声部 ${index + 1}" }
        toneDialog = AlertDialog.Builder(context).setTitle("$name · 音色")
            .setSingleChoiceItems(GuitarTone.entries.map { it.label }.toTypedArray(), tones[index].ordinal) { owner, choice ->
                owner.dismiss()
                if (!setTone(index, GuitarTone.entries[choice])) {
                    Toast.makeText(context, "播放正在准备，请稍后切换音色。", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    internal fun setTone(index: Int, next: GuitarTone): Boolean {
        if (!isSynthesizer() || !canChooseTone(index)) return false
        val previous = tones[index]
        if (next == previous) return true
        tones[index] = next
        if (!reloadTone()) {
            tones[index] = previous
            return false
        }
        sessionTones[trackSlot(index)] = next
        preferenceKey(index)?.let { key ->
            tonePrefs.edit().apply {
                if (next == GuitarTone.Original) remove(key) else putString(key, next.name)
            }.apply()
        }
        widgets[index]?.tone?.text = next.label
        return true
    }

    /** Replays controls after a new synth/score becomes ready. It deliberately ignores recordings. */
    fun reapply(): Boolean {
        val loaded = score ?: return false
        if (!isSynthesizer()) return false
        val api = apiProvider()
        if (!api.isReadyForPlayback || api.score !== loaded) return false
        val player = api.player ?: return false
        val routes = toneMidi?.channels ?: return false
        api.metronomeVolume = metronome.volume
        api.countInVolume = if (countInEnabled) 1.0 else 0.0
        for ((index, track) in loaded.tracks.withIndex()) {
            val state = tracks[index]
            for (channel in routes[index].all) {
                player.setChannelVolume(channel, state.volume)
                player.setChannelMute(channel, state.muted)
                player.setChannelSolo(channel, state.solo)
                player.setChannelTranspositionPitch(channel, if (track.isPercussion) 0.0 else transposition.toDouble())
            }
        }
        if (openWhenReady) {
            openWhenReady = false
            show()
        }
        return true
    }

    fun setTrackVolume(index: Int, volume: Double): Boolean = updateTracks(index) {
        it.copy(volume = volume.coerceIn(0.0, 1.0))
    }
    fun setTrackMute(index: Int, muted: Boolean): Boolean = updateTracks(index) {
        it.copy(muted = muted, solo = if (muted) false else it.solo)
    }
    fun setTrackSolo(index: Int, solo: Boolean): Boolean = updateTracks(index) {
        it.copy(solo = solo, muted = if (solo) false else it.muted)
    }

    private fun updateTracks(index: Int, change: (TrackMix) -> TrackMix): Boolean {
        val loaded = score ?: return false
        if (!isSynthesizer() || index !in tracks.indices) return false
        if (linkedTracks(index).all { change(tracks[it]) == tracks[it] }) return true
        beforeMixChange()
        // The synth addresses MIDI channels, not separate audio stems. Keep shared-channel rows linked.
        for (linked in linkedTracks(index)) {
            val state = change(tracks[linked])
            tracks[linked] = state
            loaded.tracks[linked].playbackInfo.apply {
                volume = state.volume * 16.0
                isMute = state.muted
                isSolo = state.solo
            }
        }
        reapply()
        refreshTrackWidgets()
        afterMixChange()
        return true
    }

    private fun linkedTracks(index: Int): Set<Int> {
        val routes = toneMidi?.channels ?: return emptySet()
        val result = mutableSetOf(index)
        val channels = mutableSetOf<Double>()
        var changed = true
        while (changed) {
            changed = false
            for (member in result.toList()) {
                channels.addAll(routes[member].all)
            }
            for ((candidate, route) in routes.withIndex()) {
                if (route.all.any { it in channels } && result.add(candidate)) changed = true
            }
        }
        return result
    }

    fun setTransposition(semitones: Int): Boolean {
        if (score == null || !isSynthesizer()) return false
        transposition = semitones.coerceIn(-12, 12)
        reapply()
        return true
    }
    fun setMetronomeEnabled(enabled: Boolean): Boolean {
        if (score == null) return false
        metronomeEnabled = enabled
        updateMetronomeVolume()
        return true
    }
    fun setMetronomeVolume(volume: Double): Boolean {
        if (score == null || !volume.isFinite()) return false
        metronomeVolume = volume.coerceIn(0.0, 1.0)
        updateMetronomeVolume()
        return true
    }

    private fun updateMetronomeVolume() {
        metronome.volume = if (metronomeEnabled) metronomeVolume else 0.0
        if (isSynthesizer()) {
            if (score != null && apiProvider().score === score) apiProvider().metronomeVolume = metronome.volume
        } else onMetronomeChange()
        onMetronomeUiChange()
    }
    fun setCountInEnabled(enabled: Boolean): Boolean {
        if (score == null || !isSynthesizer()) return false
        countInEnabled = enabled; reapply(); return true
    }

    private fun refreshTrackWidgets() {
        updatingWidgets = true
        try {
            for ((index, views) in widgets) {
                val state = tracks.getOrNull(index) ?: continue
                views.slider.progress = (state.volume * 100).toInt()
                views.volume.text = "${(state.volume * 100).toInt()}%"
                views.mute.isChecked = state.muted
                views.solo.isChecked = state.solo
            }
        } finally { updatingWidgets = false }
    }

    fun show() {
        dialog?.dismiss(); dialog = null; widgets.clear()
        metronomeTempoButton = null
        val loaded = score
        if (loaded == null) {
            dialog = AlertDialog.Builder(context).setTitle("练习控制").setMessage("请先打开乐谱。")
                .setPositiveButton("关闭", null).show()
            return
        }
        if (!isSynthesizer()) {
            val content = practiceContent()
            addMetronomeControls(content, countIn = false)
            val live = liveGuitarTracks().sorted()
            if (live.isEmpty()) content.addView(label(externalPlaybackDescription()).apply { setPadding(0, dp(12), 0, 0) })
            else {
                content.addView(label("声部效果器", true))
                for (index in live) {
                    val name = loaded.tracks[index].name.ifBlank { "声部 ${index + 1}" }
                    content.addView(mixButton("$name · 效果器") { dialog?.dismiss(); openAmp(index) })
                }
            }
            showPracticeDialog(content, synth = false)
            return
        }
        if (!apiProvider().isReadyForPlayback) {
            dialog = AlertDialog.Builder(context).setTitle("练习控制").setMessage("合成音源正在准备，请稍后再打开。")
                .setPositiveButton("关闭", null).show()
            return
        }

        val content = practiceContent()
        content.addView(label("播放移调", true))
        val transposeLabel = label(transpositionText(), false)
        content.addView(transposeLabel)
        content.addView(slider(24, transposition + 12, "播放移调") { value ->
            setTransposition(value - 12); transposeLabel.text = transpositionText()
        })
        content.addView(label("仅改变合成播放音高，不修改谱面音符；鼓轨保持原音高。", false, 12f))

        addMetronomeControls(content, countIn = true)

        content.addView(label("声部混音", true))
        if (tracks.indices.any { linkedTracks(it).size > 1 }) {
            content.addView(label("部分声部共用 MIDI 通道，其混音控制会联动。", false, 12f))
        }
        for ((index, track) in loaded.tracks.withIndex()) {
            val name = track.name.ifBlank { "声部 ${index + 1}" }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = AppUi.surface(context, AppUi.panel, 12, AppUi.line)
            }
            val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(label(name).apply {
                setTextColor(AppUi.ink); typeface = AppUi.medium
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val tone = if (canChooseTone(index)) mixButton(tones[index].label) { showTone(index) }.apply {
                contentDescription = "$name 音色"
            } else null
            tone?.let { titleRow.addView(it) }
            val volumeLabel = label("${(tracks[index].volume * 100).toInt()}%").apply {
                setTextColor(AppUi.accent); typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = AppUi.surface(context, AppUi.selected, 7)
            }
            titleRow.addView(volumeLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
            card.addView(titleRow)
            val volumeSlider = slider(100, (tracks[index].volume * 100).toInt(), "$name 音量") { value ->
                if (!updatingWidgets) setTrackVolume(index, value / 100.0)
            }
            card.addView(volumeSlider)
            val switches = LinearLayout(context)
            val mute = CheckBox(context).apply {
                text = "静音"; contentDescription = "$name 静音"; isChecked = tracks[index].muted
                setOnCheckedChangeListener { _, checked -> if (!updatingWidgets) setTrackMute(index, checked) }
            }
            val solo = CheckBox(context).apply {
                text = "独奏"; contentDescription = "$name 独奏"; isChecked = tracks[index].solo
                setOnCheckedChangeListener { _, checked -> if (!updatingWidgets) setTrackSolo(index, checked) }
            }
            switches.addView(mute, LinearLayout.LayoutParams(0, dp(48), 1f))
            switches.addView(solo, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (canChooseTone(index)) switches.addView(mixButton("效果器") { dialog?.dismiss(); openAmp(index) }.apply {
                contentDescription = "$name 效果器"
                AppUi.iconButton(this, "audio", AppUi.accent, 15)
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { gravity = Gravity.CENTER_VERTICAL })
            card.addView(switches)
            widgets[index] = TrackWidgets(volumeSlider, volumeLabel, mute, solo, tone)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        showPracticeDialog(content, synth = true)
    }

    private fun practiceContent() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(20))
    }

    private fun addMetronomeControls(content: LinearLayout, countIn: Boolean) {
        if (countIn) content.addView(label("节拍与预备拍", true))
        val metroVolumeLabel = label("节拍器音量 ${(metronomeVolume * 100).toInt()}%")
        val metroVolumeSlider = slider(100, (metronomeVolume * 100).toInt(), "节拍器音量") { value ->
            setMetronomeVolume(value / 100.0); metroVolumeLabel.text = "节拍器音量 $value%"
        }
        metroVolumeSlider.isEnabled = metronomeEnabled
        content.addView(CheckBox(context).apply {
            text = "节拍器"; isChecked = metronomeEnabled
            setOnCheckedChangeListener { _, checked -> setMetronomeEnabled(checked); metroVolumeSlider.isEnabled = checked }
        })
        val tempoButton = mixButton(metronomeTempoLabel(), ::chooseMetronomeTempo)
        metronomeTempoButton = tempoButton
        content.addView(tempoButton, LinearLayout.LayoutParams(-1, dp(44)))
        val rhythmButton = mixButton(metronomeRhythmLabel()) {}
        rhythmButton.setOnClickListener { chooseMetronomeRhythm(rhythmButton) }
        content.addView(rhythmButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        content.addView(metroVolumeLabel)
        content.addView(metroVolumeSlider)
        if (countIn) content.addView(CheckBox(context).apply {
            text = "播放前预备一小节"; isChecked = countInEnabled
            setOnCheckedChangeListener { _, checked -> setCountInEnabled(checked) }
        })
    }

    private fun showPracticeDialog(content: LinearLayout, synth: Boolean) {
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = (resources.displayMetrics.heightPixels * 0.66).toInt()
                val available = if (android.view.View.MeasureSpec.getMode(heightMeasureSpec) == android.view.View.MeasureSpec.UNSPECIFIED)
                    maximum else minOf(maximum, android.view.View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(available, android.view.View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(content); isFillViewport = false }
        val builder = AlertDialog.Builder(context).setTitle("混音与练习设置").setView(scroll).setPositiveButton("关闭", null)
        if (!synth) builder.setNeutralButton("切换到合成音源") { _, _ -> openWhenReady = true; switchToSynthesizer() }
        dialog = builder.create().apply {
                val owner = this
                setOnDismissListener {
                    if (dialog === owner) { widgets.clear(); metronomeTempoButton = null }
                    onMixSessionEnded()
                }
                setOnShowListener {
                    val display = context.resources.displayMetrics
                    window?.setLayout(minOf(display.widthPixels - dp(32), dp(620)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                show()
            }
    }

    fun close() {
        openWhenReady = false
        metronomeDialog?.dismiss(); metronomeDialog = null; metronomeTempoButton = null
        toneDialog?.dismiss(); toneDialog = null
        dialog?.dismiss(); dialog = null; widgets.clear()
    }

    internal fun destroy() {
        close()
        metronome.cancelPendingLoad()
    }

    private fun metronomeTempoLabel(): String {
        val fixed = metronome.settings.bpm
        if (fixed != null) return "自定义 · $fixed BPM"
        return "跟随乐谱 · ${scoreBpmLabel()} BPM"
    }

    private fun scoreBpmLabel() = if (scoreBpmTenths % 10 == 0) (scoreBpmTenths / 10).toString() else (scoreBpmTenths / 10.0).toString()

    private fun chooseMetronomeTempo() {
        metronomeDialog = AlertDialog.Builder(context).setTitle("节拍器速度")
            .setSingleChoiceItems(arrayOf("跟随乐谱", "自定义 BPM"), if (metronome.settings.bpm == null) 0 else 1) { owner, choice ->
                owner.dismiss()
                if (choice == 0) setMetronomeBpm(null) else enterMetronomeBpm()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun enterMetronomeBpm() {
        val range = MetronomeSettings.bpmRange
        val input = EditText(context).apply {
            hint = "BPM（${range.first}–${range.last}）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText((metronome.settings.bpm ?: (scoreBpmTenths / 10.0).roundToInt().coerceIn(range)).toString())
            setSelectAllOnFocus(true)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(label("BPM 按四分音符计算，以 4/4 打拍。", size = 12f))
        }
        metronomeDialog = AlertDialog.Builder(context).setTitle("自定义节拍器 BPM").setView(content)
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val bpm = input.text.toString().toIntOrNull()
                        if (bpm == null || bpm !in range) input.error = "请输入 ${range.first}–${range.last} 的整数"
                        else if (setMetronomeBpm(bpm)) dismiss()
                    }
                }
                show()
            }
    }

    private fun metronomeRhythmLabel() = "节奏 · ${metronome.settings.rhythm.label}"

    private fun chooseMetronomeRhythm(button: Button) {
        val rhythms = MetronomeRhythm.entries
        metronomeDialog = AlertDialog.Builder(context).setTitle("节拍器节奏")
            .setSingleChoiceItems(rhythms.map { it.label }.toTypedArray(), metronome.settings.rhythm.ordinal) { owner, index ->
                if (setMetronomeRhythm(rhythms[index])) button.text = metronomeRhythmLabel()
                owner.dismiss()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun transpositionText() = if (transposition > 0) "+$transposition 半音" else "$transposition 半音"
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(value: String, heading: Boolean = false, size: Float = 14f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (heading) AppUi.ink else AppUi.muted)
        if (heading) { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(8)) }
    }
    private fun mixButton(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; typeface = AppUi.medium; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(44); minimumHeight = dp(44)
        setTextColor(AppUi.accent); setPadding(dp(10), 0, dp(10), 0)
        background = AppUi.ripple(context, AppUi.control, 9)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun slider(maximum: Int, value: Int, description: String, changed: (Int) -> Unit) = SeekBar(context).apply {
        max = maximum; progress = value; contentDescription = description
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, position: Int, user: Boolean) { if (user) changed(position) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }
}
