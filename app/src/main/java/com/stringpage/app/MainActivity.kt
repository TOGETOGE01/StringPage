@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.PlayerMode
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import alphaTab.synth.PlaybackRange
import alphaTab.synth.PlayerState
import alphaTab.synth.IExternalMediaSynthOutput
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.KeyEvent
import android.text.TextUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Offline GP reader. Embedded audio and the GP8 sync map stay inside alphaTab's data model. */
class MainActivity : AppCompatActivity() {
    private val ink = AppUi.ink
    private val mint = AppUi.mint
    private val paper = AppUi.canvas
    private val teal = AppUi.accent
    private val muted = AppUi.muted
    private val io = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var loading = false
    private var score: Score? = null
    private var audioMode = true
    private var factoryMode = false
    private var factoryResult: com.stringpage.app.rse.FactoryScoreRenderer.Result? = null
    private var activeTrack = 0
    private var playing = false
    private var duration = 0.0
    private var currentTick = 0.0
    private var currentSpeed = 1.0
    private var hasAudio = false
    private var fileLabel = ""
    private var embeddedFile: File? = null
    private var embeddedReady = false
    private var midiReady = false
    private val embedded by lazy { EmbeddedAudio { error ->
        sheet.api.pause(); embeddedReady = false; setControlsReady(false)
        showError("内嵌音频播放失败", error)
    } }
    private lateinit var sheet: AlphaTabView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var source: Button
    private lateinit var status: TextView
    private lateinit var empty: LinearLayout
    private lateinit var play: Button
    private lateinit var track: Button
    private lateinit var loop: Button
    private lateinit var speed: Button
    private lateinit var zoom: Button
    private lateinit var progress: SeekBar
    private lateinit var position: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var sidebar: ScrollView
    private lateinit var inspector: ScrollView
    private lateinit var infoPanel: LinearLayout
    private lateinit var documentHeading: LinearLayout
    private lateinit var scoreTools: LinearLayout
    private lateinit var scoreHost: FrameLayout
    private lateinit var scoreFrame: FrameLayout
    private lateinit var headerToolbar: LinearLayout
    private lateinit var transport: LinearLayout
    private lateinit var speedReadout: TextView
    private lateinit var bottomContent: LinearLayout
    private lateinit var timeline: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var voiceList: LinearLayout
    private lateinit var audioDetails: TextView
    private lateinit var sourceNotice: TextView
    private lateinit var barCounter: TextView
    private lateinit var fullScreen: Button
    private lateinit var notation: Button
    private lateinit var inlineFrom: EditText
    private lateinit var inlineTo: EditText
    private lateinit var applyLoop: Button
    private lateinit var clearLoop: Button
    private val speedPresets = mutableListOf<Button>()
    private var fullSheet = false
    private var currentWidth = 0
    private var modified = false
    private var restoreTick: Double? = null
    private var exportFile: File? = null
    private var exportFormat: ScoreExport.Format? = null
    private var exportScore: Score? = null
    private val drafts by lazy { DraftStore(this) }
    private val trainer = ProgressivePractice()
    private var mixChanged = false
    private lateinit var moreButton: Button
    private lateinit var recentHeader: Button
    private val practice by lazy { PlaybackControls(this, { sheet.api }, { !audioMode }, { switchSource(false) },
        { editor.beginExternalChange() }, { modified = true; mixChanged = true; updateDocumentSubtitle() },
        { if (mixChanged) { editor.commitExternalChange("修改声部混音"); mixChanged = false; saveDraft() } },
        { if (factoryMode) "原厂采样播放已按当前声部混音准备。修改混音、节拍器或预备拍时，请先切换至合成音源；之后可以重新准备原厂采样播放。"
          else "内嵌录音是完整音频，不能按乐谱声部分别静音或独奏。轨道混音、节拍器、预备拍和播放移调在合成音源模式下使用。" }) }
    private val editor by lazy { BasicScoreEditor(this, { score }, this::applyEditedScore, { sheet.settings }) }
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val library by lazy { RecentLibrary(prefs) }
    private val recentUi by lazy { RecentScoresUi(this, library, this::openUri, this::pickFile) }
    private val documentReader by lazy { GpDocumentReader(this) }
    private var importing = false
    private var batchCancellation: AtomicBoolean? = null
    private var batchDialog: AlertDialog? = null
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { if (it < 0) runOnUiThread { sheet.api.pause() } }
            .build()
    }
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (bankLibraryDelegate.isInitialized()) bankLibrary.stop()
            sheet.api.pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!factoryCacheSwept) {
            factoryCacheSwept = true
            cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("factory-score-") && it.extension == "wav" }
                ?.forEach { it.delete() }
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildUi()
        wirePlayer()
        savedInstanceState?.getString("exportPath")?.let { path ->
            val pending = File(path)
            if (pending.parentFile?.canonicalPath == cacheDir.canonicalPath && pending.isFile) exportFile = pending
        }
        savedInstanceState?.getString("exportFormat")?.let { runCatching { ScoreExport.Format.valueOf(it) }.getOrNull()?.let { exportFormat = it } }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unplugged, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(unplugged, filter)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { guardDocumentChange { finish() } }
        })
        val initial = {
            if (intent.action == Intent.ACTION_VIEW && intent.data != null) openUri(intent.data!!)
            else if (savedInstanceState?.getString("uri") != null) openUri(Uri.parse(savedInstanceState.getString("uri")))
        }
        if (drafts.exists()) AlertDialog.Builder(this).setTitle("恢复未导出的修改")
            .setMessage("发现本机草稿。可恢复继续编辑，或丢弃后打开文件。草稿仅保留当前模型支持的数据。")
            .setPositiveButton("恢复草稿") { _, _ -> loadFile({ "恢复的草稿.gp" to drafts.read() }, null, true) }
            .setNegativeButton("丢弃草稿") { _, _ -> io.execute { drafts.delete() }; initial() }
            .setCancelable(false).show()
        else initial()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, dark: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 13f; isAllCaps = false
        setTextColor(if (dark) Color.WHITE else ink)
        background = AppUi.ripple(this@MainActivity, if (dark) teal else Color.WHITE, 10, if (dark) null else AppUi.line)
        elevation = 0f; stateListAnimator = null
        minWidth = 0; minimumWidth = 0; minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addWeighted(v: View, weight: Float = 1f, height: Int = 48) {
        addView(v, LinearLayout.LayoutParams(0, dp(height), weight).apply { marginEnd = dp(6) })
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        headerToolbar = row().apply { setBackgroundColor(Color.WHITE); setPadding(dp(24), dp(4), dp(20), dp(4)) }
        val logo = ImageView(this).apply {
            setImageDrawable(AppUi.icon(this@MainActivity, "audio", teal, 22))
            background = shape(AppUi.selected, 10); setPadding(dp(8), dp(8), dp(8), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        headerToolbar.addView(logo, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        val brand = text("弦页", 20f).apply { setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); setOnClickListener { showAbout() } }
        headerToolbar.addWeighted(brand, 1f, 48)
        fun headerAction(label: String, icon: String, action: () -> Unit) = button(label, action = action).apply {
            background = AppUi.ripple(this@MainActivity, Color.TRANSPARENT, 10)
            AppUi.iconButton(this, icon, muted)
        }
        recentHeader = headerAction("最近", "clock") { showRecents() }
        headerToolbar.addView(recentHeader)
        fullScreen = headerAction("全屏看谱", "full") { fullSheet = !fullSheet; applyAdaptiveLayout(currentWidth) }.apply { id = R.id.full_screen }
        headerToolbar.addView(fullScreen)
        headerToolbar.addView(button("导入", true) { pickFile() }.apply {
            id = R.id.import_file; AppUi.iconButton(this, "import", Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8); marginEnd = dp(6) }
        })
        moreButton = headerAction("更多", "more") { showMore() }.apply { id = R.id.more_actions }
        headerToolbar.addView(moreButton)
        root.addView(headerToolbar, LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(View(this).apply { setBackgroundColor(AppUi.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        val body = row().apply { gravity = Gravity.TOP; setBackgroundColor(paper) }
        val workspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        sidebar = ScrollView(this).apply {
            id = R.id.library_panel; setBackgroundColor(Color.WHITE); isFillViewport = true; isVerticalScrollBarEnabled = false
        }
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)) }
        library.addView(sectionLabel("乐谱文件"))
        library.addView(button("导入 GP 文件") { pickFile() }.apply {
            gravity = Gravity.CENTER_VERTICAL; AppUi.iconButton(this, "import", teal)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        library.addView(button("内置练习曲") { openDemo() }.apply {
            gravity = Gravity.CENTER_VERTICAL; AppUi.iconButton(this, "file", muted)
            background = AppUi.ripple(this@MainActivity, Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        library.addView(recentUi.section)
        library.addView(sectionLabel("声部"))
        voiceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        library.addView(voiceList)
        sourceNotice = text("", 11f, teal).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = shape(AppUi.selected, 10)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) }
        }
        library.addView(sourceNotice)
        sidebar.addView(library)
        body.addView(sidebar, LinearLayout.LayoutParams(dp(208), -1))
        body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))

        infoPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(paper); setPadding(dp(24), dp(16), dp(24), dp(10))
        }
        documentHeading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = text("未打开乐谱", 22f).apply {
            id = R.id.score_title; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = text("GP3 / GP4 / GP5 / GPX / GP", 12f, muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        }
        documentHeading.addView(title); documentHeading.addView(subtitle)
        infoPanel.addView(documentHeading)
        scoreTools = row()
        source = button("音源") { chooseSource() }.apply { id = R.id.audio_source; AppUi.iconButton(this, "audio", teal) }
        track = button("声部") { chooseTrack() }.apply { id = R.id.track_select; AppUi.iconButton(this, "track", muted) }
        zoom = button("85%") { chooseZoom() }.apply { AppUi.iconButton(this, "zoom", muted) }
        scoreTools.addWeighted(source, 1.1f, 44); scoreTools.addWeighted(track, 1.5f, 44); scoreTools.addWeighted(zoom, 0.85f, 44)
        infoPanel.addView(scoreTools); workspace.addView(infoPanel)

        scoreHost = FrameLayout(this)
        scoreFrame = FrameLayout(this).apply {
            background = shape(Color.WHITE, 12); clipToOutline = true; elevation = dp(1).toFloat()
        }
        sheet = AlphaTabView(this, null).apply {
            id = R.id.score_view
            barCursorFillColor = Color.argb(24, 8, 120, 103)
            beatCursorFillColor = Color.argb(185, 8, 120, 103)
            selectionFillColor = Color.argb(45, 8, 120, 103)
            settings.display.scale = 0.85
            settings.display.padding = alphaTab.collections.DoubleList().apply { push(20.0); push(24.0); push(20.0); push(24.0) }
            settings.player.playerMode = PlayerMode.EnabledSynthesizer
            settings.player.enableUserInteraction = true
            api.updateSettings()
        }
        scoreFrame.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20)); setBackgroundColor(Color.WHITE)
        }
        empty.addView(ImageView(this).apply {
            setImageDrawable(AppUi.icon(this@MainActivity, "file", teal, 32)); background = shape(AppUi.selected, 20)
            setPadding(dp(20), dp(20), dp(20), dp(20)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(24) })
        empty.addView(text("打开乐谱", 24f).apply { gravity = Gravity.CENTER; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)) })
        empty.addView(text("选择本地 GP 文件，读取谱面与内嵌音频。", 13f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(24))
        })
        empty.addView(button("导入 GP 文件", true) { pickFile() }.apply { AppUi.iconButton(this, "import", Color.WHITE) }, LinearLayout.LayoutParams(dp(216), dp(48)))
        empty.addView(button("内置练习曲") { openDemo() }.apply {
            background = AppUi.ripple(this@MainActivity, Color.WHITE)
        }, LinearLayout.LayoutParams(dp(216), dp(48)).apply { topMargin = dp(8) })
        empty.addView(text("GP3 · GP4 · GP5 · GPX · GP", 11f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0)
        })
        scoreFrame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        loadingIndicator = ProgressBar(this).apply { visibility = View.GONE }
        scoreFrame.addView(loadingIndicator, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        scoreHost.addView(scoreFrame, FrameLayout.LayoutParams(-1, -1))
        workspace.addView(scoreHost, LinearLayout.LayoutParams(-1, 0, 1f))

        inspector = ScrollView(this).apply { id = R.id.practice_panel; setBackgroundColor(paper); isFillViewport = true; isVerticalScrollBarEnabled = false }
        val practice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(12), dp(16), dp(12)) }
        val inspectorHeading = row()
        inspectorHeading.addWeighted(text("练习", 14f).apply { setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)) }, 1f, 44)
        barCounter = text("小节 —", 11f, muted)
        inspectorHeading.addView(barCounter); practice.addView(inspectorHeading)
        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(12))
            background = AppUi.surface(this@MainActivity, Color.WHITE, 12, AppUi.line)
            practice.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        val tempoCard = card()
        val tempoHeading = row()
        tempoHeading.addWeighted(text("播放速度", 12f, muted), 1f, 32)
        speedReadout = text("100%", 23f, teal).apply { setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)) }
        tempoHeading.addView(speedReadout); tempoCard.addView(tempoHeading)
        for (values in listOf(listOf(0.5, 0.75), listOf(1.0, 1.25))) {
            val presets = row()
            for (value in values) {
                val preset = button("${(value * 100).toInt()}%") { changeSpeed(value) }
                preset.tag = value; speedPresets.add(preset); presets.addWeighted(preset, 1f, 42)
            }
            tempoCard.addView(presets, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        }
        tempoCard.addView(button("逐轮加速练习") { showTrainer() }.apply {
            background = AppUi.ripple(this@MainActivity, Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            AppUi.iconButton(this, "tempo", teal)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        val loopCard = card()
        loopCard.addView(text("小节循环", 12f, muted), LinearLayout.LayoutParams(-1, dp(28)))
        val loopInputs = row()
        inlineFrom = numberField("起始小节"); inlineTo = numberField("结束小节")
        loopInputs.addWeighted(inlineFrom); loopInputs.addView(text(" – ", 14f, muted)); loopInputs.addWeighted(inlineTo)
        loopCard.addView(loopInputs)
        val loopActions = row().apply { setPadding(0, dp(8), 0, 0) }
        applyLoop = button("应用循环", true) {
            applyLoopRange(inlineFrom.text.toString().toIntOrNull() ?: 0, inlineTo.text.toString().toIntOrNull() ?: 0)
        }
        clearLoop = button("清除") { clearLoopRange() }
        loopActions.addWeighted(applyLoop, 1.6f, 44); loopActions.addWeighted(clearLoop, 1f, 44)
        loopCard.addView(loopActions)
        val displayCard = card()
        displayCard.addView(text("谱面显示", 12f, muted), LinearLayout.LayoutParams(-1, dp(28)))
        notation = button("五线谱 + 六线谱") { chooseNotation() }.apply { id = R.id.notation_select }
        displayCard.addView(notation, LinearLayout.LayoutParams(-1, dp(44)))
        displayCard.addView(button("回到播放位置") { sheet.api.scrollToCursor() }.apply {
            background = AppUi.ripple(this@MainActivity, Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        practice.addView(button("混音与练习设置") { this@MainActivity.practice.show() }.apply {
            AppUi.iconButton(this, "mix", teal)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        practice.addView(sectionLabel("主音量"))
        val volume = SeekBar(this).apply {
            max = 100; progress = 100; contentDescription = "播放音量"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, value: Int, user: Boolean) { if (user) sheet.api.masterVolume = value / 100.0 }
                override fun onStartTrackingTouch(view: SeekBar?) {}
                override fun onStopTrackingTouch(view: SeekBar?) {}
            })
        }
        practice.addView(volume, LinearLayout.LayoutParams(-1, dp(32)))
        practice.addView(sectionLabel("音频信息"))
        audioDetails = text("未打开乐谱", 11f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        practice.addView(audioDetails)
        inspector.addView(practice)
        body.addView(inspector, LinearLayout.LayoutParams(dp(248), -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(AppUi.line) }, LinearLayout.LayoutParams(-1, dp(1)))

        transport = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(20), dp(4), dp(20), dp(12))
        }
        status = text("未打开乐谱", 11f, muted).apply {
            id = R.id.playback_status; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), 0, 0, 0)
        }
        transport.addView(status, LinearLayout.LayoutParams(-1, dp(28)))
        bottomContent = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = shape(ink, 16); setPadding(dp(12), dp(8), dp(8), dp(8)) }
        timeline = row()
        progress = SeekBar(this).apply {
            max = 10000; progressTintList = android.content.res.ColorStateList.valueOf(mint)
            thumbTintList = android.content.res.ColorStateList.valueOf(mint)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(65, 85, 91))
            contentDescription = "播放进度"; id = R.id.progress
        }
        timeline.addWeighted(progress, 1f, 40)
        position = text("00:00 / 00:00", 12f, Color.rgb(191, 207, 210)).apply {
            id = R.id.time_position; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(8), 0, dp(8), 0)
        }
        timeline.addView(position); bottomContent.addView(timeline)
        controls = row()
        fun transportButton(button: Button, icon: String? = null): Button = button.apply {
            setTextColor(Color.WHITE); background = AppUi.ripple(this@MainActivity, Color.rgb(42, 63, 70), 12)
            icon?.let { AppUi.iconButton(this, it, Color.WHITE) }
        }
        val restart = transportButton(button("") { sheet.api.stop() }, "restart").apply {
            contentDescription = "回到开头"; tooltipText = "回到开头"; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0); compoundDrawablePadding = 0
        }
        controls.addView(restart, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
        play = button("播放", true) { togglePlayback() }.apply {
            id = R.id.play_pause; setTextColor(ink); background = AppUi.ripple(this@MainActivity, mint, 12)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); AppUi.iconButton(this, "play", ink)
        }
        controls.addWeighted(play, 1.1f)
        speed = transportButton(button("1.0×") { chooseSpeed() }).apply { id = R.id.speed_select }
        controls.addWeighted(speed, 0.8f)
        loop = transportButton(button("循环") { chooseLoop() }, "loop").apply { id = R.id.loop_select }
        controls.addWeighted(loop, 0.9f)
        bottomContent.addView(controls); transport.addView(bottomContent)
        root.addView(transport)
        setContentView(root)
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = ((right - left) / resources.displayMetrics.density).toInt()
            if (width != currentWidth) { currentWidth = width; applyAdaptiveLayout(width) }
        }
        currentWidth = resources.configuration.screenWidthDp
        applyAdaptiveLayout(currentWidth); refreshPanels(); setControlsReady(false)
    }

    private fun sectionLabel(value: String) = text(value, 12f, muted).apply {
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); setPadding(0, dp(20), 0, dp(10))
    }
    private fun numberField(label: String) = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(); textSize = 16f
        gravity = Gravity.CENTER; setTextColor(ink); setText("1"); contentDescription = label
        background = AppUi.surface(this@MainActivity, paper, 8, AppUi.line)
    }
    private fun applyAdaptiveLayout(width: Int) {
        if (!::sidebar.isInitialized) return
        sidebar.visibility = if (width >= 840 && !fullSheet) View.VISIBLE else View.GONE
        inspector.visibility = if (width >= 1200 && !fullSheet) View.VISIBLE else View.GONE
        infoPanel.visibility = if (fullSheet) View.GONE else View.VISIBLE
        fullScreen.text = if (fullSheet) "退出全屏" else "全屏看谱"
        fullScreen.visibility = if (width < 600) View.GONE else View.VISIBLE
        if (width < 600) {
            recentHeader.setCompoundDrawables(null, null, null, null)
            moreButton.setCompoundDrawables(null, null, null, null)
        } else {
            AppUi.iconButton(recentHeader, "clock", muted); AppUi.iconButton(moreButton, "more", muted)
        }
        val wide = width >= 840
        headerToolbar.setPadding(dp(if (wide) 24 else 16), dp(4), dp(if (wide) 20 else 8), dp(4))
        // One header row on tablets; stack the same controls on phones without detaching the score view.
        infoPanel.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        infoPanel.setPadding(dp(if (wide) 24 else 16), dp(if (wide) 16 else 14), dp(if (wide) 18 else 10), dp(12))
        documentHeading.layoutParams = if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
        scoreTools.layoutParams = LinearLayout.LayoutParams(if (wide) dp(384) else -1, dp(44)).apply {
            if (wide) marginStart = dp(20) else topMargin = dp(12)
        }
        title.textSize = if (wide) 22f else 21f
        if (wide) {
            AppUi.iconButton(source, "audio", teal); AppUi.iconButton(track, "track", muted); AppUi.iconButton(zoom, "zoom", muted)
        } else {
            listOf(source, track, zoom).forEach { it.setCompoundDrawables(null, null, null, null) }
        }
        scoreHost.setPadding(dp(if (wide) 20 else 0), dp(2), dp(if (wide) 16 else 0), dp(if (wide) 16 else 0))
        scoreFrame.background = shape(Color.WHITE, if (wide) 12 else 0)
        scoreFrame.elevation = if (wide) dp(1).toFloat() else 0f
        transport.setPadding(dp(if (wide) 20 else 12), dp(4), dp(if (wide) 20 else 12), dp(10))
        bottomContent.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        timeline.layoutParams = if (wide) LinearLayout.LayoutParams(0, dp(48), 1f) else LinearLayout.LayoutParams(-1, dp(38))
        controls.layoutParams = LinearLayout.LayoutParams(if (wide) dp(360) else -1, dp(48)).apply { if (wide) marginStart = dp(16) else topMargin = dp(4) }
        if (width < 380) loop.setCompoundDrawables(null, null, null, null)
        else AppUi.iconButton(loop, "loop", Color.WHITE)
        sheet.settings.display.barsPerRow = -1.0
        sheet.api.updateSettings()
        if (score != null) sheet.api.render()
    }

    private fun refreshPanels() {
        recentUi.refresh(); voiceList.removeAllViews()
        val loaded = score
        if (loaded == null) voiceList.addView(text("未打开乐谱", 12f, muted))
        else for ((index, voice) in loaded.tracks.withIndex()) {
            val label = voice.name.ifBlank { "声部 ${index + 1}" }
            val selected = index == activeTrack
            val choice = button(label) { selectTrack(index) }.apply {
                gravity = Gravity.CENTER_VERTICAL; setTextColor(if (selected) teal else ink)
                background = AppUi.ripple(this@MainActivity, if (selected) AppUi.selected else Color.WHITE)
                AppUi.iconButton(this, if (selected) "check" else "track", if (selected) teal else muted)
            }
            voiceList.addView(choice, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        }
        val syncCount = loaded?.masterBars?.sumOf { it.syncPoints?.length?.toInt() ?: 0 } ?: 0
        audioDetails.text = when {
            factoryMode -> "原厂采样 · 按谱播放\n${factoryResult?.noteCount ?: 0} 个演奏音符\n$fileLabel"
            hasAudio && audioMode -> "内嵌录音\n$syncCount 个同步点\n${fileLabel}"
            loaded != null -> "GeneralUser GS 1.44\n按谱合成演奏\n$fileLabel"
            else -> "未打开乐谱"
        }
        sourceNotice.text = when {
            factoryMode -> "按分配的音色库播放；完整连奏、拾音器及效果链仍在还原。"
            hasAudio && audioMode -> "切换声部只改变谱面显示，内嵌录音保持完整。"
            else -> ""
        }
        sourceNotice.visibility = if (sourceNotice.text.isEmpty()) View.GONE else View.VISIBLE
        source.setTextColor(if (audioMode) teal else ink)
        source.background = AppUi.ripple(this, if (audioMode && loaded != null) AppUi.selected else Color.WHITE, 10, if (audioMode && loaded != null) null else AppUi.line)
        speedReadout.text = "${(currentSpeed * 100).toInt()}%"
        speedPresets.forEach {
            it.isSelected = it.tag == currentSpeed
            it.setTextColor(if (it.isSelected) teal else ink)
            it.background = AppUi.ripple(this, if (it.isSelected) AppUi.selected else Color.WHITE, 8, if (it.isSelected) null else AppUi.line)
        }
    }

    private fun chooseNotation() {
        val values = arrayOf(alphaTab.StaveProfile.ScoreTab, alphaTab.StaveProfile.Tab, alphaTab.StaveProfile.Score)
        val names = arrayOf("五线谱 + 六线谱", "六线谱", "五线谱")
        AlertDialog.Builder(this).setTitle("谱面显示").setItems(names) { _, index ->
            sheet.settings.display.staveProfile = values[index]; notation.text = names[index]
            sheet.api.updateSettings(); sheet.api.render()
        }.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("弦页 ${packageManager.getPackageInfo(packageName, 0).versionName}")
            .setMessage("本地 GP 乐谱阅读与内嵌音频播放。\n\n乐谱引擎：alphaTab 1.8.4（MPL-2.0）\nhttps://alphatab.net\n\n字体：Bravura（SIL OFL）\n默认合成音色：GeneralUser GS SoftSynth 1.44\n作者：S. Christian Collins\n\n原厂采样按谱播放已接入；完整技法、拾音器与效果链正在还原。\n\n键盘：空格播放 / 暂停，Home 回到开头。")
            .setNeutralButton("开源许可") { _, _ -> showLicenses() }
            .setPositiveButton("关闭", null).show()
    }

    private fun showLicenses() {
        val names = assets.list("licenses") ?: return
        AlertDialog.Builder(this).setTitle("开源许可").setItems(names) { _, index ->
            val content = text(assets.open("licenses/${names[index]}").bufferedReader().use { it.readText() }, 12f)
                .apply { setPadding(dp(20), dp(16), dp(20), dp(16)); setTextIsSelectable(true) }
            val scroll = ScrollView(this).apply { addView(content) }
            AlertDialog.Builder(this).setTitle(names[index]).setView(scroll).setPositiveButton("关闭", null).show()
        }.setNegativeButton("关闭", null).show()
    }

    private fun setControlsReady(ready: Boolean) {
        (listOf(play, speed, loop, progress, applyLoop, clearLoop) + speedPresets).forEach {
            it.isEnabled = ready; it.alpha = if (ready) 1f else 0.4f
        }
        listOf(source, track, zoom, notation).forEach { it.isEnabled = score != null && !loading }
        moreButton.isEnabled = !loading
    }

    private fun wirePlayer() {
        sheet.api.error.on { error -> runOnUiThread {
            if (!isDestroyed) {
                setControlsReady(false)
                status.text = "播放或显示失败，可尝试切换音源或重新导入。"
                showError("无法处理这份谱子", error)
            }
        } }
        sheet.api.playerReady.on { runOnUiThread {
            if (!loading && score != null) {
                midiReady = true
                bindEmbeddedOutput()
                sheet.api.playbackSpeed = currentSpeed
                practice.reapply()
                restorePlaybackPosition()
                setControlsReady(!audioMode || embeddedReady)
            }
        } }
        sheet.api.playerStateChanged.on { e -> runOnUiThread {
            playing = e.state == PlayerState.Playing
            play.text = if (playing) "暂停" else "播放"
            AppUi.iconButton(play, if (playing) "pause" else "play", ink)
            if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); audioManager.abandonAudioFocusRequest(focus) }
        } }
        sheet.api.playerPositionChanged.on { e -> runOnUiThread {
            duration = e.endTime; currentTick = e.currentTick
            if (!progress.isPressed) progress.progress = if (duration > 0) (e.currentTime / duration * 10000).toInt() else 0
            position.text = "${formatTime(e.currentTime)} / ${formatTime(duration)}"
        } }
        sheet.api.playbackRangeChanged.on { runOnUiThread {
            if (sheet.api.playbackRange == null && sheet.api.isLooping) sheet.api.isLooping = false
            loop.text = if (sheet.api.playbackRange != null && sheet.api.isLooping) "循环中" else "循环"
        } }
        sheet.api.playedBeatChanged.on { beat -> runOnUiThread {
            barCounter.text = "小节 ${(beat.voice.bar.masterBar.index + 1).toInt()} / ${score?.masterBars?.length?.toInt() ?: 0}"
        } }
        sheet.api.playerFinished.on {
            // Post after alphaTab has rewound its loop position.
            play.post {
                if (sheet.api.isLooping && !audioMode) trainer.onLoopFinished()?.let { changeSpeed(it, false) }
            }
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(v: SeekBar?, value: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(v: SeekBar?) {}
            override fun onStopTrackingTouch(v: SeekBar?) {
                if (duration > 0) sheet.api.timePosition = duration * progress.progress / 10000.0
            }
        })
    }

    private fun formatTime(ms: Double): String {
        val seconds = (ms / 1000).toInt().coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        if (loading || importing) { Toast.makeText(this, "正在处理乐谱，请稍候。", Toast.LENGTH_SHORT).show(); return }
        startActivityForResult(importIntent(), 10)
    }

    internal fun importIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_TITLE, "选择 GP 乐谱，可多选")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    @Deprecated("Legacy activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.stringpage.app.rse.BankLibraryUi.REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null) bankLibrary.acceptImport(selectedImportUris(data))
            return
        }
        if (requestCode == 11) {
            val pending = exportFile; val format = exportFormat
            if (resultCode != RESULT_OK || data?.data == null) { pending?.delete(); exportFile = null; return }
            val destination = data.data!!
            if (pending == null || !pending.isFile || format == null) { status.text = "导出数据已失效，请重新导出。"; return }
            io.execute {
                try {
                    contentResolver.openOutputStream(destination, "w")?.use { output -> pending.inputStream().use { it.copyTo(output) } }
                        ?: throw IllegalStateException("无法写入所选位置。")
                    runOnUiThread {
                        status.text = if (format == ScoreExport.Format.GUITAR_PRO) "已导出 GP 兼容副本" else "已导出 MIDI"
                        if (format == ScoreExport.Format.GUITAR_PRO && score === exportScore) {
                            modified = false; updateDocumentSubtitle(); io.execute { drafts.delete() }
                        }
                    }
                } catch (error: Exception) { runOnUiThread { showError("导出失败", error) } }
                finally { pending.delete(); exportFile = null }
            }
            return
        }
        if (requestCode == 10 && resultCode == RESULT_OK && data != null) {
            acceptImportSelection(data)
        }
    }

    internal fun acceptImportSelection(data: Intent) {
        val uris = selectedImportUris(data)
        if (uris.isEmpty()) return
        if (uris.size > 1) {
            beginBatchImport(uris, data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
            return
        }
        val uri = uris.first()
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Some providers only grant access for this session. */ }
        openUri(uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.data?.let { openUri(it) }
    }

    internal fun openUri(uri: Uri) {
        if (importing) { Toast.makeText(this, "请先完成或取消批量导入。", Toast.LENGTH_SHORT).show(); return }
        guardDocumentChange { readUri(uri) }
    }

    private fun readUri(uri: Uri) {
        loadFile({ documentReader.read(uri) }, uri)
    }

    private fun beginBatchImport(uris: List<Uri>, persistGrants: Boolean) {
        if (loading || importing) { Toast.makeText(this, "正在处理乐谱，请稍候。", Toast.LENGTH_SHORT).show(); return }
        sheet.api.pause()
        importing = true; loading = true
        val token = ++generation
        val cancelled = AtomicBoolean(false); batchCancellation = cancelled
        setControlsReady(false); loadingIndicator.visibility = View.VISIBLE
        status.text = "正在批量导入 ${uris.size} 份乐谱…"
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val detail = text("逐份读取并校验 GP 文件", 13f, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = uris.size }
        form.addView(detail); form.addView(progress, LinearLayout.LayoutParams(-1, dp(32)))
        val dialog = AlertDialog.Builder(this).setTitle("批量导入 · 0 / ${uris.size}").setView(form)
            .setNegativeButton("取消导入") { _, _ -> cancelled.set(true); status.text = "正在取消，已校验成功的乐谱会保留。" }
            .setCancelable(false).create()
        batchDialog = dialog; dialog.show()
        io.execute {
            try {
                val report = GpBatchImporter(this, library).run(uris, persistGrants, cancelled) { done, total, name ->
                    runOnUiThread {
                        if (!isDestroyed && token == generation) {
                            progress.progress = done; detail.text = name
                            dialog.setTitle("批量导入 · $done / $total")
                        }
                    }
                }
                runOnUiThread {
                    if (isDestroyed || token != generation) return@runOnUiThread
                    finishBatchUi()
                    refreshPanels()
                    val heading = if (report.cancelled) "批量导入已取消" else "批量导入完成"
                    status.text = "$heading · 成功 ${report.imported.size} 份，失败 ${report.failures.size} 份"
                    val openFirst = score == null && !modified && report.imported.isNotEmpty()
                    val message = buildString {
                        append("已加入 ${report.imported.size} 份，未导入 ${report.failures.size} 份。重复选择的同一文件只保留一条记录。")
                        if (report.cancelled) append("\n未处理 ${report.total - report.imported.size - report.failures.size} 份。")
                        append(if (openFirst) "\n已打开第一份，可在最近打开中选择其他乐谱。" else "\n当前乐谱和未导出的修改均保留。")
                        if (report.transientGrants > 0) append("\n其中 ${report.transientGrants} 份未获得长期访问权限，重启后可能需要重新选择。")
                        report.failures.take(6).forEach { append("\n\n${it.fileName}\n${it.reason}") }
                        if (report.failures.size > 6) append("\n\n另有 ${report.failures.size - 6} 份未导入。")
                    }
                    if (openFirst) readUri(Uri.parse(report.imported.first().uri))
                    AlertDialog.Builder(this).setTitle(heading).setMessage(message)
                        .setPositiveButton("完成", null)
                        .setNeutralButton("查看最近打开") { _, _ -> showRecents() }.show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && token == generation) {
                        finishBatchUi(); recentUi.refresh(); showError("批量导入失败", error)
                    }
                }
            }
        }
    }

    private fun finishBatchUi() {
        batchDialog?.dismiss(); batchDialog = null; batchCancellation = null
        importing = false; loading = false; loadingIndicator.visibility = View.GONE
        setControlsReady(score != null && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
    }

    fun openDemo() {
        if (importing) { Toast.makeText(this, "请先完成或取消批量导入。", Toast.LENGTH_SHORT).show(); return }
        guardDocumentChange { loadFile({ "晨光练习曲.gp" to assets.open("morning.gp").use { it.readBytes() } }, null) }
    }

    private fun guardDocumentChange(action: () -> Unit) {
        if (!modified) { action(); return }
        AlertDialog.Builder(this).setTitle("修改尚未导出")
            .setMessage("导出 GP 副本可保留当前修改。继续切换会丢弃当前草稿；取消可留在当前乐谱。")
            .setPositiveButton("导出副本") { _, _ -> requestExport(ScoreExport.Format.GUITAR_PRO) }
            .setNeutralButton("丢弃修改") { _, _ -> modified = false; io.execute { drafts.delete() }; action() }
            .setNegativeButton("取消", null).show()
    }

    private fun loadFile(read: () -> Pair<String, ByteArray>, uri: Uri?, recovered: Boolean = false) {
        if (factorySetupDelegate.isInitialized()) factorySetup.cancel()
        sheet.api.pause()
        val token = ++generation
        loading = true; setControlsReady(false)
        status.text = "正在读取谱面、内嵌音频和同步点…"
        loadingIndicator.visibility = View.VISIBLE
        io.execute {
            try {
                val (label, bytes) = read()
                require(bytes.size <= MAX_FILE) { "文件超过 128 MB，请先减小内嵌音频大小。" }
                val parsed = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), sheet.settings)
                require(parsed.tracks.length > 0) { "这份文件中没有可显示的声部。" }
                try {
                    val properties = com.stringpage.app.rse.GpRseProperties.read(bytes)
                    require(!properties.hasGpif || properties.tracks.size == parsed.tracks.length.toInt())
                    com.stringpage.app.rse.GpRseProperties.bind(parsed, properties)
                } catch (error: Exception) {
                    com.stringpage.app.rse.GpRseProperties.bindFailure(parsed, error.message ?: "无法读取原谱的音源设置。")
                }
                val rawAudio = parsed.backingTrack?.rawAudioFile
                val audioFile = if (rawAudio != null && rawAudio.length > 0) {
                    File(cacheDir, "embedded-$token.audio").apply { writeBytes(rawAudio.buffer.asByteArray()) }
                } else null
                runOnUiThread {
                    if (token != generation || isDestroyed) return@runOnUiThread
                    loading = false; loadingIndicator.visibility = View.GONE
                    score = parsed; fileLabel = label; activeTrack = 0
                    modified = recovered; restoreTick = null; trainer.stop()
                    editor.reset(parsed); practice.reset(parsed)
                    if (!recovered) io.execute { drafts.delete() }
                    hasAudio = (parsed.backingTrack?.rawAudioFile?.length ?: 0.0) > 0.0
                    audioMode = hasAudio; factoryMode = false
                    embedded.release(); embeddedReady = false; midiReady = false
                    factoryResult?.file?.delete(); factoryResult = null
                    embeddedFile?.delete(); embeddedFile = audioFile
                    currentSpeed = 1.0; speed.text = "1.0×"
                    sheet.api.isLooping = false; sheet.api.playbackRange = null; loop.text = "循环"
                    duration = 0.0; progress.progress = 0; position.text = "00:00 / 00:00"
                    title.text = parsed.title.ifBlank { label.substringBeforeLast('.') }
                    subtitle.text = listOf(parsed.artist, "${parsed.masterBars.length.toInt()} 小节", "${parsed.tracks.length.toInt()} 个声部")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    updateDocumentSubtitle()
                    source.text = if (hasAudio) "内嵌原曲" else "合成音源"
                    track.text = parsed.tracks[0].name.ifBlank { "声部 1" }
                    val syncCount = parsed.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 }
                    status.text = when {
                        hasAudio && syncCount > 0 -> "已读取内嵌音频及 $syncCount 个同步点 · 点击谱面可跳转"
                        hasAudio -> "已读取内嵌音频；缺少同步点，跟谱位置可能有偏差。"
                        else -> "这份谱子没有可读取的内嵌音频，当前按谱合成播放。"
                    }
                    sheet.settings.player.playerMode = if (hasAudio) PlayerMode.EnabledExternalMedia else PlayerMode.EnabledSynthesizer
                    sheet.api.updateSettings()
                    bindEmbeddedOutput()
                    if (audioFile != null) prepareEmbedded(audioFile)
                    empty.visibility = View.GONE
                    sheet.tracks = listOf(parsed.tracks[0])
                    if (uri != null) { saveRecent(uri, title.text.toString()); prefs.edit().putString("lastUri", uri.toString()).apply() }
                    else prefs.edit().remove("lastUri").apply()
                    inlineFrom.setText("1"); inlineTo.setText(parsed.masterBars.length.toInt().toString())
                    barCounter.text = "小节 1 / ${parsed.masterBars.length.toInt()}"
                    refreshPanels()
                }
            } catch (e: Throwable) {
                if (e !is Exception && e !is alphaTab.AlphaTabError) throw e
                runOnUiThread {
                    if (token != generation || isDestroyed) return@runOnUiThread
                    loading = false; loadingIndicator.visibility = View.GONE
                    setControlsReady(score != null && sheet.api.isReadyForPlayback)
                    status.text = "导入失败。请选择完整的 GP 文件。"
                    showError("无法打开乐谱", e)
                }
            }
        }
    }

    private fun bindEmbeddedOutput() {
        if (audioMode) {
            embedded.output = sheet.api.player?.output as? IExternalMediaSynthOutput
            if (factoryMode) applyFactorySync()
        }
    }

    private fun applyFactorySync() {
        val point = alphaTab.synth.BackingTrackSyncPoint().apply {
            synthTick = 0.0; synthTime = 0.0; syncTime = factoryResult?.syncOffsetMs ?: 0.0
            synthBpm = score?.tempo ?: 120.0; syncBpm = synthBpm
        }
        sheet.api.player?.updateSyncPoints(alphaTab.collections.List(point))
    }

    private fun restorePlaybackPosition() {
        if (!midiReady || (audioMode && !embeddedReady)) return
        restoreTick?.let { tick -> restoreTick = null; sheet.api.tickPosition = tick.coerceAtMost(sheet.api.endTick) }
    }

    private val factorySetupDelegate = lazy { com.stringpage.app.rse.FactoryPlaybackSetup(this,
        { score === it }, { preparing ->
            loading = preparing
            loadingIndicator.visibility = if (preparing) View.VISIBLE else View.GONE
            setControlsReady(!preparing && score != null && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
        }, { bankLibrary.show() }, this::acceptFactoryAudio) }
    private val factorySetup by factorySetupDelegate

    private fun chooseFactorySource() {
        val loaded = score ?: return
        sheet.api.pause(); embedded.pause()
        factorySetup.show(loaded, sheet.settings, practice.snapshot().transposition)
    }

    internal fun acceptFactoryAudio(expected: Score, result: com.stringpage.app.rse.FactoryScoreRenderer.Result) {
        if (score !== expected || isDestroyed) { result.file.delete(); return }
        val externalWasReady = sheet.api.player?.output is IExternalMediaSynthOutput && sheet.api.isReadyForPlayback
        sheet.api.pause(); embedded.release(); embeddedReady = false
        factoryResult?.file?.delete(); factoryResult = result
        factoryMode = true; audioMode = true; midiReady = externalWasReady; trainer.stop(); restoreTick = currentTick
        setControlsReady(false)
        sheet.settings.player.playerMode = PlayerMode.EnabledExternalMedia
        sheet.api.updateSettings(); prepareEmbedded(result.file); sheet.api.render()
        source.text = "原厂采样"
        status.text = "已准备 ${result.noteCount} 个演奏音符" +
            (if (result.substitutedArticulations > 0) " · ${result.substitutedArticulations} 个音符使用替代技法采样" else "") +
            (if (result.clippedSamples > 0) " · 输出电平达到上限，可降低声部音量后重新准备" else "")
        refreshPanels()
    }

    private val bankLibraryDelegate = lazy { com.stringpage.app.rse.BankLibraryUi(this) { sheet.api.pause(); embedded.pause() } }
    private val bankLibrary by bankLibraryDelegate

    private fun showMore() {
        val menu = PopupMenu(this, moreButton)
        val available = score != null && !loading
        fun item(id: Int, label: String, enabled: Boolean = available) { menu.menu.add(0, id, id, label).isEnabled = enabled }
        item(1, "混音与练习设置")
        item(2, "基础编辑")
        item(3, "撤销", available && editor.canUndo)
        item(4, "重做", available && editor.canRedo)
        item(5, "导出 GP 兼容副本")
        item(6, "导出 MIDI")
        item(7, "关于", true)
        item(8, "逐轮加速练习")
        item(9, if (fullSheet) "退出全屏" else "全屏看谱", true)
        item(10, "原厂音色库 · 采样预览", true)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> practice.show()
                2 -> { sheet.api.pause(); editor.show() }
                3 -> { sheet.api.pause(); editor.undo() }
                4 -> { sheet.api.pause(); editor.redo() }
                5 -> requestExport(ScoreExport.Format.GUITAR_PRO)
                6 -> requestExport(ScoreExport.Format.MIDI)
                7 -> showAbout()
                8 -> showTrainer()
                9 -> { fullSheet = !fullSheet; applyAdaptiveLayout(currentWidth) }
                10 -> bankLibrary.show()
            }
            true
        }
        menu.show()
    }

    private fun applyEditedScore(next: Score, description: String) {
        score?.let { com.stringpage.app.rse.GpRseProperties.transfer(it, next) }
        if (factoryMode) switchSource(false)
        sheet.api.pause()
        restoreTick = currentTick
        score = next; modified = true; midiReady = false
        practice.reset(next, true)
        title.text = next.title.ifBlank { fileLabel.substringBeforeLast('.') }
        updateDocumentSubtitle()
        status.text = description
        setControlsReady(false)
        activeTrack = activeTrack.coerceIn(0, next.tracks.length.toInt() - 1)
        sheet.tracks = listOf(next.tracks[activeTrack])
        track.text = next.tracks[activeTrack].name
        refreshPanels()
        saveDraft()
    }

    private fun updateDocumentSubtitle() {
        val current = score ?: return
        subtitle.text = listOf(current.artist, "${current.masterBars.length.toInt()} 小节",
            "${current.tracks.length.toInt()} 个声部", if (modified) "未导出修改" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun saveDraft() {
        val current = score ?: return
        io.execute {
            try { drafts.write(ScoreExport.guitarProBytes(current)) }
            catch (error: Exception) { runOnUiThread { status.text = "草稿写入失败，请导出副本保存修改。" } }
        }
    }

    private fun requestExport(format: ScoreExport.Format, acknowledged: Boolean = false) {
        val current = score ?: return
        if (loading) return
        if (!acknowledged) {
            AlertDialog.Builder(this).setTitle(if (format == ScoreExport.Format.GUITAR_PRO) "导出 GP 兼容副本" else "导出 MIDI")
                .setMessage(ScoreExport.limitations(format).joinToString("\n\n"))
                .setPositiveButton("选择保存位置") { _, _ -> requestExport(format, true) }
                .setNegativeButton("取消", null).show()
            return
        }
        sheet.api.pause()
        loading = true; loadingIndicator.visibility = View.VISIBLE; setControlsReady(false)
        status.text = "正在准备导出…"
        io.execute {
            try {
                val bytes = ScoreExport.encode(current, format)
                val pending = File(cacheDir, "export-${System.nanoTime()}.${format.extension}").apply { writeBytes(bytes) }
                runOnUiThread {
                    if (isDestroyed) { pending.delete(); return@runOnUiThread }
                    exportFile?.delete(); exportFile = pending; exportFormat = format; exportScore = current
                    loading = false; loadingIndicator.visibility = View.GONE; setControlsReady(sheet.api.isReadyForPlayback)
                    val name = current.title.ifBlank { fileLabel.substringBeforeLast('.') }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        // Android's audio/midi mapping can append .rtx to a .mid filename.
                        // A neutral document type preserves the explicitly chosen extension.
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "$name.${format.extension}")
                    }, 11)
                }
            } catch (error: Throwable) {
                if (error !is Exception && error !is alphaTab.AlphaTabError) throw error
                runOnUiThread {
                    loading = false; loadingIndicator.visibility = View.GONE; setControlsReady(sheet.api.isReadyForPlayback)
                    showError("导出失败", error)
                }
            }
        }
    }

    private fun prepareEmbedded(file: File) {
        embedded.load(file) {
            embeddedReady = true
            embedded.firstCueMs = if(factoryMode) factoryResult?.firstCueMs ?: 0.0 else 0.0
            bindEmbeddedOutput()
            embedded.masterVolume = sheet.api.masterVolume
            sheet.api.playbackSpeed = currentSpeed
            if (factoryMode) applyFactorySync() else sheet.api.updateSyncPoints()
            restorePlaybackPosition()
            if (midiReady && !loading) setControlsReady(true)
        }
    }

    private fun togglePlayback() {
        if (!sheet.api.isReadyForPlayback || (audioMode && !embeddedReady)) return
        if (playing) sheet.api.pause()
        else if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) sheet.api.play()
        else Toast.makeText(this, "当前无法获得音频播放权限，请稍后重试。", Toast.LENGTH_SHORT).show()
    }

    private fun chooseSource() {
        if (score == null) return
        val options = if (hasAudio) arrayOf("内嵌原曲 / 伴奏", "按谱合成 · GeneralUser GS", "原厂采样 · 按谱播放")
            else arrayOf("按谱合成 · GeneralUser GS", "原厂采样 · 按谱播放")
        val selected = if (factoryMode) options.lastIndex else if (hasAudio && !audioMode) 1 else 0
        AlertDialog.Builder(this).setTitle("选择播放音源")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                dialog.dismiss()
                if (which == options.lastIndex) chooseFactorySource() else switchSource(hasAudio && which == 0)
            }.setNeutralButton("音色库") { _, _ -> bankLibrary.show() }.setNegativeButton("关闭", null).show()
    }

    internal fun switchSource(useEmbedded: Boolean) {
        if ((useEmbedded == audioMode && !factoryMode) || score == null) return
        if (factorySetupDelegate.isInitialized()) factorySetup.cancel()
        val externalWasReady = sheet.api.player?.output is IExternalMediaSynthOutput && sheet.api.isReadyForPlayback
        sheet.api.pause(); embedded.pause(); audioMode = useEmbedded && hasAudio
        factoryMode = false
        trainer.stop()
        setControlsReady(false); midiReady = audioMode && externalWasReady
        sheet.settings.player.playerMode = if (audioMode) PlayerMode.EnabledExternalMedia else PlayerMode.EnabledSynthesizer
        sheet.api.updateSettings()
        if (audioMode) embeddedFile?.let { prepareEmbedded(it) }
        else { embedded.release(); embeddedReady = false }
        factoryResult?.file?.delete(); factoryResult = null
        sheet.api.render()
        source.text = if (audioMode) "内嵌原曲" else "合成音源"
        status.text = if (audioMode) "播放内嵌录音" else "按谱合成演奏"
        refreshPanels()
    }

    private fun chooseTrack() {
        val loaded = score ?: return
        val names = loaded.tracks.toList().map { it.name.ifBlank { "声部 ${(it.index + 1).toInt()}" } }.toTypedArray()
        AlertDialog.Builder(this).setTitle("显示哪个声部？")
            .setSingleChoiceItems(names, activeTrack) { dialog, index ->
                selectTrack(index)
                dialog.dismiss()
            }.setNegativeButton("关闭", null).show()
    }

    private fun selectTrack(index: Int) {
        val loaded = score ?: return
        activeTrack = index; sheet.tracks = listOf(loaded.tracks[index])
        track.text = loaded.tracks[index].name.ifBlank { "声部 ${index + 1}" }
        refreshPanels()
    }

    internal fun changeSpeed(value: Double, stopTrainer: Boolean = true) {
        if (!sheet.api.isReadyForPlayback) return
        if (stopTrainer) trainer.stop()
        val wasPlaying = playing
        currentSpeed = value; sheet.api.playbackSpeed = value
        if (!wasPlaying) { sheet.api.pause(); embedded.pause() }
        speed.text = "${currentSpeed}×"
        refreshPanels()
    }

    private fun chooseSpeed() {
        val values = doubleArrayOf(0.5, 0.6, 0.75, 0.85, 1.0, 1.25, 1.5)
        AlertDialog.Builder(this).setTitle("练习速度 · 保持音高")
            .setSingleChoiceItems(values.map { "${(it * 100).toInt()}%" }.toTypedArray(), values.indexOfFirst { it == currentSpeed }) { dialog, index ->
                changeSpeed(values[index]); dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showTrainer() {
        if (audioMode) {
            AlertDialog.Builder(this).setTitle("逐轮加速练习")
                .setMessage("逐轮加速使用合成音源。请切换音源并选择小节循环，再设置起始速度、目标速度和重复次数。")
                .setPositiveButton("切换到合成音源") { _, _ -> switchSource(false) }
                .setNegativeButton("关闭", null).show()
            return
        }
        if (sheet.api.playbackRange == null || !sheet.api.isLooping) {
            Toast.makeText(this, "请先选择小节循环。", Toast.LENGTH_LONG).show(); return
        }
        val previous = trainer.configuration ?: ProgressivePractice.Configuration(60, 100, 5, 2)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val values = listOf("起始速度（%）" to previous.start, "目标速度（%）" to previous.target,
            "每次增加（百分点）" to previous.step, "每个速度重复次数" to previous.repeats)
        val fields = values.map { (label, value) ->
            content.addView(text(label, 13f))
            numberField(label).apply { setText(value.toString()); content.addView(this, LinearLayout.LayoutParams(-1, dp(48))) }
        }
        content.addView(text("完成指定轮数后升速，到达目标后保持。手动变速、切换音源或关闭循环会停止加速。", 12f, muted))
        val dialog = AlertDialog.Builder(this).setTitle("逐轮加速练习").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("应用", null).setNeutralButton("停止加速") { _, _ -> trainer.stop(); status.text = "逐轮加速已停止" }
            .setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val input = fields.map { it.text.toString().toIntOrNull() ?: throw IllegalArgumentException("请输入整数。") }
                    val settings = ProgressivePractice.Configuration(input[0], input[1], input[2], input[3])
                    if (!startTrainer(settings)) throw IllegalArgumentException("请等待合成音源准备完成，并选择小节循环。")
                    status.text = "逐轮加速 ${settings.start}% → ${settings.target}% · 每个速度 ${settings.repeats} 轮"
                    dialog.dismiss()
                } catch (error: IllegalArgumentException) { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }

    internal fun startTrainer(settings: ProgressivePractice.Configuration): Boolean {
        if (audioMode || !sheet.api.isReadyForPlayback || !sheet.api.isLooping) return false
        val range = sheet.api.playbackRange ?: return false
        sheet.api.pause()
        changeSpeed(trainer.start(settings), false)
        sheet.api.tickPosition = range.startTick
        return true
    }

    private fun chooseZoom() {
        val values = intArrayOf(65, 75, 85, 100, 115, 130)
        AlertDialog.Builder(this).setTitle("谱面大小")
            .setItems((values.map { "$it%" } + "切换五线谱 / 六线谱").toTypedArray()) { _, index ->
                if (index == values.size) { chooseNotation(); return@setItems }
                sheet.settings.display.scale = values[index] / 100.0
                sheet.api.updateSettings(); sheet.api.render()
                zoom.text = "${values[index]}%"
            }.show()
    }

    private fun chooseLoop() {
        val loaded = score ?: return
        val form = row().apply { setPadding(dp(20), dp(8), dp(20), dp(8)); gravity = Gravity.CENTER }
        val from = NumberPicker(this).apply { minValue = 1; maxValue = loaded.masterBars.length.toInt(); value = 1 }
        val to = NumberPicker(this).apply { minValue = 1; maxValue = loaded.masterBars.length.toInt(); value = loaded.masterBars.length.toInt() }
        form.addView(from); form.addView(text("  到  ")); form.addView(to)
        val dialog = AlertDialog.Builder(this).setTitle("循环练习 · 选择小节")
            .setView(form).setPositiveButton("开始循环", null)
            .setNeutralButton("取消循环") { _, _ -> clearLoopRange() }.setNegativeButton("关闭", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (applyLoopRange(from.value, to.value)) dialog.dismiss()
            }
        }
        dialog.show()
    }

    internal fun applyLoopRange(from: Int, to: Int): Boolean {
        val loaded = score ?: return false
        if (from < 1 || from > to || to > loaded.masterBars.length.toInt()) {
            Toast.makeText(this, "请输入有效的小节范围（1–${loaded.masterBars.length.toInt()}）。", Toast.LENGTH_SHORT).show()
            return false
        }
        val cache = sheet.api.tickCache ?: return false
        val first = loaded.masterBars[from - 1]; val last = loaded.masterBars[to - 1]
        val start = cache.getMasterBarStart(first)
        val end = cache.getMasterBarStart(last) + last.calculateDuration()
        if (end <= start) return false
        sheet.api.playbackRange = PlaybackRange().apply { startTick = start; endTick = end }
        sheet.api.isLooping = true; sheet.api.tickPosition = start
        inlineFrom.setText(from.toString()); inlineTo.setText(to.toString())
        loop.text = "循环中"; status.text = "循环第 $from–$to 小节"
        return true
    }

    private fun clearLoopRange() {
        trainer.stop()
        sheet.api.isLooping = false; sheet.api.playbackRange = null; loop.text = "循环"
        status.text = "循环已关闭"
    }

    private fun saveRecent(uri: Uri, name: String) {
        library.addBatch(listOf(RecentLibrary.Entry(uri.toString(), name, fileLabel)))
    }

    private fun showRecents() {
        if (!importing) recentUi.show()
    }

    private fun showError(heading: String, error: Throwable) {
        android.util.Log.e("StringPage", heading, error)
        AlertDialog.Builder(this).setTitle(heading)
            .setMessage(error.message?.take(180) ?: "文件格式或音频编码暂不支持。")
            .setPositiveButton("知道了", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exportFile?.let { outState.putString("exportPath", it.absolutePath) }
        exportFormat?.let { outState.putString("exportFormat", it.name) }
        prefs.getString("lastUri", null)?.let { outState.putString("uri", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        currentWidth = newConfig.screenWidthDp
        applyAdaptiveLayout(currentWidth)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.repeatCount == 0) togglePlayback()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME && score != null) { sheet.api.stop(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        if (bankLibraryDelegate.isInitialized()) bankLibrary.stop()
        sheet.api.pause(); embedded.pause(); practice.close(); audioManager.abandonAudioFocusRequest(focus); super.onPause()
    }
    override fun onDestroy() {
        if (factorySetupDelegate.isInitialized()) factorySetup.close()
        if (bankLibraryDelegate.isInitialized()) bankLibrary.close()
        batchCancellation?.set(true); batchDialog?.dismiss(); recentUi.close()
        generation++; io.shutdownNow(); unregisterReceiver(unplugged)
        embedded.release(); embeddedFile?.delete()
        factoryResult?.file?.delete()
        practice.close()
        super.onDestroy()
        // AlphaTabView releases audio workers and rendering resources on detach.
    }

    companion object {
        private var factoryCacheSwept = false
        const val MAX_FILE = 128 * 1024 * 1024
        internal fun selectedImportUris(data: Intent): List<Uri> = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) } }
            data.data?.let { add(it) }
        }.distinctBy { it.toString() }
    }
}
