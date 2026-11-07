package com.stringpage.app.rse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Explicit sample audition area; it does not advertise RSE score playback before that exists. */
class BankLibraryUi(private val activity: Activity, private val beforePreview: () -> Unit) {
    private val library = BankLibrary(File(activity.filesDir, "rse-banks"))
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }.build()
    private var dialog: AlertDialog? = null
    private var previewStopped: (() -> Unit)? = null
    private var closed = false
    private fun ui(action: () -> Unit) = activity.runOnUiThread { if (!closed && !activity.isFinishing) action() }
    fun stop() {
        generation.incrementAndGet(); audioManager.abandonAudioFocusRequest(focus)
        val stopped = previewStopped; previewStopped = null
        if (stopped != null) ui(stopped)
    }
    fun close() { closed = true; stop(); dialog?.dismiss(); executor.shutdownNow() }

    fun show() {
        stop(); dialog?.dismiss()
        val token = generation.get()
        executor.execute {
            val items = library.list()
            ui {
                if (token != generation.get()) return@ui
                dialog = AlertDialog.Builder(activity).setTitle("原厂音色库 · 采样预览")
                    .setItems(if (items.isEmpty()) arrayOf("尚未导入音色库") else items.map { "${it.bank.name} · ${it.bank.samples.size} 个采样" }.toTypedArray()) { _, index ->
                        if (items.isNotEmpty()) showBank(items[index])
                    }.setPositiveButton("导入音色库") { _, _ -> pick() }
                    .setNegativeButton("关闭", null).create().also { it.show() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pick() {
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_TITLE, "选择 .gp7bank 音色库，可多选")
        }, REQUEST_IMPORT)
    }

    fun acceptImport(uris: List<Uri>) {
        if (uris.isEmpty() || closed) return
        stop(); dialog?.dismiss()
        val token = generation.get()
        val progress = TextView(activity).apply { text = "正在检查音色库…"; setPadding(40, 24, 40, 24) }
        val pending = AlertDialog.Builder(activity).setTitle("导入音色库").setView(progress)
            .setNegativeButton("取消") { _, _ -> stop() }.setCancelable(false).create()
        dialog = pending; pending.show()
        executor.execute {
            var added = 0; var existing = 0; var failed = 0
            for ((index, uri) in uris.withIndex()) {
                if (token != generation.get()) break
                ui { if (token == generation.get()) progress.text = "正在导入 ${index + 1} / ${uris.size}" }
                try {
                    val result = activity.contentResolver.openInputStream(uri)?.use {
                        library.import(it) { token != generation.get() }
                    } ?: throw IllegalArgumentException("无法读取所选文件。")
                    if (result.added) added++ else existing++
                } catch (_: Exception) { if (token == generation.get()) failed++ }
            }
            ui {
                pending.dismiss()
                if (token == generation.get()) {
                    AlertDialog.Builder(activity).setTitle("导入结果")
                        .setMessage("已添加 $added 个，已存在 $existing 个，无法读取 $failed 个。\n支持 .gp7bank 音色库；原始文件保留在原位置。")
                        .setPositiveButton("查看音色库") { _, _ -> show() }.setNegativeButton("关闭", null).show()
                }
            }
        }
    }

    private fun showBank(item: BankLibrary.Item) {
        stop()
        val bank = item.bank
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding / 2, padding, padding / 2) }
        fun label(value: String) = TextView(activity).apply { text = value; textSize = 14f; setPadding(0, 12, 0, 8); form.addView(this) }
        label("试听原厂录音采样及延音循环。音源菜单可选择原厂采样按谱播放；完整 RSE 技法与效果仍在还原。")
        label("采样")
        val samples = Spinner(activity)
        samples.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, bank.samples.map {
            "${it.midi} · ${it.dynamic} · ${it.nodePath.joinToString(" / ")}" + if (it.string > 0) " · 弦 ${it.string}" else ""
        })
        form.addView(samples)
        label("拾音位置")
        val waves = Spinner(activity); form.addView(waves)
        val transposeLabel = label("移调：0 半音")
        val pitch = SeekBar(activity).apply { max = 24; progress = 12 }; form.addView(pitch)
        val state = label("每次试听约 2 秒。音量较低，可先调整设备音量。")
        fun updateWaves(index: Int) {
            stop()
            waves.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                bank.samples[index].waves.map { if (it.pickup == "none" || it.pickup.isBlank()) "默认" else it.pickup })
        }
        samples.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateWaves(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        waves.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { stop() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        pitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) { stop(); transposeLabel.text = "移调：${progress - 12} 半音" }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        updateWaves(0)
        val preview = AlertDialog.Builder(activity).setTitle(bank.name).setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("试听", null).setNegativeButton("关闭", null)
            .setNeutralButton("移除") { _, _ ->
                stop()
                AlertDialog.Builder(activity).setTitle("移除此音色库？").setMessage("只移除应用内的副本，原始文件保留。")
                    .setPositiveButton("移除") { _, _ ->
                        executor.execute {
                            try { library.remove(item); ui { show() } }
                            catch (_: Exception) { ui { Toast.makeText(activity, "移除失败，请重试。", Toast.LENGTH_SHORT).show() } }
                        }
                    }.setNegativeButton("取消") { _, _ -> showBank(item) }.show()
            }.create()
        preview.setOnDismissListener { stop() }
        preview.show(); dialog = preview
        preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            stop(); beforePreview()
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                state.text = "当前无法播放音频，请稍后重试。"
                return@setOnClickListener
            }
            val token = generation.get()
            val sample = bank.samples[samples.selectedItemPosition.coerceAtLeast(0)]
            val wave = sample.waves.getOrNull(waves.selectedItemPosition.coerceAtLeast(0)) ?: return@setOnClickListener
            val semitones = pitch.progress - 12
            state.text = "正在试听…"
            previewStopped = { state.text = "已停止试听" }
            executor.execute {
                try {
                    val metadata = BankAudition.play(item.file, wave, semitones) { token != generation.get() }
                    ui { if (token == generation.get()) {
                        previewStopped = null
                        state.text = "${metadata.sampleRate} Hz · ${if (metadata.channels == 2) "立体声" else "单声道"} · ${metadata.sampler?.loops?.size ?: 0} 个循环点"
                    } }
                } catch (_: Exception) {
                    ui { if (token == generation.get()) { previewStopped = null; state.text = "无法试听该采样，请尝试其他音色。" } }
                } finally { ui { if (token == generation.get()) audioManager.abandonAudioFocusRequest(focus) } }
            }
        }
    }

    companion object { const val REQUEST_IMPORT = 12 }
}
