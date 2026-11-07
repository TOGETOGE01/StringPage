@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app.rse

import alphaTab.Settings
import alphaTab.model.Score
import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns asynchronous preparation and explicit per-track/per-drum bank assignment. */
class FactoryPlaybackSetup(
    private val activity: Activity,
    private val isCurrent: (Score) -> Boolean,
    private val busy: (Boolean) -> Unit,
    private val openLibrary: () -> Unit,
    private val ready: (Score, FactoryScoreRenderer.Result) -> Unit
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = activity.getSharedPreferences("factory-routes", 0)
    private var active: AtomicBoolean? = null
    private var dialog: AlertDialog? = null
    private var closed = false
    @Volatile private var preparingFile: File? = null
    private fun ui(action: () -> Unit) = activity.runOnUiThread { if (!closed && !activity.isFinishing) action() }
    fun cancel() {
        active?.set(true); active = null; preparingFile?.delete(); preparingFile = null
        dialog?.dismiss(); if (!closed) busy(false)
    }
    fun close() { cancel(); closed = true; worker.shutdownNow() }

    fun show(score: Score, settings: Settings, initialTransposition: Int) {
        cancel()
        val flag = AtomicBoolean(false); active = flag
        busy(true)
        dialog = AlertDialog.Builder(activity).setTitle("读取演奏信息")
            .setMessage("正在展开反复段、速度和音符…").setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancel() }.show()
        worker.execute {
            try {
                val performance = RseSlidePlayback.compile(score, settings) { flag.get() }
                val banks = BankLibrary(File(activity.filesDir, "rse-banks")).list()
                ui {
                    if (flag.get() || active !== flag || !isCurrent(score)) return@ui
                    dialog?.dismiss(); busy(false)
                    if (banks.isEmpty()) {
                        active = null
                        dialog = AlertDialog.Builder(activity).setTitle("请先导入音色库")
                            .setMessage("选择 .gp7bank 文件后，可以用其中的原厂采样播放乐谱。")
                            .setPositiveButton("音色库") { _, _ -> openLibrary() }.setNegativeButton("关闭", null).show()
                    } else configure(score, performance, banks, initialTransposition)
                }
            } catch (error: Exception) { failed(flag, error) }
        }
    }
    private fun failed(flag: AtomicBoolean, error: Exception) = ui {
        if (active !== flag || flag.get()) return@ui
        active = null; dialog?.dismiss(); busy(false)
        dialog = AlertDialog.Builder(activity).setTitle("无法准备原厂采样播放")
            .setMessage(error.message ?: "请检查乐谱和音色库后重试。")
            .setPositiveButton("关闭", null).show()
    }
    private fun configure(score: Score, performance: ScorePerformance, banks: List<BankLibrary.Item>, initialTransposition: Int) {
        val solo = score.tracks.any { it.playbackInfo.isSolo }
        val muted = score.tracks.withIndex().filter { (_, track) ->
            (track.playbackInfo.isMute && !track.playbackInfo.isSolo) || (solo && !track.playbackInfo.isSolo)
        }.map { it.index }.toSet()
        val routes = performance.notes.filter { it.track !in muted }.map { note ->
            FactoryScoreRenderer.Route(note.track, if (score.tracks[note.track].isPercussion) note.midi else null)
        }.distinct().sortedWith(compareBy<FactoryScoreRenderer.Route> { it.track }.thenBy { it.percussionMidi ?: -1 })
        if (routes.isEmpty()) {
            active = null
            dialog = AlertDialog.Builder(activity).setTitle("没有参与播放的声部")
                .setMessage("请在混音设置中取消静音，或选择有音符的乐谱。")
                .setPositiveButton("关闭", null).show()
            return
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding/2, padding, padding/2) }
        fun label(text: String) = TextView(activity).apply { this.text = text; textSize = 14f; setPadding(0,16,0,8); form.addView(this) }
        label("为参与播放的声部选择音色库。鼓组按击打音分别分配。完整连奏、拾音器与效果链仍在还原。")
        val choices = arrayOf("请选择音色库") + banks.map { "${it.bank.name} · ${it.bank.id}" }
        val widgets = LinkedHashMap<FactoryScoreRenderer.Route, Spinner>()
        fun key(route: FactoryScoreRenderer.Route) = "bank.${score.tracks[route.track].playbackInfo.program.toInt()}.${route.percussionMidi ?: -1}"
        for (route in routes) {
            val track = score.tracks[route.track]
            label(track.name.ifBlank { "声部 ${route.track + 1}" } + (route.percussionMidi?.let { " · 击打音 $it" } ?: ""))
            val spinner = Spinner(activity)
            spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
            val saved = prefs.getString(key(route), null)
            val previous = banks.indexOfFirst { it.file.name == saved }
            if (previous >= 0) spinner.setSelection(previous + 1)
            else if (banks.size == 1) spinner.setSelection(1)
            widgets[route] = spinner; form.addView(spinner)
        }
        val transposeLabel = label("移调：${initialTransposition.coerceIn(-12,12)} 半音（鼓组不移调）")
        val transpose = SeekBar(activity).apply { max = 24; progress = initialTransposition.coerceIn(-12,12) + 12 }
        transpose.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) { transposeLabel.text = "移调：${progress - 12} 半音（鼓组不移调）" }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }); form.addView(transpose)
        val warning = label("准备完成后按播放。修改音色库、声部混音或音符后需要重新准备。")
        val setup = AlertDialog.Builder(activity).setTitle("原厂采样 · 按谱播放")
            .setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("准备播放", null)
            .setNegativeButton("取消") { _, _ -> cancel() }.create()
        dialog = setup; setup.show()
        setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (widgets.values.any { it.selectedItemPosition == 0 }) { warning.text = "请为每一项选择音色库。"; return@setOnClickListener }
            val bindings = widgets.mapValues { banks[it.value.selectedItemPosition - 1] }
            val edit = prefs.edit()
            bindings.forEach { (route, item) -> edit.putString(key(route), item.file.name) }; edit.apply()
            setup.dismiss()
            render(score, performance, bindings, muted, transpose.progress - 12)
        }
    }
    /** Also used by instrumentation to exercise the same preparation path with a known bank. */
    fun render(score: Score, performance: ScorePerformance, bindings: Map<FactoryScoreRenderer.Route, BankLibrary.Item>,
        mutedTracks: Set<Int>, transposition: Int) {
        cancel()
        val flag = AtomicBoolean(false); active = flag; busy(true)
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40,24,40,24) }
        val caption = TextView(activity).apply { text = "正在读取采样…" }; form.addView(caption)
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }; form.addView(progress)
        dialog = AlertDialog.Builder(activity).setTitle("准备原厂采样播放").setView(form).setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancel() }.show()
        worker.execute {
            val file = File(activity.cacheDir, "factory-score-${System.nanoTime()}.wav")
            preparingFile = file
            try {
                val result = FactoryScoreRenderer.render(performance, bindings, file, mutedTracks, transposition,
                    { flag.get() }) { percentage -> ui { if (active === flag && !flag.get()) {
                        caption.text = "正在准备 $percentage%"; progress.progress = percentage
                    } } }
                if (flag.get()) { file.delete(); return@execute }
                ui {
                    if (flag.get() || active !== flag || !isCurrent(score)) { file.delete(); return@ui }
                    preparingFile = null; active = null; dialog?.dismiss(); busy(false); ready(score, result)
                }
            } catch (error: Exception) { file.delete(); failed(flag, error) }
        }
    }
}
