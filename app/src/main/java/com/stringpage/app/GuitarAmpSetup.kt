@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.model.Score
import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class GuitarAmpSetup(
    private val activity: Activity,
    private val isCurrent: (Score) -> Boolean,
    private val busy: (Boolean) -> Unit,
    private val applyLive: (Map<Int, GuitarAmpSettings>) -> Boolean,
    private val ready: (Score, AmpStems, Map<Int, GuitarAmpSettings>) -> Unit
) : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val mixer = SynthAmpMix(activity.cacheDir) { activity.assets.open("sonivox.sf2").use { it.readBytes() } }
    private val store = activity.getSharedPreferences("practice-guitar-amps", Activity.MODE_PRIVATE)
    private var active: AtomicBoolean? = null
    private var dialog: AlertDialog? = null
    private var transientScore: Score? = null
    private var transient = emptyMap<Int, GuitarAmpSettings>()
    private var closed = false

    fun settingsFor(score: Score, document: String?): Map<Int, GuitarAmpSettings> {
        if (document == null) return if (score === transientScore) transient else emptyMap()
        val prefix = preferenceKey(document)
        return score.tracks.toList().mapIndexedNotNull { index, track ->
            if (!GuitarTone.supports(track)) return@mapIndexedNotNull null
            store.getString("$prefix/$index", null)?.let(::decode)?.let { index to it }
        }.toMap()
    }

    fun show(
        score: Score, document: String?, slot: Int, settings: Settings, tones: List<GuitarTone>,
        transpose: Int, live: Boolean
    ) {
        val track = score.tracks[slot]
        if (!GuitarTone.supports(track)) return
        dialog?.dismiss()
        val current = settingsFor(score, document)
        val initial = current[slot] ?: GuitarAmpSettings(
            volume = (track.playbackInfo.volume * 75 / 16).toInt().coerceIn(0, 100),
            pan = ((track.playbackInfo.balance - 8) * 12.5).toInt().coerceIn(-100, 100)
        )
        val content = column()
        val enabled = CheckBox(activity).apply { text = "使用音箱效果"; isChecked = !live || current[slot] != null }
        content.addView(enabled)
        var preview: (() -> Unit)? = null
        fun selector(label: String, choices: List<String>, selected: Int, host: LinearLayout = content): Spinner {
            host.addView(caption(label))
            return Spinner(activity).apply {
                contentDescription = label
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
                setSelection(selected)
                host.addView(this)
            }
        }
        fun slider(label: String, value: Int, min: Int, max: Int, host: LinearLayout = content): SeekBar {
            val text = caption("$label $value")
            host.addView(text)
            return SeekBar(activity).apply {
                this.max = max - min
                progress = value - min
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar?, progress: Int, fromUser: Boolean) {
                        text.text = "$label ${progress + min}"
                        if (fromUser) preview?.invoke()
                    }
                    override fun onStartTrackingTouch(view: SeekBar?) {}
                    override fun onStopTrackingTouch(view: SeekBar?) {}
                })
                host.addView(this)
            }
        }
        val preset = selector("预设", AmpPreset.entries.map { it.label }, initial.preset.ordinal)
        val gain = slider("增益", initial.gain, 0, 100)
        val volume = slider("输出音量", initial.volume, 0, 100)
        val advanced = column().apply { visibility = View.GONE; setPadding(0, 0, 0, 0) }
        content.addView(CheckBox(activity).apply {
            text = "箱体、声像和均衡"
            setOnCheckedChangeListener { _, checked -> advanced.visibility = if (checked) View.VISIBLE else View.GONE }
        })
        val cabinet = selector("箱体", GuitarCabinet.entries.map { it.label }, initial.cabinet.ordinal, advanced)
        val pan = slider("声像", initial.pan, -100, 100, advanced)
        val bass = slider("低频 dB", initial.bass, -12, 12, advanced)
        val mids = slider("中频 dB", initial.mids, -12, 12, advanced)
        val treble = slider("高频 dB", initial.treble, -12, 12, advanced)
        content.addView(advanced)
        fun selectedSettings() = current.toMutableMap().apply {
            if (enabled.isChecked) put(slot, GuitarAmpSettings(AmpPreset.entries[preset.selectedItemPosition],
                gain.progress, volume.progress, pan.progress - 100, GuitarCabinet.entries[cabinet.selectedItemPosition],
                bass.progress - 12, mids.progress - 12, treble.progress - 12))
            else remove(slot)
        }
        var appliedPreview = current
        var committed = false
        if (live) preview = {
            val values = selectedSettings()
            if (values != appliedPreview && isCurrent(score) && applyLive(values)) appliedPreview = values
        }
        fun selection(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) = action(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        var previousPreset = initial.preset
        preset.onItemSelectedListener = selection { slot ->
            val next = AmpPreset.entries[slot]
            if (next != previousPreset) {
                if (cabinet.selectedItemPosition == previousPreset.defaultCabinet.ordinal) {
                    cabinet.setSelection(next.defaultCabinet.ordinal)
                }
                previousPreset = next
                enabled.isChecked = true
                preview?.invoke()
            }
        }
        cabinet.onItemSelectedListener = selection { preview?.invoke() }
        enabled.setOnCheckedChangeListener { _, _ -> preview?.invoke() }
        dialog = AlertDialog.Builder(activity).setTitle("${track.name.ifBlank { "声部 ${slot + 1}" }} · 吉他音箱")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                preview = null
                if (!committed && appliedPreview != current && isCurrent(score)) applyLive(current)
            }
            .setPositiveButton("应用") { _, _ ->
                committed = true
                val values = selectedSettings()
                if (applyLive(values) || values.isEmpty()) {
                    save(score, document, slot, values[slot], values)
                } else prepare(score, settings, tones, values, transpose) {
                    save(score, document, slot, values[slot], values)
                }
            }.show()
    }

    fun cancel() {
        active?.let { it.set(true); active = null; busy(false) }
        dialog?.dismiss()
        dialog = null
    }

    fun discardScore() {
        cancel()
        transientScore = null
        transient = emptyMap()
    }

    fun prepare(
        score: Score, settings: Settings, tones: List<GuitarTone>, amps: Map<Int, GuitarAmpSettings>,
        transpose: Int, onReady: () -> Unit = {}
    ) {
        cancel()
        val flag = AtomicBoolean(false)
        active = flag
        val content = column()
        val note = caption("正在准备吉他干声…").also(content::addView)
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        content.addView(progress)
        busy(true)
        dialog = AlertDialog.Builder(activity).setTitle("准备音箱混音").setView(content)
            .setCancelable(false).setNegativeButton("取消") { _, _ -> cancel() }.show()
        worker.execute {
            try {
                val result = mixer.prepare(score, settings, tones, transpose, flag::get) { percent ->
                    activity.runOnUiThread {
                        if (active === flag && !flag.get() && !closed) {
                            note.text = "正在准备 $percent%"
                            progress.progress = percent
                        }
                    }
                }
                activity.runOnUiThread {
                    if (closed || active !== flag || flag.get() || !isCurrent(score)) {
                        result.close()
                    } else {
                        cancel()
                        onReady()
                        ready(score, result, amps)
                    }
                }
            } catch (failure: Exception) {
                activity.runOnUiThread {
                    if (!closed && active === flag && !flag.get() && isCurrent(score)) {
                        cancel()
                        dialog = AlertDialog.Builder(activity).setTitle("音箱混音准备失败")
                            .setMessage(failure.message ?: "请重新准备播放。").setPositiveButton("关闭", null).show()
                    }
                }
            }
        }
    }

    private fun save(score: Score, document: String?, index: Int, config: GuitarAmpSettings?, all: Map<Int, GuitarAmpSettings>) {
        if (document == null) {
            transientScore = score
            transient = all.toMap()
            return
        }
        val key = "${preferenceKey(document)}/$index"
        store.edit().apply {
            if (config == null) remove(key) else putString(key, encode(config))
        }.apply()
    }

    override fun close() {
        discardScore()
        closed = true
        worker.shutdown()
    }

    private fun preferenceKey(document: String) =
        MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun encode(config: GuitarAmpSettings) = JSONObject().put("preset", config.preset.name)
        .put("gain", config.gain).put("volume", config.volume).put("pan", config.pan).put("cabinet", config.cabinet.name)
        .put("bass", config.bass).put("mids", config.mids).put("treble", config.treble).toString()

    private fun decode(raw: String): GuitarAmpSettings? = runCatching {
        val json = JSONObject(raw)
        GuitarAmpSettings(AmpPreset.valueOf(json.getString("preset")), json.optInt("gain", 50).coerceIn(0, 100),
            json.optInt("volume", 75).coerceIn(0, 100), json.optInt("pan", 0).coerceIn(-100, 100),
            GuitarCabinet.valueOf(json.optString("cabinet", GuitarCabinet.Open.name)),
            json.optInt("bass").coerceIn(-12, 12), json.optInt("mids").coerceIn(-12, 12),
            json.optInt("treble").coerceIn(-12, 12))
    }.getOrNull()

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding / 2)
    }

    private fun caption(value: String) = TextView(activity).apply { text = value; textSize = 14f; setTextColor(AppUi.ink) }
}
