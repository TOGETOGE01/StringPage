package com.stringpage.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class RecordingSyncDialog(
    private val activity: Activity,
    private val file: File,
    private val visits: List<ScoreRecording.Visit>,
    private val initialTick: Double,
    private val initialMs: Double,
    original: List<ScoreRecording.Point>,
    private val save: (List<ScoreRecording.Point>, Double) -> Unit
) {
    private var duration = 0.0
    private var points = original.toList()
    private var closed = false
    private var auditioning = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0) pausePreview() }, main)
        .build()
    private val message = TextView(activity).apply { text = "正在读取录音波形…" }
    private val preview = EmbeddedAudio { error ->
        pausePreview()
        message.text = error.message ?: "试听失败，请重选录音。"
    }
    private val play: Button = button("试听") { togglePreview() }
    private val inspect = button("同步点") { showPoints() }
    private val position = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine()
        setSelectAllOnFocus(true)
    }
    private val milliseconds = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        setSelectAllOnFocus(true)
    }
    private val measure = Spinner(activity).apply {
        contentDescription = "选择小节和演奏遍次"
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            visits.map { "第 ${it.bar + 1} 小节 · 第 ${it.occurrence + 1} 遍" })
    }
    private val wave = Wave(activity) { time ->
        pausePreview()
        seek(time)
    }
    private val clockLabel = TextView(activity).apply { textSize = 17f; setTextColor(AppUi.ink) }
    private val auditionPoll = object : Runnable {
        override fun run() {
            if (!auditioning || closed) return
            val time = preview.currentPositionMs
            if (time != null) {
                showCursor(time)
                if (time >= duration - 15.0) { pausePreview(); return }
            }
            main.postDelayed(this, 40)
        }
    }

    fun show(): AlertDialog {
        val selected = visits.indexOfLast { it.start <= initialTick }.coerceAtLeast(0)
        val visit = visits[selected]
        measure.setSelection(selected)
        val ratio = if (visit.end > visit.start) ((initialTick - visit.start) / (visit.end - visit.start)).coerceIn(0.0, 1.0) else 0.0
        position.setText(percentage((ratio * 10000).roundToInt() / 10000.0))
        updatePoints()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
            isFocusableInTouchMode = true
            addView(measure, LinearLayout.LayoutParams(-1, dp(48)))
            addView(field("小节内位置（%）", position))
            addView(wave, LinearLayout.LayoutParams(-1, dp(104)))
            addView(message)
            addView(clockLabel)
            addView(field("录音时间（毫秒）", milliseconds))
            addView(row(play, button("−50 ms") { adjust(-50) }, button("+50 ms") { adjust(50) }))
            addView(row(inspect, button("添加或更新") { updatePoint(false) }, button("删除此点") { updatePoint(true) }))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("录音同步点")
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("保存同步点", null).create()
        dialog.setOnDismissListener {
            closed = true
            pausePreview()
            preview.release()
            worker.shutdownNow()
        }
        dialog.show()
        val commit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        play.isEnabled = false
        commit.isEnabled = false
        commit.setOnClickListener {
            protect {
                val ordered = ScoreRecording.ordered(points, visits, duration)
                dialog.dismiss()
                save(ordered, duration)
            }
        }
        try {
            preview.load(file) {
                if (closed) return@load
                duration = preview.backingTrackDuration
                if (duration <= 0.0) return@load
                seek(initialMs)
                play.isEnabled = true
                commit.isEnabled = true
                worker.execute {
                    val peaks = runCatching { RecordingWaveform.read(file, duration) }
                    main.post {
                        if (closed) return@post
                        peaks.onSuccess { values ->
                            wave.setPeaks(values)
                            message.visibility = View.GONE
                        }.onFailure { message.text = "波形无法显示，可使用试听和时间输入。" }
                    }
                }
            }
        } catch (error: Exception) {
            message.text = error.message ?: "无法打开录音。"
        }
        return dialog
    }

    private fun currentPoint(): ScoreRecording.Point {
        val visit = visits[measure.selectedItemPosition.coerceAtLeast(0)]
        val fraction = position.text.toString().toDoubleOrNull()?.div(100.0)
        require(fraction != null && fraction in 0.0..1.0) { "小节内位置请输入 0–100%。" }
        return ScoreRecording.Point(visit.bar, visit.occurrence, fraction, timeInput())
    }

    private fun timeInput(): Double {
        val time = milliseconds.text.toString().toDoubleOrNull()
        require(time != null && time in 0.0..duration && duration > 0.0) { "请输入录音范围内的时间。" }
        return time
    }

    private fun updatePoint(remove: Boolean) = protect {
        pausePreview()
        val point = currentPoint()
        val remaining = points.filterNot { ScoreRecording.samePosition(it, point) }
        if (remove) require(remaining.size < points.size) { "这个谱面位置还没有同步点。" }
        points = ScoreRecording.ordered(if (remove) remaining else remaining + point, visits, duration)
        updatePoints()
    }

    private fun showPoints() {
        if (points.isEmpty()) { toast("暂无同步点。"); return }
        pausePreview()
        val items = points.map { "第 ${it.bar + 1} 小节 · 第 ${it.occurrence + 1} 遍 · ${percentage(it.position)}% · ${clock(it.timeMs)}" }
        AlertDialog.Builder(activity).setTitle("选择同步点")
            .setItems(items.toTypedArray()) { _, index ->
                val point = points[index]
                val visit = visits.indexOfFirst { it.bar == point.bar && it.occurrence == point.occurrence }
                if (visit < 0) toast("当前播放顺序中没有这一小节。")
                else {
                    measure.setSelection(visit)
                    position.setText(percentage(point.position))
                    seek(point.timeMs)
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun updatePoints() {
        inspect.text = "同步点 ${points.size}"
        wave.times = points.map { it.timeMs }
    }

    private fun adjust(delta: Int) = protect {
        pausePreview()
        seek(timeInput() + delta)
    }

    private fun togglePreview() = protect {
        if (auditioning) { pausePreview(); return@protect }
        val time = timeInput()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            toast("当前无法取得音频播放权限。")
            return@protect
        }
        seek(if (time >= duration) 0.0 else time)
        auditioning = true
        play.text = "暂停"
        preview.play()
        main.post(auditionPoll)
    }

    private fun pausePreview() {
        if (!auditioning) return
        auditioning = false
        main.removeCallbacks(auditionPoll)
        preview.pause()
        preview.currentPositionMs?.let(::showCursor)
        audio.abandonAudioFocusRequest(focus)
        play.text = "试听"
    }

    private fun seek(time: Double) {
        if (duration <= 0.0) return
        val target = time.coerceIn(0.0, duration)
        preview.seekTo(target)
        showCursor(target)
    }

    private fun showCursor(time: Double) {
        wave.update(time, duration)
        val value = time.roundToLong().toString()
        if (milliseconds.text.toString() != value) milliseconds.setText(value)
        clockLabel.text = "${clock(time)} / ${clock(duration)}"
    }

    private fun percentage(value: Double) = BigDecimal.valueOf(value).movePointRight(2).stripTrailingZeros().toPlainString()
    private fun clock(ms: Double): String {
        val millis = ms.roundToLong().coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000)
    }
    private fun field(label: String, input: EditText) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        input.id = View.generateViewId()
        addView(TextView(activity).apply { text = label; labelFor = input.id }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    private fun row(vararg buttons: Button) = LinearLayout(activity).apply {
        for (button in buttons) addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    private fun button(caption: String, run: () -> Unit) = Button(activity).apply {
        text = caption
        isAllCaps = false
        setOnClickListener { run() }
    }
    private fun protect(run: () -> Unit) {
        try { run() } catch (error: IllegalArgumentException) { toast(error.message ?: "请检查同步点。") }
    }
    private fun toast(value: String) { Toast.makeText(activity, value, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int) = AppUi.dp(activity, value)

    private class Wave(context: Context, private val seek: (Double) -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var peaks = FloatArray(0)
        private var duration = 0.0
        private var cursor = 0.0
        var times: List<Double> = emptyList()
            set(value) { field = value; invalidate() }

        init {
            setBackgroundColor(AppUi.control)
            // The time field and audition buttons provide keyboard and screen reader access.
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setPeaks(values: FloatArray) { peaks = values; invalidate() }
        fun update(time: Double, end: Double) { cursor = time; duration = end; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val mid = height / 2f
            paint.color = AppUi.muted
            val stride = (peaks.size / width.coerceAtLeast(1)).coerceAtLeast(1)
            for (first in peaks.indices step stride) {
                var peak = 0f
                for (index in first until minOf(first + stride, peaks.size)) peak = maxOf(peak, peaks[index])
                val x = first.toFloat() / peaks.size * width
                val level = peak.coerceAtLeast(0.02f) * mid
                canvas.drawLine(x, mid - level, x, mid + level, paint)
            }
            if (duration <= 0.0) return
            paint.color = AppUi.line
            paint.strokeWidth = 2f
            for (time in times) {
                val x = ((time / duration).coerceIn(0.0, 1.0) * width).toFloat()
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
            paint.color = AppUi.accent
            val x = ((cursor / duration).coerceIn(0.0, 1.0) * width).toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            paint.strokeWidth = 1f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (duration <= 0.0 || width <= 0) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
            }
            if (event.actionMasked != MotionEvent.ACTION_CANCEL) seek((event.x / width).coerceIn(0f, 1f) * duration)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick() = super.performClick()
    }
}
