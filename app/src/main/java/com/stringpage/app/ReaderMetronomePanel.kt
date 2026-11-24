package com.stringpage.app

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.roundToInt

internal class ReaderMetronomePanel(context: Context, private val practice: PlaybackControls) : LinearLayout(context) {
    private val toggle = button("") { practice.setMetronomeEnabled(!practice.metronomeUi().enabled) }
    private val bpm: EditText = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSelectAllOnFocus(true)
        setSingleLine()
        gravity = Gravity.CENTER
        textSize = 16f
        contentDescription = "自定义节拍器 BPM，20 到 300"
        background = AppUi.surface(context, AppUi.control, 8, AppUi.line)
        setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) applyBpm() } }
    }
    private val applyButton: Button = button("设定 BPM") { applyBpm() }
    private val follow = button("跟随乐谱") { practice.setMetronomeBpm(null) }
    private val rhythms = Spinner(context).apply {
        contentDescription = "节拍器节奏"
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, MetronomeRhythm.entries.map { it.label })
        background = AppUi.surface(context, AppUi.control, 8, AppUi.line)
        setPadding(dp(10), 0, dp(10), 0)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (practice.metronomeUi().rhythm.ordinal != position) practice.setMetronomeRhythm(MetronomeRhythm.entries[position])
            }
        }
    }
    private val volumeLabel = TextView(context).apply { textSize = 13f; setTextColor(AppUi.muted) }
    private val volume = SeekBar(context).apply {
        max = 100
        contentDescription = "节拍器音量"
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                if (user) practice.setMetronomeVolume(value / 100.0)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }
    private var displayedFixed: Int? = null
    private var displayedBpm: String? = null

    init {
        orientation = VERTICAL
        addView(toggle, LayoutParams(-1, dp(48)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(bpm, LayoutParams(0, dp(48), 1f))
            addView(applyButton, LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
        }, LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        addView(follow, LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        addView(rhythms, LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        addView(volumeLabel, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(volume, LayoutParams(-1, dp(42)))
        refresh()
    }

    fun refresh() {
        val state = practice.metronomeUi()
        val status = if (state.enabled) "开 · ${state.bpm} BPM" else "关"
        toggle.text = "节拍器 $status"
        toggle.isSelected = state.enabled
        toggle.contentDescription = "节拍器 $status，点击${if (state.enabled) "关闭" else "开启"}"
        toggle.setCompoundDrawablesWithIntrinsicBounds(AppUi.icon(context, if (state.enabled) "check" else "tempo", AppUi.accent, 17), null, null, null)
        follow.text = if (state.fixed == null) "✓ 跟随乐谱" else "跟随乐谱"
        follow.isSelected = state.fixed == null
        applyButton.text = if (state.fixed != null) "✓ 固定 BPM" else "设定 BPM"
        applyButton.isSelected = state.fixed != null
        val applied = state.fixed != displayedFixed
        if (applied || (!bpm.hasFocus() && displayedBpm != state.bpm)) {
            bpm.setText(state.fixed?.toString() ?: state.bpm.toDouble().roundToInt().toString())
        }
        displayedFixed = state.fixed
        displayedBpm = state.bpm
        if (rhythms.selectedItemPosition != state.rhythm.ordinal) rhythms.setSelection(state.rhythm.ordinal)
        volumeLabel.text = "节拍器音量 ${state.volume}%"
        volume.progress = state.volume
        volume.isEnabled = state.enabled
    }

    private fun applyBpm() {
        val value = bpm.text.toString().toIntOrNull()
        if (value == null || value !in MetronomeSettings.bpmRange) {
            bpm.error = "请输入 20–300 的整数"
        } else if (practice.setMetronomeBpm(value)) bpm.clearFocus()
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 13f
        typeface = AppUi.medium
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(AppUi.ink)
        background = AppUi.ripple(context, AppUi.control, 8)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = AppUi.dp(context, value)
}
