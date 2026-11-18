@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.model.Score
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class GuitarAmpSetup(
    private val activity: Activity,
    private val isCurrent: (Score) -> Boolean,
    private val busy: (Boolean) -> Unit,
    private val applyLive: (Map<Int, GuitarAmpSettings>) -> Boolean,
    private val ready: (Score, AmpStems, Map<Int, GuitarAmpSettings>) -> Unit,
    private val trackSlot: (Int) -> Int
) : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val mixer = SynthAmpMix(activity.cacheDir) { activity.assets.open("sonivox.sf2").use { it.readBytes() } }
    private val store = activity.getSharedPreferences("practice-guitar-amps", Activity.MODE_PRIVATE)
    private val presets = GuitarPresetLibrary(activity.getSharedPreferences("guitar-tone-presets", Activity.MODE_PRIVATE))
    private var active: AtomicBoolean? = null
    private var dialog: AlertDialog? = null
    private var cabinetImport: ((Uri?) -> Unit)? = null
    private val transient = mutableMapOf<Int, GuitarAmpSettings>()
    private var closed = false

    fun settingsFor(score: Score, document: String?): Map<Int, GuitarAmpSettings> {
        val prefix = document?.let(::preferenceKey)
        return score.tracks.toList().mapIndexedNotNull { index, track ->
            if (!GuitarTone.supports(track)) return@mapIndexedNotNull null
            val slot = trackSlot(index)
            val config = if (prefix == null || slot < 0) transient[slot]
            else store.getString("$prefix/$slot", null)?.let(GuitarAmpPreferences::decode)
            config?.let { index to it }
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
            enabled = !live,
            volume = (track.playbackInfo.volume * 75 / 16).toInt().coerceIn(0, 100),
            pan = ((track.playbackInfo.balance - 8) * 12.5).toInt().coerceIn(-100, 100)
        )
        var appliedPreview = current
        var committed = false
        var panelOpen = true
        lateinit var panel: GuitarEffectsPanel
        panel = GuitarEffectsPanel(activity, initial, presets, changed = {
            if (live && panelOpen && !committed && isCurrent(score)) {
                val values = current + (slot to panel.settings())
                if (values != appliedPreview && applyLive(values)) appliedPreview = values
            }
        }, pickCabinet = ::pickCabinet)
        dialog = AlertDialog.Builder(activity).setTitle("${track.name.ifBlank { "声部 ${slot + 1}" }} · 吉他效果器")
            .setView(panel.view)
            .setNegativeButton("取消", null)
            .setOnDismissListener { dismissed ->
                panelOpen = false
                if (dialog === dismissed) cabinetImport = null
                if (!committed && appliedPreview != current && isCurrent(score)) applyLive(current)
            }
            .setPositiveButton("应用") { _, _ ->
                committed = true
                val config = panel.settings()
                val values = current + (slot to config)
                if (applyLive(values) || !config.enabled) {
                    save(document, slot, config)
                } else prepare(score, settings, tones, values, transpose) {
                    save(document, slot, config)
                }
            }.show().also { dialog ->
                if (panel.wide) {
                    val width = minOf(AppUi.dp(activity, 960), (activity.resources.displayMetrics.widthPixels * 0.9).toInt())
                    dialog.window?.setLayout(width, WRAP_CONTENT)
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun pickCabinet(result: (CabinetImpulse?) -> Unit) {
        val owner = dialog ?: return
        val applyButton = owner.getButton(AlertDialog.BUTTON_POSITIVE)
        if (cabinetImport != null || !applyButton.isEnabled) return
        applyButton.isEnabled = false
        fun complete(imported: Result<CabinetImpulse>?) {
            if (dialog !== owner || !owner.isShowing || closed) return
            applyButton.isEnabled = true
            applyButton.text = "应用"
            result(imported?.getOrNull())
            imported?.exceptionOrNull()?.let { failure ->
                AlertDialog.Builder(activity).setTitle("无法导入箱体 IR")
                    .setMessage(failure.message ?: "请检查 WAV 文件。")
                    .setPositiveButton("关闭", null).show()
            }
        }
        cabinetImport = { uri ->
            if (uri == null) complete(null)
            else {
                applyButton.text = "正在读取 IR"
                worker.execute {
                    val imported = runCatching {
                        val resolver = activity.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "自定义箱体 IR"
                        resolver.openInputStream(uri)?.use { CabinetImpulse.readWave(name, it) }
                            ?: error("无法读取所选 WAV 文件。")
                    }
                    activity.runOnUiThread { complete(imported) }
                }
            }
        }
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_CABINET)
        } catch (failure: Exception) {
            cabinetImport = null
            complete(Result.failure(failure))
        }
    }

    fun acceptCabinet(uri: Uri?) {
        val result = cabinetImport
        cabinetImport = null
        result?.invoke(uri)
    }

    fun cancel() {
        active?.let { it.set(true); active = null; busy(false) }
        cabinetImport = null
        dialog?.dismiss()
        dialog = null
    }

    fun discardScore() {
        cancel()
        transient.clear()
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

    private fun save(document: String?, index: Int, config: GuitarAmpSettings) {
        val slot = trackSlot(index)
        if (document == null || slot < 0) {
            transient[slot] = config
            return
        }
        val key = "${preferenceKey(document)}/$slot"
        store.edit().putString(key, GuitarAmpPreferences.encode(config)).apply()
    }

    override fun close() {
        discardScore()
        closed = true
        worker.shutdown()
    }

    private fun preferenceKey(document: String) =
        MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding / 2)
    }

    private fun caption(value: String) = TextView(activity).apply { text = value; textSize = 14f; setTextColor(AppUi.ink) }

    companion object { const val REQUEST_CABINET = 13 }
}
