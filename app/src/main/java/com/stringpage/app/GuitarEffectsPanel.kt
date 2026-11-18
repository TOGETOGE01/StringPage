package com.stringpage.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

internal class GuitarEffectsPanel(
    private val activity: Activity,
    initial: GuitarAmpSettings,
    private val presets: GuitarPresetLibrary,
    private val changed: () -> Unit,
    private val pickCabinet: ((CabinetImpulse?) -> Unit) -> Unit
) {
    internal val wide = activity.resources.configuration.screenWidthDp >= 840
    private var updating = true
    private var impulse = initial.cabinetImpulse
    val view = column().apply { setPadding(dp(16), dp(6), dp(16), dp(12)) }
    private val body = column()
    private val scroll = ScrollView(activity).apply { addView(body) }
    private val enabled = CheckBox(activity).apply {
        text = "启用效果器链"
        setTextColor(AppUi.railInk); textSize = 14f; typeface = AppUi.medium
        buttonTintList = android.content.res.ColorStateList.valueOf(AppUi.railAccent)
        isChecked = initial.enabled
        setOnCheckedChangeListener { _, _ -> notifyChanged() }
    }

    private val ampPanel = section("音箱", "audio")
    private val inputPanel = section("输入与踏板", "strings")
    private val outputPanel = section("箱体与均衡", "mix")
    private val modulationPanel = section("调制", "audio")
    private val delayPanel = section("延迟", "clock")
    private val reverbPanel = section("混响", "audio")
    private val ampEnabled = toggle(ampPanel, "音箱", initial.ampEnabled)
    private val amp = selector(ampPanel, "音箱型号", AmpPreset.entries.map { it.label }, initial.preset.ordinal)
    private val gain = slider(ampPanel, "音箱增益", initial.gain, 0..100)
    private val volume = slider(ampPanel, "输出音量", initial.volume, 0..100)
    private val pan = slider(ampPanel, "声像", initial.pan, -100..100)
    private val input = slider(inputPanel, "输入增益", initial.inputDb, -18..18, "dB")
    private val gateEnabled = toggle(inputPanel, "噪声门", initial.gateDb != null)
    private val gate = slider(inputPanel, "噪声门阈值", initial.gateDb ?: -62, -80..-24, "dBFS")
    private val compression = slider(inputPanel, "压缩量", initial.effects.compression, 0..100, "%")
    private val wah = selector(inputPanel, "哇音", GuitarWah.entries.map { it.label }, initial.effects.wah.ordinal)
    private val wahControls = group(inputPanel)
    private val wahPosition = slider(wahControls, "踏板位置", initial.effects.wahPosition, 0..100, "%")
    private val firstPedal = PedalControls(inputPanel, "踏板 1", initial.firstPedal)
    private val secondPedal = PedalControls(inputPanel, "踏板 2", initial.secondPedal)
    private val swapPedals = button("交换踏板顺序") {
        val first = firstPedal.settings()
        updating = true
        firstPedal.apply(secondPedal.settings())
        secondPedal.apply(first)
        updating = false
        notifyChanged()
    }.also(inputPanel::addView)
    private val cabinetEnabled = toggle(outputPanel, "箱体", initial.cabinetEnabled)
    private val cabinet = selector(outputPanel, "箱体型号", GuitarCabinet.entries.map { it.label }, initial.cabinet.ordinal)
    private val cabinetMic = selector(outputPanel, "箱体拾音位置", CabinetMic.entries.map { it.label }, initial.cabinetMic.ordinal)
    private val impulseName = label("").apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val clearImpulse = button("使用内置箱体") {
        impulse = null
        refreshEnabled()
        notifyChanged()
    }

    init {
        outputPanel.addView(impulseName)
        outputPanel.addView(button("导入 WAV IR") { importCabinet() })
        outputPanel.addView(clearImpulse)
    }

    private val eqEnabled = toggle(outputPanel, "三段均衡", initial.eqEnabled)
    private val bass = slider(outputPanel, "低频", initial.bass, -12..12, "dB")
    private val mids = slider(outputPanel, "中频", initial.mids, -12..12, "dB")
    private val treble = slider(outputPanel, "高频", initial.treble, -12..12, "dB")

    private val modulation = selector(modulationPanel, "调制类型", GuitarModulation.entries.map { it.label }, initial.effects.modulation.ordinal)
    private val modulationControls = group(modulationPanel)
    private val modulationPosition = selector(modulationControls, "调制位置", ModulationPosition.entries.map { it.label },
        initial.effects.modulationPosition.ordinal)
    private val modulationDepth = slider(modulationControls, "调制深度", initial.effects.modulationDepth, 0..100, "%")
    private val modulationRate = slider(modulationControls, "调制速率", initial.effects.modulationRate, 10..500) {
        "${it / 100}.${(it % 100).toString().padStart(2, '0')} Hz"
    }
    private val delay = selector(delayPanel, "延迟类型", GuitarDelay.entries.map { it.label }, initial.effects.delay.ordinal)
    private val delayControls = group(delayPanel)
    private val delayVoice = selector(delayControls, "延迟音色", GuitarDelayVoice.entries.map { it.label },
        initial.effects.delayVoice.ordinal)
    private val delayPosition = selector(delayControls, "延迟位置", SpacePosition.entries.map { it.label },
        initial.effects.delayPosition.ordinal)
    private val fixedDelayControls = group(delayControls)
    private val delayMs = slider(fixedDelayControls, "延迟时间", initial.effects.delayMs, 60..1200, "ms")
    private val delayMix = slider(delayControls, "延迟湿声", initial.effects.delayMix, 0..100, "%")
    private val delayFeedback = slider(delayControls, "反馈", initial.effects.delayFeedback, 0..75, "%")
    private val reverb = selector(reverbPanel, "混响类型", GuitarReverb.entries.map { it.label }, initial.effects.reverb.ordinal)
    private val reverbControls = group(reverbPanel)
    private val reverbPosition = selector(reverbControls, "混响位置", SpacePosition.entries.map { it.label },
        initial.effects.reverbPosition.ordinal)
    private val reverbMix = slider(reverbControls, "混响湿声", initial.effects.reverbMix, 0..100, "%")
    private val reverbDecay = slider(reverbControls, "衰减长度", initial.effects.reverbDecay, 0..100, "%")
    private var rememberedAmp = initial.preset

    init {
        view.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(8), dp(4))
            background = AppUi.surface(activity, AppUi.rail, 12)
            addView(enabled, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("套用预设") { choosePreset() }.apply {
                background = AppUi.ripple(activity, AppUi.railSurface, 8, AppUi.railLine)
                setTextColor(AppUi.railAccent); setPadding(dp(13), 0, dp(13), 0)
            })
        })
        val core = listOf(ampPanel, inputPanel, outputPanel)
        val space = page(listOf(modulationPanel, delayPanel, reverbPanel))
        val pages = if (wide) listOf(page(core), space) else core + space
        val names = if (wide) listOf("音箱链", "调制与空间") else listOf("音箱", "输入", "箱体", "空间")
        val tabs = LinearLayout(activity)
        view.addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(9); bottomMargin = dp(9) })
        fun selectTab(index: Int) {
            for (at in pages.indices) {
                val active = at == index
                pages[at].visibility = visibility(active)
                val tab = tabs.getChildAt(at) as Button
                tab.isSelected = active
                tab.setTextColor(if (active) AppUi.accent else AppUi.muted)
                tab.background = AppUi.ripple(activity, if (active) AppUi.selected else AppUi.control, 9)
            }
            scroll.scrollTo(0, 0)
        }
        names.forEachIndexed { index, name ->
            tabs.addView(button(name) { selectTab(index) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(if (index == 0) 0 else 5)
            })
        }
        for (panel in pages) body.addView(panel)
        selectTab(0)
        view.addView(scroll, MATCH_PARENT, WRAP_CONTENT)
        amp.onItemSelectedListener = selection { index ->
            val next = AmpPreset.entries[index]
            if (next != rememberedAmp) {
                updating = true
                rememberedAmp = next
                enabled.isChecked = true
                updating = false
            }
            notifyChanged()
        }
        updating = false
        refreshEnabled()
    }

    fun settings(): GuitarAmpSettings {
        val first = firstPedal.settings()
        return GuitarAmpSettings(
            preset = AmpPreset.entries[amp.selectedItemPosition],
            gain = gain.progress,
            volume = volume.progress,
            pan = pan.progress - 100,
            cabinet = GuitarCabinet.entries[cabinet.selectedItemPosition],
            bass = bass.progress - 12,
            mids = mids.progress - 12,
            treble = treble.progress - 12,
            enabled = enabled.isChecked,
            inputDb = input.progress - 18,
            gateDb = if (gateEnabled.isChecked) gate.progress - 80 else null,
            pedal = first.model,
            pedalDrive = first.drive,
            pedalTone = first.tone,
            pedalLevelDb = first.levelDb,
            secondPedal = secondPedal.settings(),
            ampEnabled = ampEnabled.isChecked,
            cabinetEnabled = cabinetEnabled.isChecked,
            cabinetImpulse = impulse,
            cabinetMic = CabinetMic.entries[cabinetMic.selectedItemPosition],
            eqEnabled = eqEnabled.isChecked,
            effects = GuitarEffects(
                compression = compression.progress,
                wah = GuitarWah.entries[wah.selectedItemPosition],
                wahPosition = wahPosition.progress,
                modulation = GuitarModulation.entries[modulation.selectedItemPosition],
                modulationDepth = modulationDepth.progress,
                modulationRate = modulationRate.progress + 10,
                modulationPosition = ModulationPosition.entries[modulationPosition.selectedItemPosition],
                delay = GuitarDelay.entries[delay.selectedItemPosition],
                delayVoice = GuitarDelayVoice.entries[delayVoice.selectedItemPosition],
                delayMs = delayMs.progress + 60,
                delayMix = delayMix.progress,
                delayFeedback = delayFeedback.progress,
                delayPosition = SpacePosition.entries[delayPosition.selectedItemPosition],
                reverb = GuitarReverb.entries[reverb.selectedItemPosition],
                reverbMix = reverbMix.progress,
                reverbDecay = reverbDecay.progress,
                reverbPosition = SpacePosition.entries[reverbPosition.selectedItemPosition]
            )
        )
    }

    private fun choosePreset() {
        val names = presets.names()
        val rows = buildList<Pair<String, (() -> GuitarAmpSettings?)?>> {
            for (group in BuiltInGuitarPresets.groups) {
                add(group.label to null)
                for ((name, settings) in group.presets) add(name to { settings })
            }
            if (names.isNotEmpty()) add("自定义" to null)
            for (name in names) add(name to { presets.load(name) })
        }
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, rows.map { it.first }) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position].second != null

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val text = super.getView(position, convertView, parent) as TextView
                val selectable = isEnabled(position)
                text.typeface = if (selectable) Typeface.DEFAULT else AppUi.medium
                text.setTextColor(if (selectable) AppUi.ink else AppUi.muted)
                text.textSize = if (selectable) 16f else 12f
                return text
            }
        }
        AlertDialog.Builder(activity).setTitle("吉他效果预设")
            .setAdapter(adapter) { _, index -> rows[index].second?.invoke()?.let(::applyPreset) }
            .setNeutralButton("保存当前预设") { _, _ -> savePreset() }
            .apply { if (names.isNotEmpty()) setNegativeButton("管理预设") { _, _ -> managePresets() } }
            .setPositiveButton("关闭", null).show()
    }

    private fun applyPreset(preset: GuitarAmpSettings) {
        updating = true
        rememberedAmp = preset.preset
        enabled.isChecked = true
        input.progress = preset.inputDb + 18
        gateEnabled.isChecked = preset.gateDb != null
        gate.progress = (preset.gateDb ?: -62) + 80
        firstPedal.apply(preset.firstPedal)
        secondPedal.apply(preset.secondPedal)
        ampEnabled.isChecked = preset.ampEnabled
        amp.setSelection(preset.preset.ordinal)
        gain.progress = preset.gain
        cabinetEnabled.isChecked = preset.cabinetEnabled
        cabinet.setSelection(preset.cabinet.ordinal)
        cabinetMic.setSelection(preset.cabinetMic.ordinal)
        impulse = preset.cabinetImpulse
        eqEnabled.isChecked = preset.eqEnabled
        bass.progress = preset.bass + 12
        mids.progress = preset.mids + 12
        treble.progress = preset.treble + 12
        setEffects(preset.effects)
        updating = false
        notifyChanged()
    }

    private fun savePreset() {
        val nameInput = EditText(activity).apply {
            hint = "预设名称"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(GuitarPresetLibrary.MAX_NAME_LENGTH))
            setSingleLine()
        }
        val draft = settings()
        val content = column().apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(nameInput, MATCH_PARENT, WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("保存吉他预设").setView(content)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) { nameInput.error = "请输入预设名称"; return@setOnClickListener }
                val existing = presets.names().firstOrNull { it.equals(name, ignoreCase = true) }
                fun save(key: String) {
                    presets.save(key, draft)
                    dialog.dismiss()
                    Toast.makeText(activity, "已保存预设", Toast.LENGTH_SHORT).show()
                }
                if (existing == null) save(name)
                else AlertDialog.Builder(activity).setTitle("替换预设「$existing」？")
                    .setNegativeButton("取消", null).setPositiveButton("替换") { _, _ -> save(existing) }.show()
            }
        }
        dialog.show()
    }

    private fun managePresets() {
        val names = presets.names()
        AlertDialog.Builder(activity).setTitle("删除自定义预设").setNegativeButton("取消", null)
            .setItems(names.toTypedArray()) { _, index ->
                val name = names[index]
                AlertDialog.Builder(activity).setTitle("删除预设「$name」？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> presets.remove(name) }.show()
            }.show()
    }

    private fun setEffects(effects: GuitarEffects) {
        compression.progress = effects.compression
        wah.setSelection(effects.wah.ordinal)
        wahPosition.progress = effects.wahPosition
        modulation.setSelection(effects.modulation.ordinal)
        modulationDepth.progress = effects.modulationDepth
        modulationRate.progress = effects.modulationRate - 10
        modulationPosition.setSelection(effects.modulationPosition.ordinal)
        delay.setSelection(effects.delay.ordinal)
        delayVoice.setSelection(effects.delayVoice.ordinal)
        delayMs.progress = effects.delayMs - 60
        delayMix.progress = effects.delayMix
        delayFeedback.progress = effects.delayFeedback
        delayPosition.setSelection(effects.delayPosition.ordinal)
        reverb.setSelection(effects.reverb.ordinal)
        reverbMix.progress = effects.reverbMix
        reverbDecay.progress = effects.reverbDecay
        reverbPosition.setSelection(effects.reverbPosition.ordinal)
    }

    private fun importCabinet() {
        pickCabinet { imported ->
            if (imported != null) {
                updating = true
                impulse = imported
                cabinetEnabled.isChecked = true
                updating = false
                notifyChanged()
            }
        }
    }

    private fun notifyChanged() {
        if (updating) return
        refreshEnabled()
        changed()
    }

    private fun refreshEnabled() {
        amp.isEnabled = ampEnabled.isChecked
        gain.isEnabled = ampEnabled.isChecked
        gate.isEnabled = gateEnabled.isChecked
        firstPedal.refresh()
        secondPedal.refresh()
        swapPedals.visibility = visibility(firstPedal.active || secondPedal.active)
        cabinet.isEnabled = cabinetEnabled.isChecked && impulse == null
        cabinetMic.isEnabled = cabinet.isEnabled
        impulseName.text = impulse?.name.orEmpty()
        impulseName.visibility = if (impulse == null) View.GONE else View.VISIBLE
        clearImpulse.visibility = impulseName.visibility
        for (slider in listOf(bass, mids, treble)) slider.isEnabled = eqEnabled.isChecked
        wahControls.visibility = visibility(wah.selectedItemPosition == GuitarWah.Manual.ordinal)
        modulationControls.visibility = visibility(modulation.selectedItemPosition != GuitarModulation.Off.ordinal)
        delayControls.visibility = visibility(delay.selectedItemPosition != GuitarDelay.Off.ordinal)
        val alternating = delayVoice.selectedItemPosition == GuitarDelayVoice.PingPong.ordinal
        if (alternating && delayPosition.selectedItemPosition != SpacePosition.AfterCabinet.ordinal) {
            delayPosition.setSelection(SpacePosition.AfterCabinet.ordinal)
        }
        delayPosition.isEnabled = !alternating
        fixedDelayControls.visibility = visibility(delay.selectedItemPosition == GuitarDelay.Fixed.ordinal)
        reverbControls.visibility = visibility(reverb.selectedItemPosition != GuitarReverb.Off.ordinal)
    }

    private fun visibility(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    private inner class PedalControls(parent: LinearLayout, name: String, settings: GuitarPedalSettings) {
        private val model = selector(parent, name, GuitarDrive.entries.map { it.label }, settings.model.ordinal)
        private val knobs = group(parent)
        private val drive = slider(knobs, "$name 驱动", settings.drive, 0..100, "%")
        private val tone = slider(knobs, "$name 音色", settings.tone, 0..100, "%")
        private val level = slider(knobs, "$name 输出", settings.levelDb, -18..12, "dB")

        val active get() = model.selectedItemPosition != GuitarDrive.Off.ordinal

        fun settings() = GuitarPedalSettings(GuitarDrive.entries[model.selectedItemPosition],
            drive.progress, tone.progress, level.progress - 18)

        fun apply(settings: GuitarPedalSettings) {
            model.setSelection(settings.model.ordinal)
            drive.progress = settings.drive
            tone.progress = settings.tone
            level.progress = settings.levelDb + 18
        }

        fun refresh() { knobs.visibility = visibility(active) }
    }

    private fun page(panels: List<LinearLayout>) = LinearLayout(activity).apply {
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (panel in panels) {
            val params = if (wide) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            } else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) }
            addView(panel, params)
        }
    }

    private fun group(parent: LinearLayout) = column().also { parent.addView(it) }

    private fun section(title: String, icon: String) = column().apply {
        setPadding(dp(12), dp(10), dp(12), dp(8))
        background = AppUi.surface(activity, AppUi.panel, 12, AppUi.line)
        addView(label(title).apply {
            typeface = AppUi.medium; textSize = 17f; gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawables(AppUi.icon(activity, icon, AppUi.accent, 17), null, null, null)
            compoundDrawablePadding = dp(8)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(38)))
        addView(View(activity).apply { setBackgroundColor(AppUi.line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            bottomMargin = dp(8)
        })
    }

    private fun toggle(parent: LinearLayout, title: String, checked: Boolean) = CheckBox(activity).apply {
        text = title
        textSize = 13f; typeface = AppUi.medium
        isChecked = checked
        setOnCheckedChangeListener { _, _ -> notifyChanged() }
        parent.addView(this)
    }

    private fun selector(parent: LinearLayout, title: String, choices: List<String>, selected: Int): Spinner {
        parent.addView(label(title).apply { setTextColor(AppUi.muted) })
        return Spinner(activity).apply {
            contentDescription = title
            minimumHeight = dp(44)
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(selected)
            onItemSelectedListener = selection { notifyChanged() }
            parent.addView(this, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun slider(parent: LinearLayout, title: String, value: Int, range: IntRange, unit: String = "",
        format: ((Int) -> String)? = null): SeekBar {
        fun caption(amount: Int) = format?.invoke(amount) ?: if (unit.isEmpty()) "$amount" else "$amount $unit"
        val readout = label(caption(value)).apply {
            setTextColor(AppUi.accent); typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
        }
        parent.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(readout, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        })
        return SeekBar(activity).apply {
            max = range.last - range.first
            progress = value - range.first
            minimumHeight = dp(44)
            contentDescription = title
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    readout.text = caption(progress + range.first)
                    if (fromUser) notifyChanged()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            parent.addView(this, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun selection(onSelect: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) = onSelect(position)
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private fun button(title: String, action: () -> Unit) = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
        text = title
        textSize = 13f
        typeface = AppUi.medium
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(5), 0, dp(5), 0)
        setTextColor(AppUi.accent)
        background = AppUi.ripple(activity, AppUi.control, 9)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun label(title: String) = TextView(activity).apply {
        text = title
        textSize = 13f
        setTextColor(AppUi.ink)
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()
}
