@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabApiBase
import alphaTab.AlphaTabView
import alphaTab.model.Score
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

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
    private val externalPlaybackDescription: () -> String = { "内嵌录音是完整音频，不能按乐谱声部分别静音或独奏。轨道混音、节拍器、预备拍和播放移调在合成音源模式下使用。" }
) {
    data class TrackMix(val volume: Double, val muted: Boolean, val solo: Boolean)
    data class Snapshot(
        val tracks: List<TrackMix>,
        val transposition: Int,
        val metronomeEnabled: Boolean,
        val metronomeVolume: Double,
        val countInEnabled: Boolean
    )

    private var score: Score? = null
    private val tracks = mutableListOf<TrackMix>()
    private var transposition = 0
    private var metronomeEnabled = false
    private var metronomeVolume = 0.7
    private var countInEnabled = false
    private var dialog: AlertDialog? = null
    private var openWhenReady = false
    private var updatingWidgets = false
    private data class TrackWidgets(val slider: SeekBar, val volume: TextView, val mute: CheckBox, val solo: CheckBox)
    private val widgets = mutableMapOf<Int, TrackWidgets>()

    fun reset(newScore: Score, preservePractice: Boolean = false) {
        close()
        score = newScore
        tracks.clear()
        for (track in newScore.tracks) {
            tracks.add(TrackMix((track.playbackInfo.volume / 16.0).coerceIn(0.0, 1.0),
                track.playbackInfo.isMute && !track.playbackInfo.isSolo, track.playbackInfo.isSolo))
        }
        if (!preservePractice) {
            transposition = 0
            metronomeEnabled = false
            metronomeVolume = 0.7
            countInEnabled = false
        }
    }

    fun snapshot() = Snapshot(tracks.toList(), transposition, metronomeEnabled, metronomeVolume, countInEnabled)

    /** Replays controls after a new synth/score becomes ready. It deliberately ignores recordings. */
    fun reapply(): Boolean {
        val loaded = score ?: return false
        if (!isSynthesizer()) return false
        val api = apiProvider()
        if (!api.isReadyForPlayback || api.score !== loaded) return false
        api.metronomeVolume = if (metronomeEnabled) metronomeVolume else 0.0
        api.countInVolume = if (countInEnabled) 1.0 else 0.0
        for ((index, track) in loaded.tracks.withIndex()) {
            val state = tracks[index]
            val selection = alphaTab.collections.List(track)
            api.changeTrackVolume(selection, state.volume)
            api.changeTrackMute(selection, state.muted)
            api.changeTrackSolo(selection, state.solo)
            api.changeTrackTranspositionPitch(selection, if (track.isPercussion) 0.0 else transposition.toDouble())
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
        val loaded = score ?: return emptySet()
        val result = mutableSetOf(index)
        val channels = mutableSetOf<Double>()
        var changed = true
        while (changed) {
            changed = false
            for (member in result.toList()) {
                channels.add(loaded.tracks[member].playbackInfo.primaryChannel)
                channels.add(loaded.tracks[member].playbackInfo.secondaryChannel)
            }
            for ((candidate, track) in loaded.tracks.withIndex()) {
                val info = track.playbackInfo
                if ((info.primaryChannel in channels || info.secondaryChannel in channels) && result.add(candidate)) changed = true
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
        if (score == null || !isSynthesizer()) return false
        metronomeEnabled = enabled; reapply(); return true
    }
    fun setMetronomeVolume(volume: Double): Boolean {
        if (score == null || !isSynthesizer()) return false
        metronomeVolume = volume.coerceIn(0.0, 1.0); reapply(); return true
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
        val loaded = score
        if (loaded == null) {
            dialog = AlertDialog.Builder(context).setTitle("练习控制").setMessage("请先打开乐谱。")
                .setPositiveButton("关闭", null).show()
            return
        }
        if (!isSynthesizer()) {
            dialog = AlertDialog.Builder(context).setTitle("练习控制")
                .setMessage(externalPlaybackDescription())
                .setPositiveButton("切换到合成音源") { _, _ -> openWhenReady = true; switchToSynthesizer() }
                .setNegativeButton("关闭", null).show()
            return
        }
        if (!apiProvider().isReadyForPlayback) {
            dialog = AlertDialog.Builder(context).setTitle("练习控制").setMessage("合成音源正在准备，请稍后再打开。")
                .setPositiveButton("关闭", null).show()
            return
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(20))
        }
        content.addView(label("播放移调", true))
        val transposeLabel = label(transpositionText(), false)
        content.addView(transposeLabel)
        content.addView(slider(24, transposition + 12, "播放移调") { value ->
            setTransposition(value - 12); transposeLabel.text = transpositionText()
        })
        content.addView(label("仅改变合成播放音高，不修改谱面音符；鼓轨保持原音高。", false, 12f))

        content.addView(label("节拍与预备拍", true))
        val metroVolumeLabel = label("节拍器音量 ${(metronomeVolume * 100).toInt()}%")
        val metroVolumeSlider = slider(100, (metronomeVolume * 100).toInt(), "节拍器音量") { value ->
            setMetronomeVolume(value / 100.0); metroVolumeLabel.text = "节拍器音量 $value%"
        }
        metroVolumeSlider.isEnabled = metronomeEnabled
        val metro = CheckBox(context).apply {
            text = "节拍器"; isChecked = metronomeEnabled
            setOnCheckedChangeListener { _, checked -> setMetronomeEnabled(checked); metroVolumeSlider.isEnabled = checked }
        }
        content.addView(metro); content.addView(metroVolumeLabel); content.addView(metroVolumeSlider)
        content.addView(CheckBox(context).apply {
            text = "播放前预备一小节"; isChecked = countInEnabled
            setOnCheckedChangeListener { _, checked -> setCountInEnabled(checked) }
        })

        content.addView(label("声部混音", true))
        if (tracks.indices.any { linkedTracks(it).size > 1 }) {
            content.addView(label("部分声部共用 MIDI 通道，其混音控制会联动。", false, 12f))
        }
        for ((index, track) in loaded.tracks.withIndex()) {
            val name = track.name.ifBlank { "声部 ${index + 1}" }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(8))
                background = AppUi.surface(context, AppUi.canvas, 12, AppUi.line)
            }
            val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(label(name).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            val volumeLabel = label("${(tracks[index].volume * 100).toInt()}%")
            titleRow.addView(volumeLabel)
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
            card.addView(switches)
            widgets[index] = TrackWidgets(volumeSlider, volumeLabel, mute, solo)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = (resources.displayMetrics.heightPixels * 0.66).toInt()
                val available = if (android.view.View.MeasureSpec.getMode(heightMeasureSpec) == android.view.View.MeasureSpec.UNSPECIFIED)
                    maximum else minOf(maximum, android.view.View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(available, android.view.View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(content); isFillViewport = false }
        dialog = AlertDialog.Builder(context).setTitle("合成练习控制").setView(scroll)
            .setPositiveButton("关闭", null).create().apply {
                val owner = this
                setOnDismissListener { if (dialog === owner) widgets.clear(); onMixSessionEnded() }
                setOnShowListener {
                    val display = context.resources.displayMetrics
                    window?.setLayout(minOf(display.widthPixels - dp(32), dp(620)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                show()
            }
    }

    fun close() {
        openWhenReady = false
        dialog?.dismiss(); dialog = null; widgets.clear()
    }

    private fun transpositionText() = if (transposition > 0) "+$transposition 半音" else "$transposition 半音"
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(value: String, heading: Boolean = false, size: Float = 14f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (heading) AppUi.ink else AppUi.muted)
        if (heading) { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(8)) }
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
