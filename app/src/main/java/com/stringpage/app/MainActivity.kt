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
import android.graphics.PointF
import android.graphics.Rect
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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.text.TextUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.stringpage.app.rse.FactoryScoreRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Offline GP reader. Embedded audio and the GP8 sync map stay inside alphaTab's data model. */
open class MainActivity : AppCompatActivity() {
    private val pdfPreview get() = this is com.stringpage.app.pdf.PdfPreviewActivity
    private val ink = AppUi.ink
    private val paper = AppUi.canvas
    private val accent = AppUi.accent
    private val muted = AppUi.muted
    private val io = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var loading = false
    private var score: Score? = null
    private var audioMode = true
    private var factoryMode = false
    private var factoryNoteCount = 0
    private var liveAmp: StemAudio? = null
    private val preparedMode get() = liveAmp != null
    private var activeTrack = 0
    private var playing = false
    private var duration = 0.0
    private var currentTick = 0.0
    private var currentSpeed = 1.0
    private var hasAudio = false
    private var fileLabel = ""
    private var embeddedFile: File? = null
    private var recordingDialog: AlertDialog? = null
    private var embeddedReady = false
    private var midiReady = false
    private var preparedScoreTime: PreparedScoreTime? = null
    private var restoreAmpSource = false
    private val embedded by lazy { EmbeddedAudio { error ->
        pausePlayback(); embeddedReady = false; setControlsReady(false)
        showError("内嵌音频播放失败", error)
    }.apply {
        onSeekSettled = {
            if (audioMode && !preparedMode && midiReady && !loading && restoreRequestedTick != null && sheet.api.score === score) {
                completePlaybackRestore()
            }
        }
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
    private lateinit var practiceShortcut: Button
    private lateinit var scoreHost: FrameLayout
    private lateinit var scoreEntry: ScoreEntryPanel
    private val midiInputDelegate = lazy {
        MidiInputController(this,
            canCapture = { scoreEntry.isOpen && hasWindowFocus() && !loading && !playing },
            write = { chord -> scoreEntry.offerMidi(chord) },
            changed = { name, error ->
                scoreEntry.setMidiPort(name)
                if (name != null) {
                    setScoreEditing(true)
                    Toast.makeText(this, "MIDI 已连接。点选谱面后即可录入。", Toast.LENGTH_SHORT).show()
                }
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        )
    }
    private val midiInput by midiInputDelegate
    private var scoreTap: PointF? = null
    private lateinit var scoreFrame: FrameLayout
    private lateinit var headerToolbar: LinearLayout
    private lateinit var brand: TextView
    private lateinit var transport: LinearLayout
    private lateinit var speedReadout: Button
    private lateinit var bottomContent: LinearLayout
    private lateinit var timeline: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var voicesPanel: LinearLayout
    private lateinit var voiceList: LinearLayout
    private lateinit var audioDetails: TextView
    private lateinit var sourceNotice: TextView
    private lateinit var barCounter: TextView
    private var displayedBar = 0
    private lateinit var masterVolume: SeekBar
    private lateinit var fullScreen: Button
    private lateinit var notation: Button
    private lateinit var inlineFrom: EditText
    private lateinit var inlineTo: EditText
    private lateinit var applyLoop: Button
    private lateinit var clearLoop: Button
    private val speedPresets = mutableListOf<Button>()
    private var fullSheet = false
    private var currentWidth = 0
    private var currentHeight = 0
    private var modified = false
    private var restoreTick: Double? = null
    private data class BarPosition(val bar: Int, val occurrence: Int, val offset: Double)
    private var restoreBarPosition: BarPosition? = null
    private var documentKey: String? = null
    private var lyricTracks = emptySet<Int>()
    private var pendingReaderSession: ReaderSession? = null
    private var restoreRequestedTick: Double? = null
    private var resumeAfterToneReload: Boolean? = null
    private var loopFromBar: Int? = null
    private var loopToBar: Int? = null
    private var exportFile: File? = null
    private enum class ExportKind(
        val label: String, val extension: String, val mime: String, val model: ScoreExport.Format? = null
    ) {
        GUITAR_PRO("GP 兼容副本", "gp", "application/octet-stream", ScoreExport.Format.GUITAR_PRO),
        MIDI("MIDI", "mid", "application/octet-stream", ScoreExport.Format.MIDI),
        WAV("WAV", "wav", "audio/wav"),
        PDF("PDF", "pdf", "application/pdf")
    }
    private var exportFormat: ExportKind? = null
    private var exportScore: Score? = null
    private var preparingExport: AtomicBoolean? = null
    private val drafts by lazy { DraftStore(this) }
    private val trainer = ProgressivePractice()
    private var mixChanged = false
    private lateinit var moreButton: Button
    private lateinit var editScoreButton: Button
    private lateinit var recentHeader: Button
    internal val practice by lazy { PlaybackControls(this, { sheet.api }, { !audioMode }, { switchSource(false) },
        { editor.beginExternalChange() }, { modified = true; mixChanged = true; updateDocumentSubtitle() },
        { if (mixChanged) { editor.commitExternalChange("修改声部混音"); mixChanged = false; saveDraft() } },
        { if (liveAmp != null) "可在播放中调整各声部的吉他效果器。"
          else "内嵌录音是整首混音。切换到合成音源后可调整吉他音色、混音、节拍器、预备拍和播放移调。" },
        { documentKey }, this::reloadTonePlayback, { liveAmp?.guitarTracks.orEmpty() }, this::chooseAmpSettings,
        { editor.trackSlot(it) }) }
    private val editor by lazy { BasicScoreEditor(this, { score }, this::applyEditedScore, { sheet.settings }) }
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val library by lazy { RecentLibrary(prefs) }
    private val recentUi by lazy { RecentScoresUi(this, library, this::openUri, this::pickFile) }
    private val readerMenu by lazy { ReaderMenu(this) }
    private val documentReader by lazy { GpDocumentReader(this) }
    private var importing = false
    private var batchCancellation: AtomicBoolean? = null
    private var batchDialog: AlertDialog? = null
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { if (it < 0) runOnUiThread { pausePlayback() } }
            .build()
    }
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (bankLibraryDelegate.isInitialized()) bankLibrary.stop()
            pausePlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingReaderSession=ReaderSession.fromBundle(savedInstanceState?.getBundle("readerSession"))
        fullSheet=pendingReaderSession?.fullScreen ?: false
        if (!factoryCacheSwept) {
            factoryCacheSwept = true
            cacheDir.listFiles()?.filter { it.isFile && ((it.name.startsWith("factory-score-") && it.extension == "wav") ||
                (it.name.startsWith("amp-dry-") && it.extension == "pcm")) }
                ?.forEach { it.delete() }
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildUi()
        wirePlayer()
        savedInstanceState?.getString("exportPath")?.let { path ->
            val pending = File(path)
            if (pending.parentFile?.canonicalPath == cacheDir.canonicalPath && pending.isFile) exportFile = pending
        }
        savedInstanceState?.getString("exportFormat")?.let { runCatching { ExportKind.valueOf(it) }.getOrNull()?.let { exportFormat = it } }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unplugged, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(unplugged, filter)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (scoreEntry.isOpen) setScoreEditing(false) else guardDocumentChange { finish() }
            }
        })
        if (pdfPreview) {
            val file = com.stringpage.app.pdf.PdfConvertActivity.privatePreviewFile(cacheDir,
                intent.getStringExtra(com.stringpage.app.pdf.PdfConvertActivity.PREVIEW_EXTRA))
            if (file == null) {
                Toast.makeText(this, "试听文件已失效，请重新生成。", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                sheet.settings.display.staveProfile = alphaTab.StaveProfile.Tab
                loadFile({ "PDF 转换试听.gp" to file.readBytes() }, null)
            }
            return
        }
        val initial = {
            if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
                if(pendingReaderSession?.document!=intent.data.toString()) pendingReaderSession=null
                openIncoming(intent)
            }
            else if(pendingReaderSession!=null) openUri(Uri.parse(pendingReaderSession!!.document))
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
    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, dark: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 13f; isAllCaps = false; typeface = AppUi.medium
        setTextColor(if (dark) Color.WHITE else ink)
        background = AppUi.ripple(this@MainActivity, if (dark) accent else AppUi.control, 10)
        elevation = 0f; stateListAnimator = null
        minWidth = 0; minimumWidth = 0; minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun divider(color: Int = AppUi.line) = View(this).apply { setBackgroundColor(color) }
    private fun LinearLayout.addWeighted(v: View, weight: Float = 1f, height: Int = 48) {
        addView(v, LinearLayout.LayoutParams(0, dp(height), weight).apply { marginEnd = dp(6) })
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(AppUi.panel)
        }
        val statusInset = View(this).apply { setBackgroundColor(AppUi.rail) }
        root.addView(statusInset, LinearLayout.LayoutParams(-1, 0))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            if (statusInset.layoutParams.height != bars.top) {
                statusInset.layoutParams.height = bars.top
                statusInset.requestLayout()
            }
            insets
        }
        headerToolbar = row().apply { setBackgroundColor(AppUi.rail); setPadding(dp(22), dp(4), dp(18), dp(4)) }
        val logo = ImageView(this).apply {
            setImageDrawable(AppUi.icon(this@MainActivity, "strings", AppUi.railAccent, 22))
            background = AppUi.surface(this@MainActivity, AppUi.railSurface, 10, AppUi.railLine)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        headerToolbar.addView(logo, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        brand = text("弦页", 21f, AppUi.railInk).apply {
            id=R.id.app_brand;maxLines=1;ellipsize=TextUtils.TruncateAt.END
            typeface = AppUi.display; setOnClickListener { showAbout() }
        }
        headerToolbar.addWeighted(brand, 1f, 48)
        fun headerAction(label: String, icon: String, action: () -> Unit) = button(label, action = action).apply {
            background = AppUi.ripple(this@MainActivity, Color.TRANSPARENT, 10)
            setTextColor(AppUi.railInk)
            AppUi.iconButton(this, icon, AppUi.railMuted)
        }
        recentHeader = headerAction("最近", "clock") { showRecents() }
        headerToolbar.addView(recentHeader)
        editScoreButton = headerAction("编辑", "edit") { setScoreEditing(!scoreEntry.isOpen) }.apply {
            visibility = View.GONE
        }
        headerToolbar.addView(editScoreButton)
        fullScreen = headerAction("全屏看谱", "full") { fullSheet = !fullSheet; applyAdaptiveLayout(currentWidth) }.apply { id = R.id.full_screen }
        headerToolbar.addView(fullScreen)
        headerToolbar.addView(button("导入", true) { pickFile() }.apply {
            id = R.id.import_file; AppUi.iconButton(this, "import", AppUi.rail)
            setTextColor(AppUi.rail)
            background = AppUi.ripple(this@MainActivity, AppUi.railAccent, 11)
            layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8); marginEnd = dp(6) }
        })
        moreButton = headerAction("更多", "more") { showMore() }.apply { id = R.id.more_actions }
        headerToolbar.addView(moreButton)
        root.addView(headerToolbar, LinearLayout.LayoutParams(-1, dp(60)))
        val body = row().apply { gravity = Gravity.TOP; setBackgroundColor(paper) }
        val workspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        sidebar = ScrollView(this).apply {
            id = R.id.library_panel; setBackgroundColor(AppUi.rail); isFillViewport = true; isVerticalScrollBarEnabled = false
        }
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(14), dp(17), dp(20)) }
        library.addView(divider(AppUi.railLine), LinearLayout.LayoutParams(-1, dp(1)))
        library.addView(text("曲库", 18f, AppUi.railInk).apply { typeface = AppUi.medium }, LinearLayout.LayoutParams(-1, dp(50)))
        library.addView(recentUi.section)
        voicesPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voicesPanel.addView(divider(AppUi.railLine), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        voicesPanel.addView(sectionLabel("声部", AppUi.railMuted))
        voiceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voicesPanel.addView(voiceList)
        sourceNotice = text("", 12f, AppUi.railMuted).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = AppUi.surface(this@MainActivity, AppUi.railSurface, 10, AppUi.railLine)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) }
        }
        voicesPanel.addView(sourceNotice)
        library.addView(voicesPanel)
        sidebar.addView(library)
        body.addView(sidebar, LinearLayout.LayoutParams(dp(214), -1))
        body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))

        infoPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(paper); setPadding(dp(24), dp(16), dp(24), dp(10))
        }
        documentHeading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = text("未打开乐谱", 22f).apply {
            id = R.id.score_title; typeface = AppUi.display; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = text("GP3 / GP4 / GP5 / GPX / GP", 12f, muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        }
        documentHeading.addView(title); documentHeading.addView(subtitle)
        infoPanel.addView(documentHeading)
        scoreTools = row()
        source = button("音源") { chooseSource() }.apply { id = R.id.audio_source; AppUi.iconButton(this, "audio", accent) }
        track = button("声部") { chooseTrack() }.apply { id = R.id.track_select; AppUi.iconButton(this, "track", muted) }
        zoom = button("85%") { chooseZoom() }.apply { AppUi.iconButton(this, "zoom", muted) }
        scoreTools.addWeighted(source, 1.1f, 44); scoreTools.addWeighted(track, 1.5f, 44); scoreTools.addWeighted(zoom, 0.85f, 44)
        practiceShortcut = button("") { this.practice.show() }.apply {
            contentDescription = "混音与练习设置"; tooltipText = "混音与练习设置"
            setPadding(dp(13), 0, dp(13), 0); AppUi.iconButton(this, "mix", accent); compoundDrawablePadding = 0
        }
        scoreTools.addView(practiceShortcut, LinearLayout.LayoutParams(dp(44), dp(44)))
        infoPanel.addView(scoreTools); workspace.addView(infoPanel)

        scoreHost = FrameLayout(this)
        scoreFrame = FrameLayout(this).apply {
            background = AppUi.surface(this@MainActivity, Color.WHITE, 10, AppUi.line); clipToOutline = true
        }
        sheet = AlphaTabView(this, null).apply {
            id = R.id.score_view
            barCursorFillColor = Color.argb(26, 161, 60, 43)
            beatCursorFillColor = Color.argb(214, 161, 60, 43)
            selectionFillColor = Color.argb(52, 161, 60, 43)
            settings.display.scale = 0.85
            settings.display.barsPerRow = -1.0
            settings.display.padding = alphaTab.collections.DoubleList().apply { push(20.0); push(24.0); push(20.0); push(24.0) }
            settings.player.playerMode = PlayerMode.EnabledSynthesizer
            settings.player.enableUserInteraction = true
            settings.core.includeNoteBounds = true
            api.updateSettings()
        }
        scoreFrame.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppUi.panel)
        }
        val welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        welcome.addView(ImageView(this).apply {
            setImageDrawable(AppUi.icon(this@MainActivity, "score", accent, 48))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(23), dp(15), dp(23), dp(15))
            background = AppUi.surface(this@MainActivity, AppUi.selected, 24)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(98), dp(84)).apply { bottomMargin = dp(22) })
        welcome.addView(text("打开乐谱", 34f).apply { gravity = Gravity.CENTER; typeface = AppUi.display })
        welcome.addView(text("GP3、GP4、GP5、GPX、GP", 13f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(23))
        })
        welcome.addView(button("导入 GP 文件", true) { pickFile() },
            LinearLayout.LayoutParams(dp(284), dp(46)))
        if (!pdfPreview) welcome.addView(button("新建乐谱") { showNewScore() },
            LinearLayout.LayoutParams(dp(284), dp(46)).apply { topMargin = dp(8) })
        empty.addView(ScrollView(this).apply { isFillViewport = true; addView(welcome) }, LinearLayout.LayoutParams(-1, -1))
        scoreFrame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        loadingIndicator = ProgressBar(this).apply { visibility = View.GONE }
        scoreFrame.addView(loadingIndicator, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        scoreHost.addView(scoreFrame, FrameLayout.LayoutParams(-1, -1))
        workspace.addView(scoreHost, LinearLayout.LayoutParams(-1, 0, 1f))
        scoreEntry = ScoreEntryPanel(this, editor, { score }, { activeTrack }, { displayedBar - 1 },
            chooseMidi = ::chooseMidiInput,
            clearMidiGesture = { if (midiInputDelegate.isInitialized()) midiInput.clearGesture() },
            exit = { setScoreEditing(false) })
        workspace.addView(scoreEntry.view, LinearLayout.LayoutParams(-1, -2))

        inspector = ScrollView(this).apply { id = R.id.practice_panel; setBackgroundColor(AppUi.panel); isFillViewport = true; isVerticalScrollBarEnabled = false }
        val practice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(18)) }
        val inspectorHeading = row()
        inspectorHeading.addWeighted(text("练习", 18f).apply { typeface = AppUi.medium }, 1f, 48)
        barCounter = text("小节 —", 11f, muted).apply {
            id = R.id.reader_bar_number
            typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
            background = AppUi.surface(this@MainActivity, AppUi.control, 7)
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }
        inspectorHeading.addView(barCounter); practice.addView(inspectorHeading)
        fun practiceSection(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(11), 0, dp(11))
            practice.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
            practice.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val tempoCard = practiceSection()
        val tempoHeading = row()
        tempoHeading.addWeighted(text("播放速度", 13f).apply { typeface = AppUi.medium }, 1f, 42)
        speedReadout = button("100%") { chooseSpeed() }.apply {
            textSize = 21f; typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
            setTextColor(accent)
            background = AppUi.ripple(this@MainActivity, AppUi.selected, 9)
        }
        tempoHeading.addView(speedReadout, LinearLayout.LayoutParams(-2, dp(44)))
        tempoCard.addView(tempoHeading)
        val presets = row()
        for ((index, value) in listOf(0.25, 0.5, 0.75, 1.0).withIndex()) {
            val preset = button("${(value * 100).toInt()}%") { changeSpeed(value) }.apply {
                tag = value; setPadding(dp(2), 0, dp(2), 0); textSize = 12f
            }
            speedPresets.add(preset)
            presets.addView(preset, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(if (index == 0) 0 else 4) })
        }
        tempoCard.addView(presets, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        tempoCard.addView(button("逐轮加速练习") { showTrainer() }.apply {
            background = AppUi.ripple(this@MainActivity, Color.TRANSPARENT); gravity = Gravity.CENTER_VERTICAL
            AppUi.iconButton(this, "tempo", accent, 16); setPadding(dp(4), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        val loopCard = practiceSection()
        loopCard.addView(text("小节循环", 13f).apply { typeface = AppUi.medium }, LinearLayout.LayoutParams(-1, dp(30)))
        val loopInputs = row()
        inlineFrom = numberField("起始小节"); inlineTo = numberField("结束小节")
        loopInputs.addWeighted(inlineFrom); loopInputs.addView(text(" – ", 14f, muted)); loopInputs.addWeighted(inlineTo)
        loopCard.addView(loopInputs)
        val loopActions = row().apply { setPadding(0, dp(8), 0, 0) }
        applyLoop = button("应用循环", true) {
            if (applyLoopRange(inlineFrom.text.toString().toIntOrNull() ?: 0, inlineTo.text.toString().toIntOrNull() ?: 0)) {
                inlineFrom.clearFocus(); inlineTo.clearFocus()
                WindowCompat.getInsetsController(window, inlineTo).hide(WindowInsetsCompat.Type.ime())
            }
        }
        clearLoop = button("清除") { clearLoopRange() }
        loopActions.addWeighted(applyLoop, 1.6f, 44); loopActions.addWeighted(clearLoop, 1f, 44)
        loopCard.addView(loopActions)
        val displayCard = practiceSection()
        displayCard.addView(text("谱面显示", 13f).apply { typeface = AppUi.medium }, LinearLayout.LayoutParams(-1, dp(30)))
        notation = button("五线谱 + 六线谱") { chooseNotation() }.apply { id = R.id.notation_select }
        displayCard.addView(notation, LinearLayout.LayoutParams(-1, dp(44)))
        displayCard.addView(button("回到播放位置") { sheet.api.scrollToCursor() }.apply {
            background = AppUi.ripple(this@MainActivity, Color.TRANSPARENT); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0); AppUi.iconButton(this, "location", muted, 16)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        val sound = practiceSection()
        sound.addView(button("混音与练习设置") { this@MainActivity.practice.show() }.apply {
            AppUi.iconButton(this, "mix", accent); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(48)))
        sound.addView(sectionLabel("主音量"))
        masterVolume = SeekBar(this).apply {
            id=R.id.master_volume
            max = 100; progress = 100; contentDescription = "播放音量"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, value: Int, user: Boolean) { if (user) sheet.api.masterVolume = value / 100.0 }
                override fun onStartTrackingTouch(view: SeekBar?) {}
                override fun onStopTrackingTouch(view: SeekBar?) {}
            })
        }
        sound.addView(masterVolume, LinearLayout.LayoutParams(-1, dp(32)))
        sound.addView(sectionLabel("音频信息"))
        audioDetails = text("未打开乐谱", 11f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        sound.addView(audioDetails)
        inspector.addView(practice)
        body.addView(inspector, LinearLayout.LayoutParams(dp(244), -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        transport = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppUi.surface(this@MainActivity, AppUi.panel, 0)
        }
        status = text("未打开乐谱", 11f, muted).apply {
            id = R.id.playback_status; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), 0, 0, 0)
        }
        transport.addView(status, LinearLayout.LayoutParams(-1, dp(24)))
        bottomContent = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeline = row()
        progress = SeekBar(this).apply {
            max = 10000; progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(AppUi.line)
            setPadding(dp(9), 0, dp(9), 0)
            contentDescription = "播放进度"; id = R.id.progress
        }
        timeline.addWeighted(progress, 1f, 40)
        position = text("00:00 / 00:00", 12f, muted).apply {
            id = R.id.time_position; typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
            setPadding(dp(6), 0, 0, 0)
        }
        timeline.addView(position); bottomContent.addView(timeline)
        controls = row()
        fun transportButton(button: Button, icon: String? = null): Button = button.apply {
            setTextColor(ink); background = AppUi.ripple(this@MainActivity, AppUi.control, 10)
            icon?.let { AppUi.iconButton(this, it, ink, 17) }
        }
        val restart = transportButton(button("") { sheet.api.stop() }, "restart").apply {
            contentDescription = "回到开头"; tooltipText = "回到开头"; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0); compoundDrawablePadding = 0
        }
        controls.addView(restart, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        play = button("播放", true) { togglePlayback() }.apply {
            id = R.id.play_pause; background = AppUi.ripple(this@MainActivity, accent, 12)
            textSize = 14f; AppUi.iconButton(this, "play", Color.WHITE)
        }
        controls.addWeighted(play, 1.25f, 44)
        speed = transportButton(button("1.0×") { chooseSpeed() }).apply {
            id = R.id.speed_select; typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
        }
        controls.addWeighted(speed, 0.9f, 44)
        loop = transportButton(button("循环") { chooseLoop() }, "loop").apply { id = R.id.loop_select }
        controls.addWeighted(loop, 1.18f, 44)
        bottomContent.addView(controls); transport.addView(bottomContent)
        root.addView(transport)
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) window.statusBarColor = AppUi.rail
        root.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val density=resources.displayMetrics.density
            val width=((right-left-view.paddingLeft-view.paddingRight)/density).toInt()
            val height=((bottom-top-statusInset.layoutParams.height-view.paddingBottom)/density).toInt()
            if(width!=currentWidth || height!=currentHeight) {
                currentWidth=width;currentHeight=height;applyAdaptiveLayout(width)
            }
        }
        currentWidth = resources.configuration.screenWidthDp
        currentHeight = resources.configuration.screenHeightDp
        applyAdaptiveLayout(currentWidth); refreshPanels(); setControlsReady(false)
    }

    private fun sectionLabel(value: String, color: Int = muted) = text(value, 12f, color).apply {
        typeface = AppUi.medium; setPadding(0, dp(14), 0, dp(7))
    }
    private fun numberField(label: String) = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(); textSize = 16f
        gravity = Gravity.CENTER; setTextColor(ink); setText("1"); contentDescription = label
        typeface = AppUi.medium
        background = AppUi.surface(this@MainActivity, AppUi.control, 8, AppUi.line)
    }

    private fun updateReaderChrome() {
        val loaded = score != null
        val compact = currentHeight in 1..420
        inspector.visibility = if (loaded && currentWidth >= 1200 && !fullSheet) View.VISIBLE else View.GONE
        infoPanel.visibility = if (loaded && !fullSheet && !(compact && (currentWidth < 600 || currentHeight < 300))) View.VISIBLE else View.GONE
        practiceShortcut.visibility = if (currentWidth < 1200) View.VISIBLE else View.GONE
        fullScreen.visibility = if (currentWidth >= 600 && (loaded || fullSheet)) View.VISIBLE else View.GONE
        editScoreButton.visibility = if (loaded && !loading) View.VISIBLE else View.GONE
        voicesPanel.visibility = if (loaded) View.VISIBLE else View.GONE
        empty.getChildAt(0).visibility = if (!loaded && loading) View.INVISIBLE else View.VISIBLE
        transport.visibility = if (loaded || loading || importing) View.VISIBLE else View.GONE
        val wide = currentWidth >= 840
        val gutter = if (fullSheet) 0 else if (wide) 18 else 8
        scoreHost.setPadding(dp(gutter), dp(if (fullSheet) 0 else if (!loaded && wide) 18 else 2), dp(gutter), dp(gutter))
    }

    private fun applyAdaptiveLayout(width: Int) {
        if (!::sidebar.isInitialized) return
        val compact=currentHeight in 1..420
        val oneLineInfo=width>=840 || (compact && width>=600)
        val oneLineTransport=width>=840 || (compact && width>=480)
        sidebar.visibility = if (width >= 840 && !fullSheet) View.VISIBLE else View.GONE
        updateReaderChrome()
        fullScreen.text = if (fullSheet) "退出全屏" else "全屏看谱"
        if (width < 600) {
            recentHeader.setCompoundDrawables(null, null, null, null)
            editScoreButton.setCompoundDrawables(null, null, null, null)
            moreButton.setCompoundDrawables(null, null, null, null)
        } else {
            AppUi.iconButton(recentHeader, "clock", AppUi.railMuted)
            AppUi.iconButton(editScoreButton, "edit", AppUi.railMuted)
            AppUi.iconButton(moreButton, "more", AppUi.railMuted)
        }
        val wide = width >= 840
        headerToolbar.layoutParams.height=dp(if(compact) 52 else 60)
        headerToolbar.requestLayout()
        val showBrand=width>=360 && (width>=380 || resources.configuration.fontScale<=1.1f)
        brand.text=if(showBrand) "弦页" else ""
        brand.isClickable=showBrand
        brand.importantForAccessibility=if(showBrand) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        brand.layoutParams.height=dp(if(compact) 44 else 48);brand.requestLayout()
        headerToolbar.setPadding(dp(if (wide) 22 else 14), dp(4), dp(if (wide) 18 else 8), dp(4))
        // One header row on tablets; stack the same controls on phones without detaching the score view.
        infoPanel.orientation = if (oneLineInfo) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        infoPanel.setPadding(dp(if (wide) 24 else 14), dp(if(compact) 4 else if (wide) 14 else 12), dp(if (wide) 18 else 14), dp(if(compact) 4 else 10))
        documentHeading.layoutParams = if (oneLineInfo) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)
        scoreTools.layoutParams = LinearLayout.LayoutParams(if(oneLineInfo) dp(if(compact && !wide) 340 else 360) else -1, dp(44)).apply {
            if(oneLineInfo) marginStart=dp(if(compact) 10 else 16) else topMargin=dp(8)
        }
        title.textSize = if(compact) 19f else if (wide) 30f else 25f
        title.maxLines=1;title.ellipsize=TextUtils.TruncateAt.END
        subtitle.maxLines=1;subtitle.ellipsize=TextUtils.TruncateAt.END
        subtitle.visibility=if(compact) View.GONE else View.VISIBLE
        if (wide) {
            AppUi.iconButton(source, "audio", accent, 16); AppUi.iconButton(track, "track", muted, 16); AppUi.iconButton(zoom, "zoom", muted, 16)
        } else {
            listOf(source, track, zoom).forEach { it.setCompoundDrawables(null, null, null, null) }
        }
        listOf(source,track,zoom).forEach { it.setPadding(dp(if(width<380) 8 else 12),0,dp(if(width<380) 8 else 12),0) }
        val gutter = if (fullSheet) 0 else if (wide) 18 else 8
        scoreFrame.background = AppUi.surface(this, Color.WHITE, if (wide && !fullSheet) 10 else 0, if (gutter == 0) null else AppUi.line)
        scoreFrame.elevation = if (wide && !fullSheet) dp(1).toFloat() else 0f
        transport.setPadding(dp(if (wide) 22 else 12), dp(if(compact) 1 else 3), dp(if (wide) 22 else 12), dp(if(compact) 4 else 8))
        status.layoutParams.height=dp(if(compact) 19 else 23);status.requestLayout()
        bottomContent.orientation = if (oneLineTransport) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val first = if (oneLineTransport) controls else timeline
        if (bottomContent.getChildAt(0) !== first) { bottomContent.removeView(first); bottomContent.addView(first, 0) }
        timeline.layoutParams = if (oneLineTransport) LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(16) }
            else LinearLayout.LayoutParams(-1, dp(if(compact) 28 else 32))
        position.visibility=if(compact && width<600) View.GONE else View.VISIBLE
        controls.layoutParams = LinearLayout.LayoutParams(if(oneLineTransport) dp(if(wide) 358 else if(width>=600) 300 else 264) else -1, dp(44)).apply {
            if(!oneLineTransport) topMargin=dp(2)
        }
        for(control in listOf(play,speed,loop)) control.setPadding(dp(if(width<380 || compact) 8 else 12),0,dp(if(width<380 || compact) 8 else 12),0)
        updatePlayIcon()
        updateLoopStyle()
        // AlphaTab observes its viewport size and reflows itself. Keep the
        // player intact while the window or keyboard changes its dimensions.
    }

    private fun updatePlayIcon() {
        if(currentWidth<380 || (currentHeight in 1..420 && currentWidth<840)) play.setCompoundDrawables(null,null,null,null)
        else AppUi.iconButton(play,if(playing) "pause" else "play",Color.WHITE,17)
    }

    private fun updateLoopStyle() {
        val active = sheet.api.isLooping && sheet.api.playbackRange != null
        loop.text = if (active) "循环中" else "循环"
        loop.setTextColor(if (active) accent else ink)
        loop.background = AppUi.ripple(this, if (active) AppUi.selected else AppUi.control, 10)
        if (currentWidth < 380 || (currentHeight in 1..420 && currentWidth < 840)) loop.setCompoundDrawables(null, null, null, null)
        else AppUi.iconButton(loop, "loop", if (active) accent else ink, 16)
    }

    private fun refreshPanels() {
        updateReaderChrome()
        recentUi.refresh(); voiceList.removeAllViews()
        val loaded = score
        if (loaded != null) for ((index, voice) in loaded.tracks.withIndex()) {
            val label = trackChoiceLabel(voice.name, index)
            val selected = index == activeTrack
            val choice = button(label) { selectTrack(index) }.apply {
                isSelected = selected
                gravity = Gravity.CENTER_VERTICAL; setTextColor(if (selected) AppUi.railAccent else AppUi.railInk)
                background = AppUi.ripple(this@MainActivity, if (selected) AppUi.railActive else Color.TRANSPARENT, 9)
                AppUi.iconButton(this, if (selected) "check" else "track", if (selected) AppUi.railAccent else AppUi.railMuted, 16)
            }
            voiceList.addView(choice, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(3) })
        }
        val syncCount = loaded?.masterBars?.sumOf { it.syncPoints?.length?.toInt() ?: 0 } ?: 0
        audioDetails.text = when {
            factoryMode -> "原厂采样 · 按谱播放\n$factoryNoteCount 个演奏音符\n$fileLabel"
            liveAmp != null -> "按谱合成 · 吉他音箱\n$fileLabel"
            hasAudio && audioMode -> "内嵌录音\n$syncCount 个同步点\n${fileLabel}"
            loaded != null -> "GeneralUser GS 1.44\n按谱合成演奏\n$fileLabel"
            else -> "未打开乐谱"
        }
        sourceNotice.text = when {
            hasAudio && audioMode && !preparedMode -> "内嵌原曲为整首混音。切换声部只改变谱面。"
            else -> ""
        }
        sourceNotice.visibility = if (sourceNotice.text.isEmpty()) View.GONE else View.VISIBLE
        source.setTextColor(if (loaded != null) accent else ink)
        source.background = AppUi.ripple(this, if (loaded != null) AppUi.selected else AppUi.control, 10)
        val percentage = (currentSpeed * 100).roundToInt()
        speedReadout.text = "$percentage%"
        speedReadout.contentDescription = "播放速度 $percentage%，点击调整"
        speedPresets.forEach {
            it.isSelected = it.tag == currentSpeed
            it.setTextColor(if (it.isSelected) accent else ink)
            it.background = AppUi.ripple(this, if (it.isSelected) AppUi.selected else AppUi.control, 8)
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
            .setMessage("本地 GP 乐谱阅读与内嵌音频播放。\n\n乐谱引擎：alphaTab 1.8.4（MPL-2.0）\nhttps://alphatab.net\n\n字体：Bravura（SIL OFL）\n默认合成音色：GeneralUser GS SoftSynth 1.44\n作者：S. Christian Collins\n\n已支持采样按谱播放；部分复杂技法和音色效果尚未支持。\n\n键盘：空格播放 / 暂停，Home 回到开头。")
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
        updateReaderChrome()
        scoreEntry.setReady(ready)
        (listOf(play, speed, speedReadout, loop, progress, applyLoop, clearLoop) + speedPresets).forEach {
            it.isEnabled = ready; it.alpha = if (ready) 1f else 0.4f
        }
        listOf(source, track, zoom, notation, practiceShortcut).forEach { it.isEnabled = score != null && !loading }
        moreButton.isEnabled = !loading
    }

    private fun wirePlayer() {
        sheet.api.midiLoad.on { midi ->
            practice.applyPlaybackMidi(midi)
            preparedScoreTime = PreparedScoreTime(midi)
        }
        sheet.api.error.on { error -> runOnUiThread {
            if (!isDestroyed) {
                resumeAfterToneReload = null
                setControlsReady(false)
                status.text = "播放或显示失败，可尝试切换音源或重新导入。"
                showError("无法处理这份谱子", error)
            }
        } }
        sheet.api.playerReady.on { runOnUiThread {
            if (!loading && score != null && sheet.api.score===score) {
                midiReady = true
                bindEmbeddedOutput()
                if (audioMode && !preparedMode && embeddedReady) sheet.api.updateSyncPoints()
                sheet.api.playbackSpeed = currentSpeed
                practice.reapply()
                restorePlaybackPosition()
                setControlsReady((!audioMode || embeddedReady) && pendingReaderSession==null && restoreTick==null)
                if (restoreAmpSource) {
                    restoreAmpSource = false
                    prepareSynthAmp()
                }
            }
        } }
        sheet.api.playerStateChanged.on { e -> runOnUiThread {
            playing = e.state == PlayerState.Playing
            play.text = if (playing) "暂停" else "播放"
            updatePlayIcon()
            if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); audioManager.abandonAudioFocusRequest(focus) }
        } }
        sheet.api.playerPositionChanged.on { e -> runOnUiThread {
            duration = e.endTime; currentTick = e.currentTick
            updateBarCounter(e.currentTick)
            if (!progress.isPressed) progress.progress = if (duration > 0) (e.currentTime / duration * 10000).toInt() else 0
            position.text = "${formatTime(e.currentTime)} / ${formatTime(duration)}"
            val requested=restoreRequestedTick
            val rewoundLeadIn = requested != null && liveAmp?.let { audio ->
                val raw = preparedScoreTime?.timeAt(requested)?.plus(audio.syncOffsetMs)
                raw != null && audio.firstCueMs > 0 && audio.currentPositionMs < 1.0 &&
                    kotlin.math.abs(raw - audio.firstCueMs) < 1.0
            } == true
            if(requested!=null && (kotlin.math.abs(e.currentTick-requested)<=20.0 || rewoundLeadIn) &&
                (if(audioMode) !e.isSeek else e.isSeek)) {
                // Worker commands and native recording seeks finish after the
                // ready event. A cached setter value is not a seek completion.
                completePlaybackRestore()
            }
            if((pendingReaderSession!=null || restoreTick!=null) && !loading && e.endTick>0) {
                restorePlaybackPosition()
                if(pendingReaderSession==null && restoreTick==null)
                    setControlsReady(midiReady && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
            }
        } }
        sheet.api.playbackRangeChanged.on { runOnUiThread {
            if (pendingReaderSession==null && restoreTick==null && sheet.api.playbackRange == null && sheet.api.isLooping) sheet.api.isLooping = false
            updateLoopStyle()
            play.post { updateLiveLoop() }
        } }
        sheet.api.playerFinished.on {
            // Post after alphaTab has rewound its loop position.
            play.post {
                if (sheet.api.isLooping && (!audioMode || liveAmp != null)) trainer.onLoopFinished()?.let { changeSpeed(it, false) }
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

    private fun completePlaybackRestore() {
        restoreRequestedTick = null
        pendingReaderSession = null
        restoreTick = null
        restoreBarPosition = null
        setControlsReady(!loading && midiReady && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
        val resume = resumeAfterToneReload
        resumeAfterToneReload = null
        if (resume == true && (!audioMode || liveAmp != null) && !loading && !isFinishing && !isDestroyed) startPlayback()
    }

    private fun formatTime(ms: Double): String {
        val seconds = (ms / 1000).toInt().coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun updateBarCounter(tick: Double) {
        val loaded = score ?: return
        val bars = sheet.api.tickCache?.masterBars ?: return
        val size = bars.length.toInt()
        if (size == 0 || !tick.isFinite()) return
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            // The player reports whole ticks. A seek may land one tick short at an exact bar line.
            if (bars[middle].start <= tick + 1.0) low = middle + 1 else high = middle
        }
        val number = bars[(low - 1).coerceAtLeast(0)].masterBar.index.toInt() + 1
        if (number == displayedBar) return
        displayedBar = number
        barCounter.text = "小节 $number / ${loaded.masterBars.length.toInt()}"
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        if (loading || importing) { Toast.makeText(this, "正在处理乐谱，请稍候。", Toast.LENGTH_SHORT).show(); return }
        startActivityForResult(importIntent(), 10)
    }

    @Suppress("DEPRECATION")
    private fun pickRecording() {
        if (loading || score == null) return
        pausePlayback()
        val pick = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_RECORDING)
        }
        if (hasAudio) AlertDialog.Builder(this).setTitle("替换内嵌录音？")
            .setMessage("替换后会从头校准同步点。可以撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择录音") { _, _ -> pick() }.show()
        else pick()
    }

    internal fun importRecording(uri: Uri) {
        val current = score ?: return
        if (loading) return
        val token = generation
        pausePlayback()
        loading = true
        setControlsReady(false)
        loadingIndicator.visibility = View.VISIBLE
        status.text = "正在读取录音…"
        io.execute {
            val result = runCatching {
                val source = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开所选录音。")
                source.use { RecordingAudio.read(it, cacheDir) }
            }
            runOnUiThread {
                if (isDestroyed || generation != token || score !== current) return@runOnUiThread
                loading = false
                loadingIndicator.visibility = View.GONE
                result.mapCatching { clip ->
                    if (scoreEntry.isOpen) setScoreEditing(false)
                    if (editor.replaceRecording(clip)) status.text = "已导入内嵌录音 · ${formatTime(clip.durationMs.toDouble())}"
                }
                    .onFailure { error ->
                        status.text = "录音导入失败。"
                        setControlsReady(midiReady && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
                        showError("无法导入录音", error)
                    }
            }
        }
    }

    private fun confirmRemoveRecording() {
        AlertDialog.Builder(this).setTitle("移除内嵌录音？")
            .setMessage("当前录音及同步点会从乐谱中移除。可以撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ -> editor.replaceRecording(null) }.show()
    }

    private fun showRecordingSync() {
        val loaded = score ?: return
        val file = embeddedFile ?: return
        val lookup = sheet.api.tickCache
        if (lookup == null || sheet.api.score !== loaded) {
            Toast.makeText(this, "谱面正在准备，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        val visits = ScoreRecording.visits(lookup)
        if (visits.isEmpty()) return
        val tick = sheet.api.tickPosition
        val time = if (audioMode && !preparedMode) embedded.currentPositionMs else null
        pausePlayback()
        recordingDialog?.dismiss()
        recordingDialog = RecordingSyncDialog(this, file, visits, tick,
            time ?: preparedScoreTime?.timeAt(tick) ?: 0.0, ScoreRecording.points(loaded)) { points, duration ->
            if (score === loaded) editor.setRecordingPoints(points, visits, duration)
        }.show()
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
        if (requestCode == REQUEST_RECORDING) {
            if (resultCode == RESULT_OK) data?.data?.let(::importRecording)
            return
        }
        if (requestCode == GuitarAmpSetup.REQUEST_CABINET) {
            if (ampSetupDelegate.isInitialized()) ampSetup.acceptCabinet(if (resultCode == RESULT_OK) data?.data else null)
            return
        }
        if (requestCode == com.stringpage.app.rse.BankLibraryUi.REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null) bankLibrary.acceptImport(selectedImportUris(data))
            return
        }
        if (requestCode == 11) {
            val pending = exportFile; val format = exportFormat; val exportedScore = exportScore
            exportFile = null; exportFormat = null; exportScore = null
            if (resultCode != RESULT_OK || data?.data == null) { pending?.delete(); return }
            val destination = data.data!!
            if (pending == null || !pending.isFile || format == null) { status.text = "导出数据已失效，请重新导出。"; return }
            io.execute {
                try {
                    contentResolver.openOutputStream(destination, "w")?.use { output -> pending.inputStream().use { it.copyTo(output) } }
                        ?: throw IllegalStateException("无法写入所选位置。")
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        status.text = "已导出 ${format.label}"
                        if (format == ExportKind.GUITAR_PRO && score === exportedScore) {
                            modified = false; updateDocumentSubtitle(); io.execute { drafts.delete() }
                        }
                    }
                } catch (error: Exception) { runOnUiThread { if (!isDestroyed) showError("导出失败", error) } }
                finally { pending.delete() }
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
        if (contentResolver.getType(uri) == "application/pdf") openPdf(uri) else openUri(uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.data != null) openIncoming(intent)
    }

    private fun openIncoming(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.type == "application/pdf") openPdf(uri) else openUri(uri)
    }

    private fun openPdf(uri: Uri? = null) {
        if (loading || importing) return
        pausePlayback()
        startActivity(Intent(this, com.stringpage.app.pdf.PdfConvertActivity::class.java).apply {
            data = uri
            if (uri != null) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
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
        pausePlayback()
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

    private fun showNewScore() {
        if (loading || importing || pdfPreview) return
        val instruments = ScoreStructure.Instrument.entries
        val name = EditText(this).apply {
            hint = "曲名"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        val bpm = EditText(this).apply {
            hint = "速度（BPM）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("100")
            setSelectAllOnFocus(true)
        }
        val instrument = Spinner(this).apply {
            contentDescription = "第一条轨道的乐器"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, instruments.map { it.label })
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(14))
            isFocusableInTouchMode = true
            addView(name)
            addView(text("速度（BPM）", 13f))
            addView(bpm)
            addView(text("第一条轨道", 13f))
            addView(instrument, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val dialog = AlertDialog.Builder(this).setTitle("新建乐谱").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("新建", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                val tempo = bpm.text.toString().toIntOrNull()
                if (title.isEmpty()) {
                    name.error = "请输入曲名。"
                } else if (tempo == null || tempo !in 20..400) {
                    bpm.error = "请输入 20–400。"
                } else {
                    val chosen = instruments[instrument.selectedItemPosition.coerceAtLeast(0)]
                    dialog.dismiss()
                    guardDocumentChange {
                        loadFile({
                            "$title.gp" to ScoreExport.guitarProBytes(ScoreStructure.newScore(title, chosen, tempo, sheet.settings))
                        }, null, newDocument = true)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun guardDocumentChange(action: () -> Unit) {
        if (!modified) { action(); return }
        AlertDialog.Builder(this).setTitle("修改尚未导出")
            .setMessage("导出 GP 副本可保留当前修改。继续切换会丢弃当前草稿；取消可留在当前乐谱。")
            .setPositiveButton("导出副本") { _, _ -> requestExport(ExportKind.GUITAR_PRO) }
            .setNeutralButton("丢弃修改") { _, _ -> modified = false; io.execute { drafts.delete() }; action() }
            .setNegativeButton("取消", null).show()
    }

    private fun loadFile(read: () -> Pair<String, ByteArray>, uri: Uri?, recovered: Boolean = false, newDocument: Boolean = false) {
        recordingDialog?.dismiss()
        recordingDialog = null
        if (scoreEntry.isOpen) setScoreEditing(false)
        resumeAfterToneReload = null
        if (ampSetupDelegate.isInitialized()) ampSetup.discardScore()
        if (factorySetupDelegate.isInitialized()) factorySetup.cancel()
        pausePlayback()
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
                    ScoreLyrics.restore(parsed, properties)
                } catch (error: Exception) {
                    com.stringpage.app.rse.GpRseProperties.bindFailure(parsed, error.message ?: "无法读取原谱的音源设置。")
                }
                val rawAudio = parsed.backingTrack?.rawAudioFile
                val lyrics = ScoreLyrics.trackIndexes(parsed)
                val audioFile = rawAudio?.takeIf { it.length > 0 }?.let { RecordingAudio.materialize(it, cacheDir) }
                runOnUiThread {
                    if (token != generation || isDestroyed) {
                        audioFile?.delete()
                        return@runOnUiThread
                    }
                    loading = false; loadingIndicator.visibility = View.GONE
                    val key = uri?.toString()
                    val resume=pendingReaderSession?.takeIf { it.document==key }
                    if(resume==null) pendingReaderSession=null
                    restoreRequestedTick=null
                    restoreBarPosition=null
                    score = parsed; fileLabel = label; documentKey=key
                    lyricTracks = lyrics
                    activeTrack=(resume?.track ?: 0).coerceIn(0,parsed.tracks.length.toInt()-1)
                    modified = recovered || newDocument; restoreTick = null; trainer.stop()
                    editor.reset(parsed); practice.reset(parsed)
                    if (!modified && !pdfPreview) io.execute { drafts.delete() }
                    hasAudio = (parsed.backingTrack?.rawAudioFile?.length ?: 0.0) > 0.0
                    audioMode = hasAudio && (resume==null || resume.source=="embedded"); factoryMode = false
                    restoreAmpSource = resume?.source == "amp"
                    embedded.release(); embeddedReady = false; midiReady = false; preparedScoreTime = null
                    liveAmp?.close(); liveAmp = null
                    embeddedFile?.delete(); embeddedFile = audioFile
                    currentSpeed = resume?.speed ?: 1.0; speed.text = "${currentSpeed}×"
                    resume?.let {
                        sheet.settings.display.scale=it.scale
                        sheet.settings.display.staveProfile=runCatching { alphaTab.StaveProfile.valueOf(it.notation) }.getOrDefault(alphaTab.StaveProfile.ScoreTab)
                    }
                    zoom.text="${(sheet.settings.display.scale*100).roundToInt()}%"
                    notation.text=when(sheet.settings.display.staveProfile) {
                        alphaTab.StaveProfile.Tab -> "六线谱"
                        alphaTab.StaveProfile.Score -> "五线谱"
                        else -> "五线谱 + 六线谱"
                    }
                    loopFromBar=null;loopToBar=null
                    sheet.api.isLooping = false; sheet.api.playbackRange = null; updateLoopStyle()
                    duration = 0.0; progress.progress = 0; position.text = "00:00 / 00:00"
                    title.text = parsed.title.ifBlank { label.substringBeforeLast('.') }
                    subtitle.text = listOf(parsed.artist, "${parsed.masterBars.length.toInt()} 小节", "${parsed.tracks.length.toInt()} 个声部")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    updateDocumentSubtitle()
                    source.text = if (audioMode) "内嵌原曲" else "合成音源"
                    track.text = parsed.tracks[activeTrack].name.ifBlank { "声部 ${activeTrack+1}" }
                    val syncCount = parsed.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 }
                    status.text = when {
                        newDocument -> "点按休止符即可输入音符。"
                        resume?.source=="prepared" -> "已恢复练习设置；采样音频需重新准备。"
                        audioMode && syncCount > 0 -> "已读取内嵌音频及 $syncCount 个同步点 · 点击谱面可跳转"
                        audioMode -> "已读取内嵌音频；缺少同步点，跟谱位置可能有偏差。"
                        hasAudio -> "按谱合成演奏 · 可切换到内嵌录音"
                        else -> "这份谱子没有可读取的内嵌音频，当前按谱合成播放。"
                    }
                    sheet.settings.player.playerMode = if (audioMode) PlayerMode.EnabledExternalMedia else PlayerMode.EnabledSynthesizer
                    sheet.api.updateSettings()
                    bindEmbeddedOutput()
                    if (audioMode && audioFile != null) prepareEmbedded(audioFile)
                    empty.visibility = View.GONE
                    inlineFrom.setText("1"); inlineTo.setText(parsed.masterBars.length.toInt().toString())
                    displayedBar = 1
                    barCounter.text = "小节 1 / ${parsed.masterBars.length.toInt()}"
                    sheet.tracks = listOf(parsed.tracks[activeTrack])
                    if (uri != null) { saveRecent(uri, title.text.toString()); prefs.edit().putString("lastUri", uri.toString()).apply() }
                    else if (!pdfPreview) prefs.edit().remove("lastUri").apply()
                    refreshPanels()
                    if (newDocument) {
                        saveDraft()
                        setScoreEditing(true)
                    }
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
            val output = sheet.api.player?.output as? IExternalMediaSynthOutput
            val amp = liveAmp
            if (amp == null) embedded.output = output else amp.output = output
            if (preparedMode) applyPreparedSync()
        }
    }

    private fun applyPreparedSync() {
        val audio = liveAmp ?: return
        val timing = preparedScoreTime ?: return
        sheet.api.player?.updateSyncPoints(timing.syncPoints(audio.syncOffsetMs, audio.backingTrackDuration))
        updateLiveLoop()
    }

    private fun updateLiveLoop() {
        val audio = liveAmp ?: return
        val timing = preparedScoreTime ?: return
        val range = sheet.api.playbackRange?.takeIf { sheet.api.isLooping }
        val end = range?.let {
            if (it.endTick >= sheet.api.endTick) audio.backingTrackDuration
            else timing.timeAt(it.endTick) + audio.syncOffsetMs
        }
        audio.setLoop(range?.let { timing.timeAt(it.startTick) + audio.syncOffsetMs }, end)
    }

    private fun restorePlaybackPosition() {
        if (!midiReady || !sheet.api.isReadyForPlayback || sheet.api.endTick<=0 || sheet.api.score!==score || (audioMode && !embeddedReady)) return
        if(restoreRequestedTick!=null) return
        val identity = documentKey ?: transientPlaybackKey
        pendingReaderSession?.takeIf { it.document == identity }?.let { session ->
            restoreTick=null
            val target = (editedBarTick() ?: session.tick).coerceAtMost((sheet.api.endTick-1).coerceAtLeast(0.0))
            // Setting the range can synchronously report a seek. Mark the
            // whole operation in flight before any player setter can reenter.
            restoreRequestedTick=target
            sheet.api.masterVolume=session.masterVolume
            masterVolume.progress=(session.masterVolume*100).roundToInt()
            val range = session.loop?.let { saved ->
                val endpoints = if (saved.fromBar != null && saved.toBar != null) barLoopTicks(saved.fromBar, saved.toBar) else null
                if (endpoints == null) saved else saved.copy(start = endpoints.first, end = endpoints.second)
            }?.takeIf { it.start < sheet.api.endTick && it.end <= sheet.api.endTick }
            sheet.api.playbackRange=range?.let { saved -> PlaybackRange().apply { startTick=saved.start;endTick=saved.end } }
            sheet.api.isLooping=range?.enabled ?: false
            updateLiveLoop()
            loopFromBar=range?.fromBar;loopToBar=range?.toBar
            inlineFrom.setText((loopFromBar ?: 1).toString())
            inlineTo.setText((loopToBar ?: score?.masterBars?.length?.toInt() ?: 1).toString())
            updateLoopStyle()
            sheet.api.tickPosition=target
        }
        if(restoreRequestedTick==null) restoreTick?.let { tick ->
            restoreRequestedTick=tick.coerceAtMost((sheet.api.endTick-1).coerceAtLeast(0.0))
            sheet.api.tickPosition=restoreRequestedTick!!
        }
    }

    private fun barPositionAfterEdit(tick: Double, timing: ScoreStructure.TimingEdit, next: Score): BarPosition? {
        val lookup = sheet.api.tickCache?.masterBars?.toList() ?: return null
        val at = lookup.indexOfLast { it.start <= tick + 1.0 }
        val previous = lookup.getOrNull(at) ?: return null
        val written = previous.masterBar.index.toInt()
        val mapped = timing.map(written)
        val after = mapped ?: (timing as? ScoreStructure.BarEdit)?.index?.coerceAtMost(next.masterBars.length.toInt() - 1)
            ?: return null
        val pass = if (mapped == null) 0 else (0 until at).count { lookup[it].masterBar.index.toInt() == written }
        val offset = if (mapped == null) 0.0 else (tick - previous.start).coerceAtLeast(0.0)
        return BarPosition(after, pass, offset)
    }

    private fun editedBarTick(): Double? {
        val anchor = restoreBarPosition ?: return null
        val matches = sheet.api.tickCache?.masterBars?.filter { it.masterBar.index.toInt() == anchor.bar }?.toList()
            ?: return null
        val position = matches.getOrNull(anchor.occurrence) ?: matches.lastOrNull() ?: return null
        return position.start + anchor.offset.coerceAtMost((position.end - position.start - 1.0).coerceAtLeast(0.0))
    }

    private val transientPlaybackKey get() = "practice-session:$generation"

    private fun captureReaderSession(allowTransient: Boolean = false): ReaderSession? {
        val document = documentKey ?: if (allowTransient) transientPlaybackKey else return null
        if(score==null) return null
        val waiting=pendingReaderSession?.takeIf { it.document==document }
        val current=sheet.api.score===score
        val range=if(waiting!=null) waiting.loop else if(current) sheet.api.playbackRange?.let {
            ReaderSession.Loop(it.startTick,it.endTick,sheet.api.isLooping,loopFromBar,loopToBar)
        } else null
        return ReaderSession(document,activeTrack,when {
            factoryMode -> "prepared"
            liveAmp != null -> "amp"
            audioMode -> "embedded"
            else -> "synth"
        },
            waiting?.tick ?: if(current) sheet.api.tickPosition else 0.0,currentSpeed,sheet.settings.display.scale,
            sheet.settings.display.staveProfile.name,fullSheet,waiting?.masterVolume ?: if(current) sheet.api.masterVolume else 1.0,range)
    }

    private fun reloadTonePlayback(): Boolean {
        val api = sheet.api
        if (loading || audioMode || !midiReady || !api.isReadyForPlayback ||
            api.score !== score || pendingReaderSession != null || restoreTick != null || restoreRequestedTick != null) return false
        val session = captureReaderSession(allowTransient = true) ?: return false
        val resume = api.playerState == PlayerState.Playing
        pausePlayback()
        resumeAfterToneReload = resume
        pendingReaderSession = session
        midiReady = false
        setControlsReady(false)
        api.loadMidiForScore()
        return true
    }

    private val factorySetupDelegate = lazy { com.stringpage.app.rse.FactoryPlaybackSetup(this,
        { score === it }, { preparing ->
            loading = preparing
            loadingIndicator.visibility = if (preparing) View.VISIBLE else View.GONE
            setControlsReady(!preparing && score != null && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
        }, { bankLibrary.show() }, this::acceptFactoryAudio, { ampSetup.settingsFor(it, documentKey) }) }
    private val factorySetup by factorySetupDelegate

    private val ampSetupDelegate = lazy {
        GuitarAmpSetup(this, { score === it }, { preparing ->
            loading = preparing
            loadingIndicator.visibility = if (preparing) View.VISIBLE else View.GONE
            setControlsReady(!preparing && score != null && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
        }, this::applyLiveAmps, this::acceptAmpAudio, { editor.trackSlot(it) })
    }
    private val ampSetup by ampSetupDelegate

    internal fun applyLiveAmps(values: Map<Int, GuitarAmpSettings>): Boolean {
        val amp = liveAmp ?: return false
        amp.setAmps(values)
        return true
    }

    private fun chooseAmpSettings(index: Int = activeTrack) {
        val loaded = score ?: return
        if (!practice.canChooseTone(index)) return
        ampSetup.show(loaded, documentKey, index, sheet.settings, practice.snapshotTones(),
            practice.snapshot().transposition, liveAmp != null)
    }

    private fun prepareSynthAmp() {
        val loaded = score ?: return
        if (!loaded.tracks.any(GuitarTone::supports)) return
        ampSetup.prepare(loaded, sheet.settings, practice.snapshotTones(), ampSetup.settingsFor(loaded, documentKey),
            practice.snapshot().transposition)
    }

    internal fun acceptAmpAudio(expected: Score, stems: AmpStems, settings: Map<Int, GuitarAmpSettings>) =
        acceptLiveAudio(expected, stems, settings, null)

    private fun acceptLiveAudio(
        expected: Score, stems: AmpStems, settings: Map<Int, GuitarAmpSettings>, factory: FactoryScoreRenderer.StemResult?
    ) {
        if (score !== expected || isDestroyed) { stems.close(); return }
        val api = sheet.api
        val session = captureReaderSession(allowTransient = true)?.copy(source = if (factory == null) "amp" else "prepared")
        val resume = factory == null && api.playerState == PlayerState.Playing
        val externalWasReady = api.player?.output is IExternalMediaSynthOutput && api.isReadyForPlayback
        midiReady = false; pendingReaderSession = null; restoreRequestedTick = null; restoreTick = null
        pausePlayback(); embedded.release(); embeddedReady = false
        liveAmp?.close()
        val audio = StemAudio(stems, settings) { error ->
            pausePlayback()
            switchSource(false)
            showError("实时音箱播放失败", error)
        }
        liveAmp = audio
        factoryMode = factory != null
        factoryNoteCount = factory?.noteCount ?: 0
        audioMode = true; midiReady = externalWasReady
        resumeAfterToneReload = resume
        pendingReaderSession = session
        setControlsReady(false)
        sheet.settings.player.playerMode = PlayerMode.EnabledExternalMedia
        api.updateSettings()
        audio.open {
            if (liveAmp !== audio || isDestroyed) return@open
            embeddedReady = true
            bindEmbeddedOutput()
            audio.masterVolume = api.masterVolume
            api.playbackSpeed = currentSpeed
            restorePlaybackPosition()
            if (midiReady && !loading) setControlsReady(pendingReaderSession == null && restoreTick == null)
        }
        api.render()
        source.text = if (factoryMode) "原厂采样" else "音箱混音"
        status.text = if (factory != null) {
            "已准备 ${factory.noteCount} 个演奏音符" +
                if (factory.substitutedArticulations > 0) " · ${factory.substitutedArticulations} 个音符使用替代技法采样" else ""
        } else "已准备 ${stems.guitarTracks.size} 个吉他声部"
        refreshPanels()
    }

    private fun chooseFactorySource() {
        val loaded = score ?: return
        if (ampSetupDelegate.isInitialized()) ampSetup.cancel()
        pausePlayback(); embedded.pause()
        factorySetup.show(loaded, sheet.settings, practice.snapshot().transposition)
    }

    internal fun acceptFactoryAudio(expected: Score, result: FactoryScoreRenderer.StemResult) =
        acceptLiveAudio(expected, result.stems, ampSetup.settingsFor(expected, documentKey), result)

    private val bankLibraryDelegate = lazy { com.stringpage.app.rse.BankLibraryUi(this) { pausePlayback(); embedded.pause() } }
    private val bankLibrary by bankLibraryDelegate

    private fun showMore() {
        if (loading) return
        val available = score != null
        val sections = buildList {
            if (available) {
                val shortcuts = buildList {
                    if (practice.canChooseTone(activeTrack)) {
                        add(ReaderMenu.Action("吉他效果器", "strings", featured = true) { chooseAmpSettings() })
                        add(ReaderMenu.Action("当前声部音色", "audio") { practice.showTone(activeTrack) })
                    }
                    add(ReaderMenu.Action("播放音源", "audio", source.text.toString()) { chooseSource() })
                    add(ReaderMenu.Action("混音与练习", "mix") { practice.show() })
                }
                add(ReaderMenu.Section("播放与练习", listOf(ReaderMenu.Action("逐轮加速练习", "tempo") { showTrainer() }), shortcuts))
                add(ReaderMenu.Section("谱面", listOf(
                    ReaderMenu.Action(if (scoreEntry.isOpen) "完成谱面编辑" else "在谱面编辑", "edit") { setScoreEditing(!scoreEntry.isOpen) },
                    ReaderMenu.Action("MIDI 输入", "audio", if (midiInputDelegate.isInitialized()) midiInput.portLabel.orEmpty() else "") { chooseMidiInput() },
                    ReaderMenu.Action("选择声部", "track", track.text.toString()) { chooseTrack() },
                    ReaderMenu.Action("谱面大小", "zoom", zoom.text.toString()) { chooseZoom() },
                    ReaderMenu.Action(if (fullSheet) "退出全屏" else "全屏看谱", "full") {
                        fullSheet = !fullSheet
                        applyAdaptiveLayout(currentWidth)
                    }
                )))
            }
            val other = buildList {
                if (!importing && !pdfPreview) add(ReaderMenu.Action("新建乐谱", "edit") { showNewScore() })
                if (available) {
                    if (!pdfPreview) {
                        val recording = buildList {
                            if (hasAudio) add(ReaderMenu.Action("校准录音同步点", "tempo", enabled = embeddedFile != null) { showRecordingSync() })
                            add(ReaderMenu.Action(if (hasAudio) "替换内嵌录音" else "导入本地录音", "audio") { pickRecording() })
                            if (hasAudio) add(ReaderMenu.Action("移除内嵌录音", "audio") { confirmRemoveRecording() })
                        }
                        add(ReaderMenu.Action("录音与同步", "audio", children = recording))
                    }
                    val fileActions = buildList {
                        if (!pdfPreview) {
                            add(ReaderMenu.Action("基础编辑", "edit") { pausePlayback(); editor.show() })
                            add(ReaderMenu.Action("撤销", "undo", enabled = editor.canUndo) { pausePlayback(); editor.undo() })
                            add(ReaderMenu.Action("重做", "redo", enabled = editor.canRedo) { pausePlayback(); editor.redo() })
                        }
                        add(ReaderMenu.Action("导出 GP 兼容副本", "export") { requestExport(ExportKind.GUITAR_PRO) })
                        add(ReaderMenu.Action("导出 MIDI", "export") { requestExport(ExportKind.MIDI) })
                        add(ReaderMenu.Action("导出 WAV 合成混音", "audio") { requestExport(ExportKind.WAV) })
                        add(ReaderMenu.Action("导出打印版 PDF", "export") { choosePrintExport() })
                    }
                    add(ReaderMenu.Action(if (pdfPreview) "导出" else "编辑与导出", "file", children = fileActions))
                }
                if (!importing && !pdfPreview) add(ReaderMenu.Action("PDF 转 GP", "file") { openPdf() })
                add(ReaderMenu.Action("原厂音色库 · 采样预览", "audio") { bankLibrary.show() })
                add(ReaderMenu.Action("关于", "info") { showAbout() })
            }
            add(ReaderMenu.Section("文件与应用", other))
        }
        readerMenu.show(moreButton, sections)
    }

    private fun applyEditedScore(next: Score, description: String, edit: ScoreStructure.Edit?) {
        val timing = edit as? ScoreStructure.TimingEdit
        val bars = timing as? ScoreStructure.BarEdit
        val tracks = edit as? ScoreStructure.TrackEdit
        val mappedTrack = if (tracks == null) activeTrack else tracks.map(activeTrack) ?: tracks.index
        val selectedTrack = mappedTrack.coerceIn(0, next.tracks.length.toInt() - 1)
        val checkpoint = captureReaderSession(allowTransient = edit != null)
        val at = currentTick
        val position = timing?.let { barPositionAfterEdit(checkpoint?.tick ?: at, it, next) }
        resumeAfterToneReload = null
        if (ampSetupDelegate.isInitialized()) ampSetup.cancel()
        if (preparedMode) switchSource(false)
        pausePlayback()
        val recordingChanged = edit == ScoreStructure.RecordingEdit
        val raw = next.backingTrack?.rawAudioFile?.takeIf { it.length > 0 }
        if (recordingChanged) {
            embedded.release()
            embeddedReady = false
            embeddedFile?.delete()
            embeddedFile = null
            hasAudio = raw != null
            audioMode = hasAudio
            factoryMode = false
            trainer.stop()
            source.text = if (audioMode) "内嵌原曲" else "合成音源"
            sheet.settings.player.playerMode = if (audioMode) PlayerMode.EnabledExternalMedia else PlayerMode.EnabledSynthesizer
        }
        pendingReaderSession = checkpoint?.copy(
            track = selectedTrack,
            tick = if (recordingChanged) 0.0 else checkpoint.tick,
            source = if (audioMode) "embedded" else "synth",
            loop = if (bars == null) checkpoint.loop else checkpoint.loop?.let { loop ->
                val from = loop.fromBar ?: return@let null
                val to = loop.toBar ?: return@let null
                val start = bars.map(from - 1) ?: bars.index
                val end = bars.map(to - 1) ?: (bars.index - 1)
                if (start <= end) loop.copy(fromBar = start + 1, toBar = end + 1) else null
            }
        )
        restoreBarPosition = position
        restoreRequestedTick = null
        restoreTick = if (checkpoint == null) at else null
        score = next; modified = true; midiReady = false
        lyricTracks = ScoreLyrics.trackIndexes(next)
        activeTrack = selectedTrack
        tracks?.let(scoreEntry::applyTrackEdit)
        practice.reset(next, true)
        title.text = next.title.ifBlank { fileLabel.substringBeforeLast('.') }
        updateDocumentSubtitle()
        if (timing != null) trainer.stop()
        if (bars != null) {
            scoreEntry.applyBarEdit(bars)
            displayedBar = (position?.bar ?: bars.map(displayedBar - 1) ?: bars.index) + 1
        }
        status.text = if (timing != null && hasAudio) "$description · 请试听改动附近的录音同步。" else description
        setControlsReady(false)
        if (recordingChanged) sheet.api.updateSettings()
        sheet.tracks = listOf(next.tracks[activeTrack])
        if (recordingChanged && raw != null) loadEditedRecording(raw)
        track.text = next.tracks[activeTrack].name
        val lastBar = next.masterBars.length.toInt()
        displayedBar = displayedBar.coerceIn(1, lastBar)
        barCounter.text = "小节 $displayedBar / $lastBar"
        if (loopToBar == null) inlineTo.setText(lastBar.toString())
        refreshPanels()
        saveDraft()
    }

    private fun loadEditedRecording(raw: Uint8Array) {
        val load = generation
        io.execute {
            val result = runCatching { RecordingAudio.materialize(raw, cacheDir) }
            runOnUiThread {
                if (load != generation || score?.backingTrack?.rawAudioFile !== raw || isDestroyed || embeddedFile != null) {
                    result.getOrNull()?.delete()
                    return@runOnUiThread
                }
                result.onSuccess { file ->
                    embeddedFile = file
                    if (audioMode && !preparedMode) prepareEmbedded(file)
                }.onFailure { error ->
                    switchSource(false)
                    showError("无法准备录音播放", error)
                }
            }
        }
    }

    private fun chooseMidiInput() {
        if (loading || score == null) return
        val connected = midiInput.portLabel
        val ports = midiInput.ports()
        val dialog = AlertDialog.Builder(this).setTitle("MIDI 输入").setNegativeButton("取消", null)
        if (ports.isEmpty()) {
            dialog.setMessage(if (midiInput.isSupported)
                "未找到 MIDI 输入设备。请连接 USB MIDI 键盘，然后刷新列表。"
            else "当前设备未提供 Android MIDI 服务。")
            if (midiInput.isSupported) dialog.setPositiveButton("刷新") { _, _ -> chooseMidiInput() }
        } else {
            dialog.setSingleChoiceItems(ports.map { it.label }.toTypedArray(), ports.indexOfFirst { it.label == connected }) { picker, index ->
                picker.dismiss()
                midiInput.connect(ports[index])
            }
        }
        if (connected != null) dialog.setNeutralButton("断开") { _, _ ->
            midiInput.disconnect()
            scoreEntry.setMidiPort(null)
        }
        dialog.show()
    }

    private fun setScoreEditing(enabled: Boolean) {
        if (enabled && (loading || score == null)) return
        if (enabled) {
            pausePlayback()
            scoreEntry.open()
            if (audioMode) switchSource(false)
        } else scoreEntry.close()
        editScoreButton.text = if (enabled) "完成" else "编辑"
        editScoreButton.isSelected = enabled
        editScoreButton.contentDescription = if (enabled) "完成谱面编辑" else "编辑谱面"
        sheet.settings.player.enableUserInteraction = !enabled
        sheet.api.updateSettings()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var editTap = false
        if (::scoreEntry.isInitialized && scoreEntry.isOpen) {
            val x = event.rawX
            val y = event.rawY
            val slop = ViewConfiguration.get(this).scaledTouchSlop
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val bounds = Rect()
                    val origin = IntArray(2).also(sheet::getLocationOnScreen)
                    scoreTap = if (sheet.getLocalVisibleRect(bounds) && bounds.contains(x.toInt() - origin[0], y.toInt() - origin[1])) {
                        PointF(x, y)
                    } else null
                }
                MotionEvent.ACTION_MOVE -> scoreTap?.let { if (kotlin.math.abs(x - it.x) > slop || kotlin.math.abs(y - it.y) > slop) scoreTap = null }
                MotionEvent.ACTION_UP -> {
                    val start = scoreTap
                    scoreTap = null
                    if (start != null && kotlin.math.abs(x - start.x) <= slop && kotlin.math.abs(y - start.y) <= slop) {
                        editTap = true
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> scoreTap = null
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (editTap) selectScorePosition(event)
        return handled
    }

    private fun selectScorePosition(event: MotionEvent) {
        val bounds = sheet.api.boundsLookup ?: return
        val surface = sheet.findViewById<View>(net.alphatab.R.id.renderSurface) ?: return
        val viewport = sheet.findViewById<View>(net.alphatab.R.id.outerScroll) ?: return
        val width = viewport.measuredWidth
        if (width <= 0) return
        // Use the renderer's own pixel ratio, including after a display-density change.
        val scale = sheet.api.container.width / width
        val origin = IntArray(2).also(surface::getLocationOnScreen)
        val x = (event.rawX - origin[0]) * scale
        val y = (event.rawY - origin[1]) * scale
        val target = ScoreTouchTarget.find(bounds, x, y) ?: return
        scoreEntry.tap(target.beat, target.note)
    }

    private fun updateDocumentSubtitle() {
        val current = score ?: return
        val lyrics = when {
            activeTrack in lyricTracks -> "含歌词"
            lyricTracks.isNotEmpty() -> "歌词见声部列表"
            else -> ""
        }
        subtitle.text = listOf(current.artist, "${current.masterBars.length.toInt()} 小节",
            "${current.tracks.length.toInt()} 个声部", lyrics, if (modified) "未导出修改" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun saveDraft() {
        val current = score ?: return
        io.execute {
            try { drafts.write(ScoreExport.guitarProBytes(current)) }
            catch (error: Exception) {
                android.util.Log.w("StringPage", "Failed to write score recovery copy", error)
                runOnUiThread { if (score === current && !isDestroyed) status.text = "草稿写入失败，请导出副本保存修改。" }
            }
        }
    }

    private fun choosePrintExport() {
        val current = score ?: return
        if (loading) return
        val count = current.masterBars.length.toInt()
        fun choices(options: List<String>) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
        }
        fun number(initial: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(initial.toString())
            setSelectAllOnFocus(true)
        }
        val papers = ScorePrintExport.Paper.entries
        val paper = choices(papers.map { it.label }).apply { contentDescription = "打印纸张" }
        val tracks = choices(listOf("当前轨道", "全部轨道")).apply { contentDescription = "打印轨道范围" }
        val profiles = listOf(alphaTab.StaveProfile.ScoreTab, alphaTab.StaveProfile.Score, alphaTab.StaveProfile.Tab)
        val notation = choices(listOf("五线谱 + 六线谱", "五线谱", "六线谱（有指板的轨道）")).apply {
            contentDescription = "打印谱面"
            setSelection(profiles.indexOf(sheet.settings.display.staveProfile).coerceAtLeast(0))
        }
        val from = number(1).apply { contentDescription = "打印起始小节" }
        val to = number(count).apply { contentDescription = "打印结束小节" }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
            isFocusableInTouchMode = true
            addView(text("纸张", 13f))
            addView(paper, LinearLayout.LayoutParams(-1, dp(46)))
            addView(text("轨道", 13f))
            addView(tracks, LinearLayout.LayoutParams(-1, dp(46)))
            addView(text("记谱方式", 13f))
            addView(notation, LinearLayout.LayoutParams(-1, dp(46)))
            addView(text("小节范围", 13f))
            addView(row().apply {
                addView(from, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(text("至"))
                addView(to, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        }
        val dialog = AlertDialog.Builder(this).setTitle("导出打印版 PDF")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("选择保存位置", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = from.text.toString().toIntOrNull()
                val last = to.text.toString().toIntOrNull()
                if (first == null || last == null || first !in 1..count || last !in first..count) {
                    to.error = "请输入 1–$count 内的小节范围。"
                    return@setOnClickListener
                }
                val selected = if (tracks.selectedItemPosition == 1) current.tracks.toList().indices.toList() else listOf(activeTrack)
                val options = ScorePrintExport.Options(papers[paper.selectedItemPosition], selected, first..last,
                    profiles[notation.selectedItemPosition])
                dialog.dismiss()
                requestExport(ExportKind.PDF, acknowledged = true, print = options)
            }
        }
        dialog.show()
    }

    private fun requestExport(format: ExportKind, acknowledged: Boolean = false, print: ScorePrintExport.Options? = null) {
        val current = score ?: return
        if (loading) return
        if (!acknowledged) {
            AlertDialog.Builder(this).setTitle("导出 ${format.label}")
                .setMessage(format.model?.let { ScoreExport.limitations(it).joinToString("\n\n") }
                    ?: "按谱面原始速度渲染完整乐曲，应用声部音色、混音与已启用的吉他效果。输出为 44.1 kHz、16 位立体声 WAV。")
                .setPositiveButton("选择保存位置") { _, _ -> requestExport(format, true) }
                .setNegativeButton("取消", null).show()
            return
        }
        pausePlayback()
        val tones = if (format == ExportKind.WAV) practice.snapshotTones() else emptyList()
        val amps = if (format == ExportKind.WAV) ampSetup.settingsFor(current, documentKey) else emptyMap()
        val cancel = AtomicBoolean(false)
        preparingExport = cancel
        loading = true; loadingIndicator.visibility = View.VISIBLE; setControlsReady(false)
        status.text = "正在准备 ${format.label}…"
        val name = current.title.ifBlank { fileLabel.substringBeforeLast('.') }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
        io.execute {
            var pending: File? = null
            try {
                val rendered = File.createTempFile("export-", ".${format.extension}", cacheDir).also { pending = it }
                when (format) {
                    ExportKind.PDF -> rendered.outputStream().use {
                        ScorePrintExport.write(this, current, checkNotNull(print), it, cancel::get)
                    }
                    ExportKind.WAV -> ScoreWaveExport.write(current, sheet.settings, tones, amps, rendered,
                        { assets.open("sonivox.sf2").use { it.readBytes() } }, cancel::get)
                    else -> rendered.outputStream().use { ScoreExport.writeTo(current, checkNotNull(format.model), it) }
                }
                runOnUiThread {
                    if (isDestroyed || cancel.get()) { rendered.delete(); return@runOnUiThread }
                    preparingExport = null
                    exportFile?.delete(); exportFile = rendered; exportFormat = format; exportScore = current
                    loading = false; loadingIndicator.visibility = View.GONE
                    setControlsReady(midiReady && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        // Android's audio/midi mapping can append .rtx to a .mid filename.
                        // A neutral document type preserves the explicitly chosen extension.
                        addCategory(Intent.CATEGORY_OPENABLE); type = format.mime
                        putExtra(Intent.EXTRA_TITLE, "$name.${format.extension}")
                    }, 11)
                }
            } catch (error: Throwable) {
                pending?.delete()
                if (error !is Exception && error !is alphaTab.AlphaTabError) throw error
                runOnUiThread {
                    if (isDestroyed || cancel.get()) return@runOnUiThread
                    preparingExport = null
                    loading = false; loadingIndicator.visibility = View.GONE
                    setControlsReady(midiReady && sheet.api.isReadyForPlayback && (!audioMode || embeddedReady))
                    showError("导出失败", error)
                }
            }
        }
    }

    private fun prepareEmbedded(file: File) {
        try {
            embedded.load(file) {
                embeddedReady = true
                bindEmbeddedOutput()
                embedded.masterVolume = sheet.api.masterVolume
                sheet.api.playbackSpeed = currentSpeed
                if (preparedMode) applyPreparedSync() else sheet.api.updateSyncPoints()
                restorePlaybackPosition()
                if (midiReady && !loading) setControlsReady(pendingReaderSession==null && restoreTick==null)
            }
        } catch (error: Exception) {
            embedded.release()
            switchSource(false)
            showError("无法准备录音播放", error)
        }
    }

    private fun pausePlayback() {
        val amp = liveAmp
        val recordingPosition = if (audioMode && sheet.api.playerState == PlayerState.Playing)
            amp?.currentPositionMs ?: embedded.currentPositionMs else null
        sheet.api.pause()
        // alphaTab pauses at the current beat's start. A recording can be in
        // the middle of a long rest, so restore its exact physical timestamp.
        recordingPosition?.let { if (amp == null) embedded.restorePausedPosition(it) else amp.restorePausedPosition(it) }
    }

    private fun togglePlayback() {
        if(pendingReaderSession!=null || restoreTick!=null || restoreRequestedTick!=null) return
        if (!sheet.api.isReadyForPlayback || (audioMode && !embeddedReady)) return
        if (playing) pausePlayback()
        else startPlayback()
    }

    private fun startPlayback() {
        updateLiveLoop()
        if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) sheet.api.play()
        else Toast.makeText(this, "当前无法获得音频播放权限，请稍后重试。", Toast.LENGTH_SHORT).show()
    }

    private fun chooseSource() {
        val loaded = score ?: return
        val options = buildList {
            if (hasAudio) add("内嵌原曲 / 伴奏")
            add("按谱合成 · GeneralUser GS")
            if (loaded.tracks.any(GuitarTone::supports)) add("按谱合成 · 吉他音箱")
            add("原厂采样 · 按谱播放")
        }
        val ampOption = if (loaded.tracks.any(GuitarTone::supports)) options.lastIndex - 1 else -1
        val selected = when {
            factoryMode -> options.lastIndex
            liveAmp != null -> ampOption
            hasAudio && !audioMode -> 1
            else -> 0
        }
        AlertDialog.Builder(this).setTitle("选择播放音源")
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    options.lastIndex -> chooseFactorySource()
                    ampOption -> if (liveAmp == null || factoryMode) prepareSynthAmp()
                    else -> switchSource(hasAudio && which == 0)
                }
            }.setNeutralButton("音色库") { _, _ -> bankLibrary.show() }.setNegativeButton("关闭", null).show()
    }

    internal fun switchSource(useEmbedded: Boolean) {
        if ((useEmbedded == audioMode && !preparedMode) || score == null) return
        if (factorySetupDelegate.isInitialized()) factorySetup.cancel()
        if (ampSetupDelegate.isInitialized()) ampSetup.cancel()
        val session=captureReaderSession()?.copy(source=if(useEmbedded && hasAudio) "embedded" else "synth")
        resumeAfterToneReload = null
        val externalWasReady = sheet.api.player?.output is IExternalMediaSynthOutput && sheet.api.isReadyForPlayback
        midiReady=false; pendingReaderSession=null; restoreRequestedTick=null
        pausePlayback(); embedded.pause()
        // Pausing synchronously emits old-player seek events. Only expose the
        // checkpoint afterwards, so they cannot consume the new restoration.
        pendingReaderSession=session
        audioMode = useEmbedded && hasAudio
        factoryMode = false
        embeddedReady=false
        trainer.stop()
        setControlsReady(false); midiReady = audioMode && externalWasReady
        sheet.settings.player.playerMode = if (audioMode) PlayerMode.EnabledExternalMedia else PlayerMode.EnabledSynthesizer
        sheet.api.updateSettings()
        liveAmp?.close(); liveAmp = null
        if (audioMode) embeddedFile?.let { prepareEmbedded(it) }
        else { embedded.release(); embeddedReady = false }
        sheet.api.render()
        source.text = if (audioMode) "内嵌原曲" else "合成音源"
        status.text = if (audioMode) "播放内嵌录音" else "按谱合成演奏"
        refreshPanels()
    }

    private fun chooseTrack() {
        val loaded = score ?: return
        val names = loaded.tracks.toList().mapIndexed { index, track -> trackChoiceLabel(track.name, index) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("显示哪个声部？")
            .setSingleChoiceItems(names, activeTrack) { dialog, index ->
                selectTrack(index)
                dialog.dismiss()
            }.setNegativeButton("关闭", null).show()
    }

    private fun trackChoiceLabel(name: String, index: Int): String =
        name.ifBlank { "声部 ${index + 1}" } + if (index in lyricTracks) " · 歌词" else ""

    private fun selectTrack(index: Int) {
        val loaded = score ?: return
        if (scoreEntry.isOpen) scoreEntry.resetSelection()
        activeTrack = index; sheet.tracks = listOf(loaded.tracks[index])
        track.text = loaded.tracks[index].name.ifBlank { "声部 ${index + 1}" }
        updateDocumentSubtitle()
        refreshPanels()
    }

    internal fun changeSpeed(value: Double, stopTrainer: Boolean = true) {
        if (!sheet.api.isReadyForPlayback) return
        if (stopTrainer) trainer.stop()
        val wasPlaying = playing
        currentSpeed = value; sheet.api.playbackSpeed = value
        if (!wasPlaying) { pausePlayback(); embedded.pause() }
        speed.text = "${currentSpeed}×"
        refreshPanels()
    }

    private fun chooseSpeed() {
        val values = doubleArrayOf(0.1, 0.25, 0.5, 0.6, 0.75, 0.85, 1.0)
        AlertDialog.Builder(this).setTitle("练习速度 · 保持音高")
            .setSingleChoiceItems(values.map { "${(it * 100).toInt()}%" }.toTypedArray(), values.indexOfFirst { it == currentSpeed }) { dialog, index ->
                changeSpeed(values[index]); dialog.dismiss()
            }.setNeutralButton("自定义") { _, _ -> showCustomSpeed() }
            .setNegativeButton("取消", null).show()
    }

    private fun showCustomSpeed() {
        val allowed = 10..100
        val input = numberField("自定义播放速度，百分比").apply {
            setText((currentSpeed * 100).roundToInt().coerceIn(allowed).toString())
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val content = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(-1, dp(48)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义播放速度")
            .setMessage("输入 ${allowed.first}% 至 ${allowed.last}%，100% 为原速。")
            .setView(content)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .create()
        fun applySpeed() {
            val percentage = input.text.toString().toIntOrNull()
            if (percentage == null || percentage !in allowed) {
                input.error = "请输入 ${allowed.first} 到 ${allowed.last} 之间的整数"
                return
            }
            if (!speed.isEnabled) {
                input.error = "请等待播放器准备完成"
                return
            }
            changeSpeed(percentage / 100.0)
            dialog.dismiss()
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { applySpeed() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            applySpeed()
            true
        }
    }

    private fun showTrainer() {
        if (audioMode && liveAmp == null) {
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
        if ((audioMode && liveAmp == null) || !sheet.api.isReadyForPlayback || !sheet.api.isLooping) return false
        val range = sheet.api.playbackRange ?: return false
        pausePlayback()
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
        val (start, end) = barLoopTicks(from, to) ?: return false
        loopFromBar=from;loopToBar=to
        sheet.api.playbackRange = PlaybackRange().apply { startTick = start; endTick = end }
        sheet.api.isLooping = true
        updateLiveLoop()
        sheet.api.tickPosition = start
        inlineFrom.setText(from.toString()); inlineTo.setText(to.toString())
        updateLoopStyle(); status.text = "循环第 $from–$to 小节"
        return true
    }

    private fun barLoopTicks(from: Int, to: Int): Pair<Double, Double>? {
        val loaded = score ?: return null
        if (from < 1 || from > to || to > loaded.masterBars.length.toInt()) return null
        val cache = sheet.api.tickCache ?: return null
        val start = cache.getMasterBarStart(loaded.masterBars[from - 1])
        val last = loaded.masterBars[to - 1]
        val end = cache.getMasterBarStart(last) + last.calculateDuration()
        return if (end > start) start to end else null
    }

    private fun clearLoopRange() {
        trainer.stop()
        loopFromBar=null;loopToBar=null
        sheet.api.isLooping = false; sheet.api.playbackRange = null; updateLoopStyle()
        updateLiveLoop()
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
        captureReaderSession()?.let { outState.putBundle("readerSession",it.toBundle()) }
        exportFile?.let { outState.putString("exportPath", it.absolutePath) }
        exportFormat?.let { outState.putString("exportFormat", it.name) }
        documentKey?.let { outState.putString("uri", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        readerMenu.dismiss()
        currentWidth = newConfig.screenWidthDp
        currentHeight = newConfig.screenHeightDp
        applyAdaptiveLayout(currentWidth)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.onKeyDown(keyCode, event)
        if (scoreEntry.handleKey(keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.repeatCount == 0) togglePlayback()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME && score != null) { sheet.api.stop(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::scoreEntry.isInitialized) scoreEntry.cancelMidiInput()
    }

    override fun onPause() {
        readerMenu.dismiss()
        if (::scoreEntry.isInitialized) scoreEntry.cancelMidiInput()
        if (resumeAfterToneReload != null) resumeAfterToneReload = false
        if (bankLibraryDelegate.isInitialized()) bankLibrary.stop()
        pausePlayback(); embedded.pause(); practice.close(); audioManager.abandonAudioFocusRequest(focus); super.onPause()
    }
    override fun onDestroy() {
        preparingExport?.set(true)
        recordingDialog?.dismiss()
        recordingDialog = null
        readerMenu.dismiss()
        if (ampSetupDelegate.isInitialized()) ampSetup.close()
        if (factorySetupDelegate.isInitialized()) factorySetup.close()
        if (bankLibraryDelegate.isInitialized()) bankLibrary.close()
        if (midiInputDelegate.isInitialized()) midiInput.close()
        batchCancellation?.set(true); batchDialog?.dismiss(); recentUi.close()
        generation++; io.shutdown(); unregisterReceiver(unplugged)
        embedded.release(); embeddedFile?.delete()
        liveAmp?.close()
        practice.close()
        super.onDestroy()
        // AlphaTabView releases audio workers and rendering resources on detach.
    }

    companion object {
        private var factoryCacheSwept = false
        private const val REQUEST_RECORDING = 14
        const val MAX_FILE = 128 * 1024 * 1024
        internal fun selectedImportUris(data: Intent): List<Uri> = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) } }
            data.data?.let { add(it) }
        }.distinctBy { it.toString() }
    }
}
