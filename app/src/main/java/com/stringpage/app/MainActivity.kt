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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
    private val pdfReviewPlayback get() = pdfPreview && intent.getBooleanExtra(com.stringpage.app.pdf.PdfConvertActivity.REVIEW_PLAYBACK_EXTRA, false)
    private val ink get() = AppUi.ink
    private val paper get() = AppUi.canvas
    private val accent get() = AppUi.accent
    private val muted get() = AppUi.muted
    private var theme = ReaderTheme.Clay
    internal data class ThemeDocument(
        val score: Score, val label: String, val document: String?, val modified: Boolean, val reader: ReaderSession,
        val editSession: BasicScoreEditor.Session, val editing: Boolean, val selection: ScoreEntryPanel.Selection?,
        val practice: PlaybackControls.Snapshot, val tones: List<GuitarTone>,
        val transientAmps: Map<Int, GuitarAmpSettings>, val trainer: ProgressivePractice.Session
    )
    internal data class ThemeRestart(val document: ThemeDocument?)
    internal class ThemeChangeModel : ViewModel() {
        var pending: ThemeRestart? = null
        fun consume(): ThemeRestart? = pending.also { pending = null }
    }
    private val themeChange by lazy { ViewModelProvider(this)[ThemeChangeModel::class.java] }
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
    private var recordingMetronomeMap: MetronomeTimeMap? = null
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
    private lateinit var draftIndicator: TextView
    private lateinit var source: Button
    private lateinit var status: TextView
    private lateinit var empty: LinearLayout
    private lateinit var play: Button
    private lateinit var track: Button
    private lateinit var loop: Button
    private lateinit var speed: Button
    private lateinit var zoom: Button
    private lateinit var progress: ReaderTimeline
    private lateinit var position: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var sidebar: ScrollView
    private lateinit var inspector: ScrollView
    private lateinit var readerBody: LinearLayout
    private lateinit var readerWorkspace: LinearLayout
    private lateinit var practiceTabs: LinearLayout
    private lateinit var practiceClose: Button
    private lateinit var metronomePanel: ReaderMetronomePanel
    private lateinit var metronomeButton: Button
    private lateinit var libraryToggle: Button
    private lateinit var practiceToggle: Button
    private lateinit var headerLogo: ImageView
    private lateinit var importHeader: Button
    private lateinit var statusInset: View
    private lateinit var restart: Button
    private lateinit var loopHint: LinearLayout
    private lateinit var loopHintText: TextView
    private lateinit var followButton: Button
    private lateinit var speedSlider: SeekBar
    private lateinit var speedInput: EditText
    private enum class PracticeTab(val label: String) { Speed("速度"), Loop("循环"), Metronome("节拍") }
    private val practiceSections = linkedMapOf<PracticeTab, LinearLayout>()
    private val practiceTabButtons = linkedMapOf<PracticeTab, Button>()
    private var selectedPracticeTab = PracticeTab.Speed
    private var practiceInline = false
    private var loopPicking = false
    private var loopPickStart: Int? = null
    private var followScore = true
    private var previewedVisit = Double.NaN
    private var showStatus = false
    private lateinit var scoreOverlay: ReaderScoreOverlay
    private lateinit var infoPanel: LinearLayout
    private lateinit var documentHeading: LinearLayout
    private lateinit var scoreTools: LinearLayout
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
    private var panningScore = false
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
    private lateinit var barCounter: TextView
    private var displayedBar = 0
    private lateinit var masterVolume: SeekBar
    private lateinit var fullScreen: Button
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
        { "内嵌录音是整首混音。切换到合成音源后可调整声部混音、预备拍和播放移调。" },
        { documentKey }, this::reloadTonePlayback, { liveAmp?.guitarTracks.orEmpty() }, this::chooseAmpSettings,
        { editor.trackSlot(it) }, { if (audioMode && !preparedMode) embedded.refreshMetronome() }, ::refreshMetronomeChrome) }
    private val editor by lazy { BasicScoreEditor(this, { score }, this::applyEditedScore, { sheet.settings }) }
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val readerPanels by lazy { getSharedPreferences("reader-panels", MODE_PRIVATE) }
    private var libraryPinned: Boolean
        get() = readerPanels.getBoolean("library", true)
        set(value) { readerPanels.edit().putBoolean("library", value).apply() }
    private var practicePinned: Boolean
        get() = readerPanels.getBoolean("practice", true)
        set(value) { readerPanels.edit().putBoolean("practice", value).apply() }
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
        theme = ReaderTheme.read(this)
        theme.applyTo(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val themeRestart = themeChange.consume()
        selectedPracticeTab = PracticeTab.entries.firstOrNull { it.name == readerPanels.getString("practice-tab", null) } ?: PracticeTab.Speed
        pendingReaderSession=themeRestart?.document?.reader ?: ReaderSession.fromBundle(savedInstanceState?.getBundle("readerSession"))
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
                when {
                    loopPicking -> cancelLoopPick()
                    practiceInline -> closePractice()
                    scoreEntry.isOpen -> setScoreEditing(false)
                    fullSheet -> { fullSheet = false; applyAdaptiveLayout(currentWidth) }
                    else -> guardDocumentChange { finish() }
                }
            }
        })
        if (themeRestart != null) {
            themeRestart.document?.let { retained ->
                loadScore(retained.document?.let(Uri::parse), retained = retained) { retained.label to retained.score }
            }
            return
        }
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

    private fun Int.withAlpha(alpha: Int) = (this and 0xffffff) or (alpha shl 24)

    private fun Int.notationColor() = alphaTab.model.Color(
        Color.red(this).toDouble(), Color.green(this).toDouble(), Color.blue(this).toDouble(), 255.0)
    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(value: String, dark: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 13f; isAllCaps = false; typeface = AppUi.medium
        setTextColor(if (dark) AppUi.onAccent else ink)
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
        statusInset = View(this).apply { setBackgroundColor(AppUi.rail) }
        root.addView(statusInset, LinearLayout.LayoutParams(-1, 0))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            if (statusInset.layoutParams.height != bars.top) {
                statusInset.layoutParams.height = bars.top
                statusInset.requestLayout()
            }
            insets
        }
        headerToolbar = row().apply { setBackgroundColor(AppUi.rail); setPadding(dp(22), dp(4), dp(18), dp(4)) }
        headerLogo = ImageView(this).apply {
            setImageDrawable(AppUi.icon(this@MainActivity, "strings", AppUi.railAccent, 22))
            background = AppUi.surface(this@MainActivity, AppUi.railSurface, 10, AppUi.railLine)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brand = text("弦页", 21f, AppUi.railInk).apply {
            id=R.id.app_brand;maxLines=1;ellipsize=TextUtils.TruncateAt.END
            typeface = AppUi.display; setOnClickListener { showAbout() }
        }
        title = text("未打开乐谱", 22f).apply {
            id = R.id.score_title; typeface = AppUi.display; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        fun headerAction(label: String, icon: String, action: () -> Unit) = button(label, action = action).apply {
            background = AppUi.ripple(this@MainActivity, Color.TRANSPARENT, 10)
            setTextColor(AppUi.railInk)
            AppUi.iconButton(this, icon, AppUi.railMuted)
        }
        recentHeader = headerAction("曲库", "library") { showRecents() }
        headerToolbar.addView(recentHeader)
        libraryToggle = headerAction("", "library") { libraryPinned = !libraryPinned; applyAdaptiveLayout(currentWidth) }.apply {
            id = R.id.reader_library_toggle
        }
        headerToolbar.addView(libraryToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        headerToolbar.addView(headerLogo, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        headerToolbar.addWeighted(brand, 1f, 48)
        headerToolbar.addWeighted(title, 1f, 48)
        draftIndicator = text("未导出", 11f, accent).apply {
            maxLines = 1
            setPadding(dp(5), 0, dp(5), 0)
            visibility = View.GONE
        }
        headerToolbar.addView(draftIndicator, LinearLayout.LayoutParams(-2, dp(48)))
        editScoreButton = headerAction("编辑", "edit") { setScoreEditing(!scoreEntry.isOpen) }.apply {
            visibility = View.GONE
        }
        headerToolbar.addView(editScoreButton)
        fullScreen = headerAction("全屏看谱", "full", ::toggleFullSheet).apply { id = R.id.full_screen }
        headerToolbar.addView(fullScreen)
        importHeader = button("导入", true) { pickFile() }.apply {
            id = R.id.import_file; AppUi.iconButton(this, "import", AppUi.rail)
            setTextColor(AppUi.rail)
            background = AppUi.ripple(this@MainActivity, AppUi.railAccent, 11)
            layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8); marginEnd = dp(6) }
        }
        headerToolbar.addView(importHeader)
        practiceToggle = headerAction("", "panel") { togglePracticeDock() }.apply { id = R.id.reader_practice_toggle }
        headerToolbar.addView(practiceToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        moreButton = headerAction("更多", "more") { showMore() }.apply { id = R.id.more_actions }
        headerToolbar.addView(moreButton)
        root.addView(headerToolbar, LinearLayout.LayoutParams(-1, dp(60)))
        val body = row().apply { gravity = Gravity.TOP; setBackgroundColor(paper) }.also { readerBody = it }
        val workspace = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }.also { readerWorkspace = it }
        sidebar = ScrollView(this).apply {
            id = R.id.library_panel; setBackgroundColor(AppUi.rail); isFillViewport = true; isVerticalScrollBarEnabled = false
        }
        val library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(14), dp(17), dp(20)) }
        library.addView(divider(AppUi.railLine), LinearLayout.LayoutParams(-1, dp(1)))
        library.addView(text("曲库", 18f, AppUi.railInk).apply { typeface = AppUi.medium }, LinearLayout.LayoutParams(-1, dp(50)))
        library.addView(button("导入 GP 文件") { pickFile() }.apply {
            setTextColor(AppUi.railInk)
            background = AppUi.ripple(this@MainActivity, AppUi.railSurface, 9)
            AppUi.iconButton(this, "import", AppUi.railAccent, 16)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        library.addView(recentUi.section)
        voicesPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voicesPanel.addView(divider(AppUi.railLine), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        voicesPanel.addView(sectionLabel("声部", AppUi.railMuted))
        voiceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voicesPanel.addView(voiceList)
        library.addView(voicesPanel)
        sidebar.addView(library)
        body.addView(sidebar, LinearLayout.LayoutParams(dp(214), -1))
        body.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))

        infoPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(paper); setPadding(dp(24), dp(16), dp(24), dp(10))
        }
        documentHeading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        subtitle = text("GP3 / GP4 / GP5 / GPX / GP", 12f, muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        }
        documentHeading.addView(subtitle)
        infoPanel.addView(documentHeading)
        scoreTools = row()
        source = button("音源") { chooseSource() }.apply { id = R.id.audio_source; AppUi.iconButton(this, "audio", accent) }
        track = button("声部") { chooseTrack() }.apply { id = R.id.track_select; AppUi.iconButton(this, "track", muted) }
        zoom = button("谱面") { chooseScoreDisplay() }.apply { id = R.id.notation_select; AppUi.iconButton(this, "score", muted) }
        scoreTools.addWeighted(track, 1.55f, 48); scoreTools.addWeighted(source, 1.15f, 48); scoreTools.addWeighted(zoom, 0.8f, 48)
        infoPanel.addView(scoreTools); workspace.addView(infoPanel)
        loopHint = row().apply {
            id = R.id.reader_loop_selection
            visibility = View.GONE
            setPadding(dp(12), 0, dp(4), 0)
            setBackgroundColor(AppUi.selected)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        loopHintText = text("点谱面选择 A 点", 14f)
        loopHint.addWeighted(loopHintText)
        loopHint.addView(button("取消") { cancelLoopPick() }, LinearLayout.LayoutParams(-2, dp(48)))
        workspace.addView(loopHint, LinearLayout.LayoutParams(-1, -2))

        scoreHost = FrameLayout(this)
        scoreFrame = FrameLayout(this).apply {
            background = AppUi.surface(this@MainActivity, AppUi.score, 10, AppUi.line); clipToOutline = true
        }
        sheet = AlphaTabView(this, null).apply {
            id = R.id.score_view
            setBackgroundColor(AppUi.score)
            barCursorFillColor = accent.withAlpha(8)
            beatCursorFillColor = accent.withAlpha(214)
            selectionFillColor = accent.withAlpha(52)
            if (theme != ReaderTheme.Clay) settings.display.resources.apply {
                mainGlyphColor = ink.notationColor()
                secondaryGlyphColor = muted.notationColor()
                scoreInfoColor = ink.notationColor()
                barNumberColor = accent.notationColor()
                staffLineColor = muted.notationColor()
                barSeparatorColor = muted.notationColor()
            }
            settings.display.scale = 0.85
            settings.display.barsPerRow = -1.0
            settings.notation.elements.set(alphaTab.NotationElement.ScoreTitle, false)
            settings.display.padding = alphaTab.collections.DoubleList().apply { push(20.0); push(24.0); push(20.0); push(24.0) }
            settings.player.playerMode = PlayerMode.EnabledSynthesizer
            settings.player.enableUserInteraction = true
            settings.player.scrollMode = alphaTab.ScrollMode.Continuous
            settings.player.scrollOffsetY = -18.0
            settings.player.scrollSpeed = 180.0
            settings.core.includeNoteBounds = true
            api.updateSettings()
        }
        scoreFrame.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        scoreOverlay = ReaderScoreOverlay(sheet)
        scoreFrame.addView(scoreOverlay, FrameLayout.LayoutParams(-1, -1))
        sheet.viewTreeObserver.addOnScrollChangedListener { scoreOverlay.invalidate() }
        sheet.api.postRenderFinished.on { scoreOverlay.postInvalidateOnAnimation() }
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
            exit = { setScoreEditing(false) }, selectionChanged = ::refreshEditOutline)
        workspace.addView(scoreEntry.view, LinearLayout.LayoutParams(-1, -2))

        inspector = ScrollView(this).apply { id = R.id.practice_panel; setBackgroundColor(AppUi.panel); isFillViewport = true; isVerticalScrollBarEnabled = false }
        val practice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(18)) }
        val inspectorHeading = row()
        practiceTabs = row().apply { id = R.id.reader_practice_tabs }
        for (section in PracticeTab.entries) {
            val tab = button(section.label) { openPractice(section) }.apply {
                setPadding(dp(2), 0, dp(2), 0)
            }
            practiceTabButtons[section] = tab
            practiceTabs.addWeighted(tab)
        }
        inspectorHeading.addWeighted(practiceTabs, 1f, 48)
        practiceClose = button("") { closePractice() }.apply {
            AppUi.iconButton(this, "close", muted)
            compoundDrawablePadding = 0
            contentDescription = "收起练习面板"
        }
        inspectorHeading.addView(practiceClose, LinearLayout.LayoutParams(dp(48), dp(48)))
        barCounter = text("小节 —", 11f, muted).apply {
            id = R.id.reader_bar_number
            typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
            background = AppUi.surface(this@MainActivity, AppUi.control, 7)
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }
        practice.addView(inspectorHeading)
        fun practiceSection(section: PracticeTab? = null): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            section?.let { practiceSections[it] = this }
            practice.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val tempoCard = practiceSection(PracticeTab.Speed)
        val tempoHeading = row()
        tempoHeading.addWeighted(text("播放速度", 13f).apply { typeface = AppUi.medium }, 1f, 48)
        speedInput = numberField("播放速度，10 到 100%")
        speedInput.setSelectAllOnFocus(true)
        speedInput.imeOptions = EditorInfo.IME_ACTION_DONE
        speedInput.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) applyReaderSpeed() }
        }
        tempoHeading.addView(speedInput, LinearLayout.LayoutParams(dp(54), dp(48)))
        speedReadout = button("应用") { applyReaderSpeed() }.apply {
            textSize = 12f
            contentDescription = "应用播放速度百分比"
        }
        tempoHeading.addView(speedReadout, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(4) })
        tempoCard.addView(tempoHeading)
        speedSlider = SeekBar(this).apply {
            max = 90
            contentDescription = "播放速度，10 到 100%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user) speedInput.setText((value + 10).toString())
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) { applyReaderSpeed() }
            })
        }
        tempoCard.addView(speedSlider, LinearLayout.LayoutParams(-1, dp(40)))
        val presets = row()
        for ((index, value) in listOf(0.5, 0.6, 0.75, 1.0).withIndex()) {
            val preset = button("${(value * 100).toInt()}%") {
                speedInput.clearFocus()
                WindowCompat.getInsetsController(window, speedInput).hide(WindowInsetsCompat.Type.ime())
                changeSpeed(value)
            }.apply {
                tag = value; setPadding(dp(2), 0, dp(2), 0); textSize = 12f
                if (value == 0.6) id = R.id.reader_speed_sixty
            }
            speedPresets.add(preset)
            presets.addView(preset, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(if (index == 0) 0 else 4) })
        }
        tempoCard.addView(presets, LinearLayout.LayoutParams(-1, dp(48)))
        tempoCard.addView(button("逐轮加速练习") { showTrainer() }.apply {
            background = AppUi.ripple(this@MainActivity, Color.TRANSPARENT); gravity = Gravity.CENTER_VERTICAL
            AppUi.iconButton(this, "tempo", accent, 16); setPadding(dp(4), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        val loopCard = practiceSection(PracticeTab.Loop)
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
        loopActions.addWeighted(applyLoop, 1.6f, 48); loopActions.addWeighted(clearLoop, 1f, 48)
        loopCard.addView(loopActions)
        loopCard.addView(button("在谱上选段") { beginLoopPick() }.apply {
            id = R.id.reader_loop_select_on_score
            AppUi.iconButton(this, "loop", accent, 16)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val metronomeCard = practiceSection(PracticeTab.Metronome)
        metronomePanel = ReaderMetronomePanel(this, this.practice)
        metronomeCard.addView(metronomePanel)
        val sound = practiceSection()
        sound.addView(button("音量与混音") { this@MainActivity.practice.show() }.apply {
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
        audioDetails = text("未打开乐谱", 11f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        sound.addView(button("音频详情") { showAudioDetails() }.apply {
            AppUi.iconButton(this, "info", muted, 16)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        inspector.addView(practice)
        body.addView(inspector, LinearLayout.LayoutParams(dp(236), -1))
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
        timeline.addView(barCounter, LinearLayout.LayoutParams(-2, -2))
        progress = ReaderTimeline(this).apply {
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
        restart = transportButton(button("") { sheet.api.stop() }, "restart").apply {
            contentDescription = "回到开头"; tooltipText = "回到开头"; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0); compoundDrawablePadding = 0
        }
        controls.addView(restart, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
        play = button("播放", true) { togglePlayback() }.apply {
            id = R.id.play_pause; background = AppUi.ripple(this@MainActivity, accent, 12)
            textSize = 14f; AppUi.iconButton(this, "play", AppUi.onAccent)
        }
        controls.addWeighted(play, 1.1f)
        speed = transportButton(button("速度 100%") { openPractice(PracticeTab.Speed) }).apply {
            id = R.id.speed_select; typeface = AppUi.medium; fontFeatureSettings = "'tnum'"
        }
        controls.addWeighted(speed, 1f)
        loop = transportButton(button("循环") { openPractice(PracticeTab.Loop) }, "loop").apply { id = R.id.loop_select }
        controls.addWeighted(loop, 1f)
        metronomeButton = transportButton(button("节拍器关") {
            this.practice.setMetronomeEnabled(!this.practice.metronomeUi().enabled)
        }).apply {
            id = R.id.reader_metronome_toggle
            setOnLongClickListener { openPractice(PracticeTab.Metronome); true }
        }
        controls.addWeighted(metronomeButton, 1.05f)
        followButton = transportButton(button("") { returnToPlayhead() }, "location").apply {
            id = R.id.reader_scroll_resume
            contentDescription = "回到播放位置"
            tooltipText = "回到播放位置"
            compoundDrawablePadding = 0
            visibility = View.GONE
        }
        timeline.addView(followButton, 0, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottomContent.addView(controls); transport.addView(bottomContent)
        root.addView(transport)
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !theme.dark
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = AppUi.rail
            window.navigationBarColor = AppUi.panel
        }
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
        val wide = currentWidth >= 840 && !compact
        sidebar.visibility = if (wide && libraryPinned && !fullSheet) View.VISIBLE else View.GONE
        libraryToggle.visibility = if (loaded && wide && !fullSheet) View.VISIBLE else View.GONE
        libraryToggle.contentDescription = if (libraryPinned) "收起曲库" else "展开曲库"
        libraryToggle.tooltipText = libraryToggle.contentDescription
        libraryToggle.isSelected = libraryPinned
        recentHeader.visibility = if (libraryToggle.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        importHeader.visibility = if (loaded) View.GONE else View.VISIBLE
        title.visibility = if (loaded) View.VISIBLE else View.GONE
        draftIndicator.visibility = if (loaded && modified) View.VISIBLE else View.GONE
        brand.visibility = if (loaded) View.GONE else View.VISIBLE
        headerLogo.visibility = if (loaded) View.GONE else View.VISIBLE
        val headerColor = if (loaded) AppUi.panel else AppUi.rail
        headerToolbar.setBackgroundColor(headerColor)
        val toolbarHeight = dp(if (loaded || compact) 52 else 60)
        if (headerToolbar.layoutParams.height != toolbarHeight) {
            headerToolbar.layoutParams.height = toolbarHeight
            headerToolbar.requestLayout()
        }
        statusInset.setBackgroundColor(headerColor)
        WindowCompat.getInsetsController(window, headerToolbar).isAppearanceLightStatusBars = loaded && !theme.dark
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) window.statusBarColor = headerColor
        updateHeaderActions(loaded)
        practiceToggle.visibility = if (loaded && !fullSheet) View.VISIBLE else View.GONE
        practiceToggle.contentDescription = if (inspector.visibility == View.VISIBLE) "收起练习面板" else "打开练习面板"
        infoPanel.visibility = if (loaded && !fullSheet && !(compact && (currentWidth < 600 || currentHeight < 300))) View.VISIBLE else View.GONE
        fullScreen.visibility = if (loaded && (fullSheet || currentWidth >= 600)) View.VISIBLE else View.GONE
        editScoreButton.visibility = if (loaded && !loading && !fullSheet && !pdfReviewPlayback) View.VISIBLE else View.GONE
        voicesPanel.visibility = if (loaded) View.VISIBLE else View.GONE
        empty.getChildAt(0).visibility = if (!loaded && loading) View.INVISIBLE else View.VISIBLE
        transport.visibility = if (loaded || loading || importing) View.VISIBLE else View.GONE
        status.visibility = if (loading || importing || showStatus) View.VISIBLE else View.GONE
        val gutter = if (fullSheet) 0 else if (wide) 16 else 6
        scoreHost.setPadding(dp(gutter), dp(if (fullSheet) 0 else 2), dp(gutter), dp(gutter))
        positionPracticePanel()
    }

    private fun updateHeaderActions(loaded: Boolean) {
        val glyphColor = if (loaded) ink else AppUi.railInk
        fun headerGlyph(view: Button, icon: String, label: String, showLabel: Boolean) {
            view.text = if (showLabel) label else ""
            view.contentDescription = label
            view.tooltipText = label
            view.minWidth = dp(48)
            view.minimumWidth = dp(48)
            view.minHeight = dp(48)
            view.minimumHeight = dp(48)
            view.setTextColor(glyphColor)
            AppUi.iconButton(view, icon, glyphColor, 19)
            view.compoundDrawablePadding = if (showLabel) dp(6) else 0
            view.setPadding(dp(if (showLabel) 8 else 14), 0, dp(if (showLabel) 8 else 14), 0)
        }
        val labeled = currentWidth >= 840 && resources.configuration.fontScale <= 1.2f
        headerGlyph(recentHeader, "library", "曲库", (currentWidth >= 390 && resources.configuration.fontScale <= 1.2f && !fullSheet) || labeled)
        headerGlyph(libraryToggle, if (libraryPinned) "back" else "library", if (libraryPinned) "收起曲库" else "展开曲库", false)
        headerGlyph(editScoreButton, if (scoreEntry.isOpen) "check" else "edit", if (scoreEntry.isOpen) "完成编辑" else "编辑", labeled)
        headerGlyph(fullScreen, if (fullSheet) "close" else "full", if (fullSheet) "退出全屏" else "全屏看谱", labeled)
        headerGlyph(moreButton, "more", "更多", !loaded && currentWidth >= 400 || labeled)
        headerGlyph(practiceToggle, "panel", "练习面板", false)
    }

    private fun canDockPractice() = currentWidth >= 1200 && currentHeight >= 460 && !fullSheet

    private fun positionPracticePanel() {
        val docked = canDockPractice() && (practicePinned || practiceInline)
        val shouldShow = score != null && !loopPicking && (docked || practiceInline)
        val parent = if (docked) readerBody else readerWorkspace
        if (inspector.parent !== parent) {
            (inspector.parent as? LinearLayout)?.removeView(inspector)
            parent.addView(inspector)
        }
        val width = if (docked) dp(236) else -1
        val height = if (docked) -1 else dp(((currentHeight - 70) * 0.36).roundToInt().coerceIn(76, 310))
        if (inspector.layoutParams.width != width || inspector.layoutParams.height != height) {
            inspector.layoutParams = LinearLayout.LayoutParams(width, height)
        }
        inspector.visibility = if (shouldShow) View.VISIBLE else View.GONE
        practiceToggle.isSelected = shouldShow
        practiceToggle.background = AppUi.ripple(this, if (shouldShow) AppUi.selected else Color.TRANSPARENT, 8,
            if (shouldShow) accent else null)
        practiceToggle.contentDescription = if (shouldShow) "收起练习面板" else "打开练习面板"
        practiceToggle.tooltipText = practiceToggle.contentDescription
        for ((section, view) in practiceSections) view.visibility = if (section == selectedPracticeTab) View.VISIBLE else View.GONE
        for ((section, button) in practiceTabButtons) {
            val active = section == selectedPracticeTab
            button.isSelected = active
            button.setTextColor(if (active) accent else muted)
            button.background = AppUi.ripple(this, if (active) AppUi.selected else Color.TRANSPARENT, 8, if (active) accent else null)
        }
    }

    private fun openPractice(section: PracticeTab) {
        if (score == null) return
        if (selectedPracticeTab != section) readerPanels.edit().putString("practice-tab", section.name).apply()
        selectedPracticeTab = section
        if (!canDockPractice() || !practicePinned) practiceInline = true
        positionPracticePanel()
        inspector.scrollTo(0, 0)
        refreshMetronomeChrome()
    }

    private fun closePractice() {
        if (canDockPractice() && practicePinned) practicePinned = false
        practiceInline = false
        positionPracticePanel()
    }

    private fun togglePracticeDock() {
        if (inspector.visibility == View.VISIBLE) {
            closePractice()
        } else if (canDockPractice()) {
            practicePinned = true
            practiceInline = false
            positionPracticePanel()
        } else openPractice(selectedPracticeTab)
    }

    private fun toggleFullSheet() {
        fullSheet = !fullSheet
        if (fullSheet) practiceInline = false
        applyAdaptiveLayout(currentWidth)
    }

    private fun applyAdaptiveLayout(width: Int) {
        if (!::sidebar.isInitialized) return
        val compact=currentHeight in 1..420
        val oneLineInfo=width>=840 || (compact && width>=600)
        val oneLineTransport=width>=780 || (compact && width>=580)
        updateReaderChrome()
        val wide = width >= 840
        headerToolbar.layoutParams.height=dp(if (compact || score != null) 52 else 60)
        headerToolbar.requestLayout()
        val showBrand=width>=360 && (width>=380 || resources.configuration.fontScale<=1.1f)
        brand.text=if(showBrand) "弦页" else ""
        brand.isClickable=showBrand
        brand.importantForAccessibility=if(showBrand) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        brand.layoutParams.height=dp(if(compact) 44 else 48);brand.requestLayout()
        headerToolbar.setPadding(dp(if (wide) 16 else 4), 0, dp(if (wide) 16 else 4), 0)
        infoPanel.orientation = LinearLayout.HORIZONTAL
        infoPanel.setPadding(dp(if (wide) 16 else 8), 0, dp(if (wide) 16 else 8), 0)
        documentHeading.visibility = if (oneLineInfo && !compact) View.VISIBLE else View.GONE
        documentHeading.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        scoreTools.layoutParams = LinearLayout.LayoutParams(if (oneLineInfo && !compact) dp(374) else -1, dp(48)).apply {
            if (oneLineInfo && !compact) marginStart = dp(10)
        }
        title.textSize = if (compact) 18f else if (wide) 26f else 21f
        if (wide) {
            AppUi.iconButton(source, "audio", accent, 16); AppUi.iconButton(track, "track", muted, 16); AppUi.iconButton(zoom, "zoom", muted, 16)
        } else {
            listOf(source, track, zoom).forEach { it.setCompoundDrawables(null, null, null, null) }
        }
        listOf(source,track,zoom).forEach { it.setPadding(dp(if(width<380) 8 else 12),0,dp(if(width<380) 8 else 12),0) }
        val gutter = if (fullSheet) 0 else if (wide) 16 else 6
        scoreFrame.background = AppUi.surface(this, AppUi.score, if (wide && !fullSheet) 10 else 0, if (gutter == 0) null else AppUi.line)
        scoreFrame.elevation = if (wide && !fullSheet) dp(1).toFloat() else 0f
        transport.setPadding(dp(if (wide) 22 else 12), dp(if(compact) 1 else 3), dp(if (wide) 22 else 12), dp(if(compact) 4 else 8))
        status.layoutParams.height=dp(if(compact) 19 else 23);status.requestLayout()
        bottomContent.orientation = if (oneLineTransport) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val first = if (oneLineTransport) controls else timeline
        if (bottomContent.getChildAt(0) !== first) { bottomContent.removeView(first); bottomContent.addView(first, 0) }
        timeline.layoutParams = if (oneLineTransport) LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(12) }
            else LinearLayout.LayoutParams(-1, dp(if (!followScore) 48 else if (compact) 28 else 32))
        position.visibility=if(compact && width<600) View.GONE else View.VISIBLE
        restart.visibility = if (width >= 780) View.VISIBLE else View.GONE
        controls.layoutParams = LinearLayout.LayoutParams(if (oneLineTransport) dp(if (wide) 444 else 356) else -1, dp(48)).apply {
            if(!oneLineTransport) topMargin=dp(2)
        }
        for (control in listOf(play, speed, loop, metronomeButton)) control.setPadding(dp(4), 0, dp(4), 0)
        updatePlayIcon()
        updateLoopStyle()
        refreshSpeedControls()
        refreshMetronomeChrome()
        updateZoomLabel()
        // AlphaTab observes its viewport size and reflows itself. Keep the
        // player intact while the window or keyboard changes its dimensions.
    }

    private fun updatePlayIcon() {
        val label = if (playing) "暂停" else "播放"
        val iconOnly = currentWidth < 360 || resources.configuration.fontScale > 1.3f
        play.text = if (iconOnly) "" else label
        play.contentDescription = label
        AppUi.iconButton(play, if (playing) "pause" else "play", AppUi.onAccent, 17)
        play.compoundDrawablePadding = if (iconOnly) 0 else dp(5)
    }

    private fun updateLoopStyle() {
        val active = sheet.api.isLooping && sheet.api.playbackRange != null
        val range = loopFromBar?.let { start -> loopToBar?.let { end -> start..end } }
        val summary = range?.let { "${it.first}–${it.last}" }
        val compact = currentWidth < 420 || resources.configuration.fontScale > 1.2f
        loop.text = when {
            active && summary != null -> if (compact && currentWidth >= 390 && resources.configuration.fontScale <= 1.2f) "循环 $summary" else summary
            active -> "循环中"
            else -> "循环"
        }
        loop.contentDescription = if (active) "循环已开启${summary?.let { "，第 $it 小节" }.orEmpty()}，调整循环" else "设置小节循环"
        loop.isSelected = active
        loop.setTextColor(if (active) accent else ink)
        loop.background = AppUi.ripple(this, if (active) AppUi.selected else AppUi.control, 9, if (active) accent else null)
        if (compact) loop.setCompoundDrawables(null, null, null, null)
        else AppUi.iconButton(loop, "loop", if (active) accent else ink, 16)
        scoreOverlay.updateLoop(if (active && !loopPicking) range else null, loopPickStart)
        val ticks = sheet.api.playbackRange?.takeIf { active }
        val clock = preparedScoreTime
        if (ticks != null && clock != null) {
            val zero = clock.scoreTimeAt(0.0)
            val total = clock.scoreTimeAt(sheet.api.endTick) - zero
            progress.showLoop((clock.scoreTimeAt(ticks.startTick) - zero) / total, (clock.scoreTimeAt(ticks.endTick) - zero) / total)
        } else progress.showLoop(0.0, 0.0)
    }

    private fun refreshMetronomeChrome() {
        if (!::metronomeButton.isInitialized) return
        val state = practice.metronomeUi()
        val compact = currentWidth < 420 || resources.configuration.fontScale > 1.2f
        metronomeButton.text = when {
            !state.enabled -> if (compact) "节拍关" else "节拍器关"
            compact -> "${state.bpm} BPM"
            else -> "节拍 ${state.bpm} BPM"
        }
        metronomeButton.contentDescription = if (state.enabled) "节拍器已开启，${state.bpm} BPM，点击关闭" else "节拍器已关闭，点击开启"
        metronomeButton.tooltipText = "长按打开节拍设置"
        metronomeButton.isSelected = state.enabled
        metronomeButton.setTextColor(if (state.enabled) accent else ink)
        metronomeButton.background = AppUi.ripple(this, if (state.enabled) AppUi.selected else AppUi.control, 9,
            if (state.enabled) accent else null)
        if (!compact) AppUi.iconButton(metronomeButton, "tempo", if (state.enabled) accent else muted, 15)
        else metronomeButton.setCompoundDrawables(null, null, null, null)
        metronomePanel.refresh()
    }

    private fun refreshSpeedControls() {
        val percentage = (currentSpeed * 100).roundToInt()
        speed.text = if (currentWidth >= 390 && resources.configuration.fontScale <= 1.2f) "速度 $percentage%" else "$percentage%"
        speed.contentDescription = "播放速度 $percentage%，点击调整"
        if (!speedInput.hasFocus()) speedInput.setText(percentage.toString())
        if (!speedSlider.isPressed) speedSlider.progress = (percentage - 10).coerceIn(0, 90)
        speedPresets.forEach {
            it.isSelected = it.tag == currentSpeed
            it.setTextColor(if (it.isSelected) accent else ink)
            it.background = AppUi.ripple(this, if (it.isSelected) AppUi.selected else AppUi.control, 8,
                if (it.isSelected) accent else null)
        }
    }

    private fun applyReaderSpeed() {
        val percentage = speedInput.text.toString().toIntOrNull()
        if (percentage == null || percentage !in 10..100) {
            speedInput.error = "请输入 10 到 100 之间的整数"
        } else if (speed.isEnabled) {
            speedInput.clearFocus()
            WindowCompat.getInsetsController(window, speedInput).hide(WindowInsetsCompat.Type.ime())
            changeSpeed(percentage / 100.0)
        }
    }

    private fun setReaderStatus(message: String, prominent: Boolean = false) {
        showStatus = prominent
        status.text = message
        status.visibility = if (loading || importing || prominent) View.VISIBLE else View.GONE
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
            hasAudio && audioMode -> "内嵌录音\n$syncCount 个同步点\n$fileLabel\n切换声部只改变谱面。"
            loaded != null -> "GeneralUser GS 1.44\n按谱合成演奏\n$fileLabel"
            else -> "未打开乐谱"
        }
        source.setTextColor(if (loaded != null) accent else ink)
        source.background = AppUi.ripple(this, if (loaded != null) AppUi.selected else AppUi.control, 10)
        refreshSpeedControls()
        refreshMetronomeChrome()
    }

    private fun chooseNotation() {
        val values = arrayOf(alphaTab.StaveProfile.ScoreTab, alphaTab.StaveProfile.Tab, alphaTab.StaveProfile.Score)
        val names = arrayOf("五线谱 + 六线谱", "六线谱", "五线谱")
        AlertDialog.Builder(this).setTitle("谱面显示").setItems(names) { _, index ->
            sheet.settings.display.staveProfile = values[index]
            sheet.api.updateSettings(); sheet.api.render()
        }.show()
    }

    private fun updateZoomLabel() {
        val percentage = (sheet.settings.display.scale * 100).roundToInt()
        zoom.text = "谱面"
        zoom.contentDescription = "谱面设置，缩放 $percentage%"
        zoom.tooltipText = zoom.contentDescription
    }

    private fun chooseScoreDisplay() {
        val percentage = (sheet.settings.display.scale * 100).roundToInt()
        val labels = arrayOf("谱表显示", "缩放 $percentage%", "回到播放位置", "回到开头",
            if (fullSheet) "退出全屏" else "全屏看谱", "乐谱信息")
        AlertDialog.Builder(this).setTitle("谱面").setItems(labels) { _, index ->
            when (index) {
                0 -> chooseNotation()
                1 -> chooseZoom()
                2 -> returnToPlayhead()
                3 -> { sheet.api.stop(); returnToPlayhead() }
                4 -> toggleFullSheet()
                5 -> showAudioDetails()
            }
        }.setNegativeButton("关闭", null).show()
    }

    private fun showAudioDetails() {
        if (score == null) return
        val details = listOf(title.text, subtitle.text, audioDetails.text, status.text).filter { it.isNotBlank() }
        AlertDialog.Builder(this).setTitle("乐谱与音频详情").setMessage(details.joinToString("\n\n"))
            .setPositiveButton("关闭", null).show()
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
        (listOf(play, speed, speedReadout, speedSlider, speedInput, loop, progress, applyLoop, clearLoop, metronomeButton) + speedPresets).forEach {
            it.isEnabled = ready; it.alpha = if (ready) 1f else 0.4f
        }
        listOf(source, track, zoom, practiceToggle).forEach { it.isEnabled = score != null && !loading }
        moreButton.isEnabled = !loading
    }

    private fun wirePlayer() {
        sheet.api.midiLoad.on { midi ->
            val time = PreparedScoreTime(midi)
            preparedScoreTime = time
            practice.applyPlaybackMidi(midi, time)
        }
        sheet.api.error.on { error -> runOnUiThread {
            if (!isDestroyed) {
                resumeAfterToneReload = null
                setControlsReady(false)
                setReaderStatus("播放或显示失败，可尝试切换音源或重新导入。", prominent = true)
                showError("无法处理这份谱子", error)
            }
        } }
        sheet.api.playerReady.on { runOnUiThread {
            if (!loading && score != null && sheet.api.score===score) {
                midiReady = true
                bindEmbeddedOutput()
                if (audioMode && !preparedMode && embeddedReady) applyRecordingSync()
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
            updatePlayIcon()
            if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); audioManager.abandonAudioFocusRequest(focus) }
        } }
        sheet.api.playerPositionChanged.on { e -> runOnUiThread {
            duration = e.endTime; currentTick = e.currentTick
            preparedScoreTime?.let { practice.updateMetronomeBpm(it.scoreTempoAt(e.currentTick) * currentSpeed) }
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
        val visit = bars[(low - 1).coerceAtLeast(0)]
        val number = visit.masterBar.index.toInt() + 1
        if (playing && followScore && !loopPicking && previewedVisit != visit.start && tick >= (visit.start + visit.end) / 2) {
            previewedVisit = visit.start
            scoreOverlay.revealNextSystem(number)
        }
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
        setReaderStatus("正在读取录音…")
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
                    if (editor.replaceRecording(clip)) setReaderStatus("已导入内嵌录音 · ${formatTime(clip.durationMs.toDouble())}")
                }
                    .onFailure { error ->
                        setReaderStatus("录音导入失败。", prominent = true)
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
            if (pending == null || !pending.isFile || format == null) { setReaderStatus("导出数据已失效，请重新导出。", prominent = true); return }
            io.execute {
                try {
                    contentResolver.openOutputStream(destination, "w")?.use { output -> pending.inputStream().use { it.copyTo(output) } }
                        ?: throw IllegalStateException("无法写入所选位置。")
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setReaderStatus("已导出 ${format.label}")
                        Toast.makeText(this, "已导出 ${format.label}", Toast.LENGTH_SHORT).show()
                        if (format == ExportKind.GUITAR_PRO && score === exportedScore) {
                            modified = false; updateDocumentSubtitle(); discardRecoveryDraft()
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
        setReaderStatus("正在批量导入 ${uris.size} 份乐谱…")
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val detail = text("逐份读取并校验 GP 文件", 13f, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = uris.size }
        form.addView(detail); form.addView(progress, LinearLayout.LayoutParams(-1, dp(32)))
        val dialog = AlertDialog.Builder(this).setTitle("批量导入 · 0 / ${uris.size}").setView(form)
            .setNegativeButton("取消导入") { _, _ -> cancelled.set(true); setReaderStatus("正在取消，已校验成功的乐谱会保留。") }
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
                    setReaderStatus("$heading · 成功 ${report.imported.size} 份，失败 ${report.failures.size} 份")
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
            .setNeutralButton("丢弃修改") { _, _ -> modified = false; discardRecoveryDraft(); action() }
            .setNegativeButton("取消", null).show()
    }

    private fun loadFile(read: () -> Pair<String, ByteArray>, uri: Uri?, recovered: Boolean = false, newDocument: Boolean = false) {
        loadScore(uri, recovered, newDocument) {
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
            label to parsed
        }
    }

    private fun loadScore(
        uri: Uri?, recovered: Boolean = false, newDocument: Boolean = false, retained: ThemeDocument? = null,
        read: () -> Pair<String, Score>
    ) {
        recordingDialog?.dismiss()
        recordingDialog = null
        if (scoreEntry.isOpen) setScoreEditing(false)
        resumeAfterToneReload = null
        if (ampSetupDelegate.isInitialized()) ampSetup.discardScore()
        if (factorySetupDelegate.isInitialized()) factorySetup.cancel()
        pausePlayback()
        val token = ++generation
        loading = true; setControlsReady(false)
        setReaderStatus("正在读取谱面、内嵌音频和同步点…")
        loadingIndicator.visibility = View.VISIBLE
        io.execute {
            try {
                val (label, parsed) = read()
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
                    val resume = retained?.reader?.copy(document = key ?: transientPlaybackKey)
                        ?: pendingReaderSession?.takeIf { it.document == key }
                    pendingReaderSession = resume
                    restoreRequestedTick=null
                    restoreBarPosition=null
                    score = parsed; fileLabel = label; documentKey=key
                    lyricTracks = lyrics
                    activeTrack=(resume?.track ?: 0).coerceIn(0,parsed.tracks.length.toInt()-1)
                    modified = retained?.modified ?: (recovered || newDocument)
                    restoreTick = null
                    trainer.stop()
                    if (retained == null) editor.reset(parsed) else editor.restoreSession(parsed, retained.editSession)
                    practice.reset(parsed)
                    if (retained != null) {
                        practice.restoreSession(retained.practice, retained.tones)
                        trainer.restore(retained.trainer)
                        if (retained.transientAmps.isNotEmpty()) ampSetup.restoreTransientSettings(retained.transientAmps)
                    }
                    if (!modified && !pdfPreview && retained == null) io.execute { drafts.delete() }
                    hasAudio = (parsed.backingTrack?.rawAudioFile?.length ?: 0.0) > 0.0
                    audioMode = hasAudio && (resume==null || resume.source=="embedded"); factoryMode = false
                    restoreAmpSource = resume?.source == "amp"
                    embedded.release(); embeddedReady = false; midiReady = false; preparedScoreTime = null
                    liveAmp?.close(); liveAmp = null
                    embeddedFile?.delete(); embeddedFile = audioFile
                    currentSpeed = resume?.speed ?: 1.0
                    resume?.let {
                        sheet.settings.display.scale=it.scale
                        sheet.settings.display.staveProfile=runCatching { alphaTab.StaveProfile.valueOf(it.notation) }.getOrDefault(alphaTab.StaveProfile.ScoreTab)
                    }
                    updateZoomLabel()
                    cancelLoopPick()
                    practiceInline = false
                    setReaderFollow(true)
                    loopFromBar=null;loopToBar=null
                    sheet.api.isLooping = false; sheet.api.playbackRange = null; updateLoopStyle()
                    duration = 0.0; progress.progress = 0; position.text = "00:00 / 00:00"
                    title.text = parsed.title.ifBlank { label.substringBeforeLast('.') }
                    title.tooltipText = title.text
                    subtitle.text = listOf(parsed.artist, "${parsed.masterBars.length.toInt()} 小节", "${parsed.tracks.length.toInt()} 个声部")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    updateDocumentSubtitle()
                    source.text = if (audioMode) "内嵌原曲" else "合成音源"
                    track.text = parsed.tracks[activeTrack].name.ifBlank { "声部 ${activeTrack+1}" }
                    val syncCount = parsed.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 }
                    setReaderStatus(when {
                        newDocument -> "点按休止符即可输入音符。"
                        resume?.source=="prepared" -> "已恢复练习设置；采样音频需重新准备。"
                        audioMode && syncCount > 0 -> "已读取内嵌音频及 $syncCount 个同步点 · 点击谱面可跳转"
                        audioMode -> "已读取内嵌音频；缺少同步点，跟谱位置可能有偏差。"
                        hasAudio -> "按谱合成演奏 · 可切换到内嵌录音"
                        else -> "这份谱子没有可读取的内嵌音频，当前按谱合成播放。"
                    }, prominent = resume?.source == "prepared")
                    sheet.settings.player.playerMode = if (audioMode) PlayerMode.EnabledExternalMedia else PlayerMode.EnabledSynthesizer
                    sheet.api.updateSettings()
                    bindEmbeddedOutput()
                    if (audioMode && audioFile != null) prepareEmbedded(audioFile)
                    empty.visibility = View.GONE
                    inlineFrom.setText("1"); inlineTo.setText(parsed.masterBars.length.toInt().toString())
                    displayedBar = 1
                    barCounter.text = "小节 1 / ${parsed.masterBars.length.toInt()}"
                    sheet.tracks = listOf(parsed.tracks[activeTrack])
                    if (retained == null) {
                        if (uri != null) { saveRecent(uri, title.text.toString()); prefs.edit().putString("lastUri", uri.toString()).apply() }
                        else if (!pdfPreview) prefs.edit().remove("lastUri").apply()
                    }
                    refreshPanels()
                    if (newDocument) {
                        saveDraft()
                        setScoreEditing(true)
                    }
                    if (retained?.editing == true) {
                        setScoreEditing(true, keepSource = true)
                        scoreEntry.restoreSelection(retained.selection)
                    }
                }
            } catch (e: Throwable) {
                if (e !is Exception && e !is alphaTab.AlphaTabError) throw e
                runOnUiThread {
                    if (token != generation || isDestroyed) return@runOnUiThread
                    loading = false; loadingIndicator.visibility = View.GONE
                    setControlsReady(score != null && sheet.api.isReadyForPlayback)
                    setReaderStatus("导入失败。请选择完整的 GP 文件。", prominent = true)
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
        audio.setMetronome(practice.mediaMetronome(MetronomeTimeMap.offset(audio.syncOffsetMs)), practice::mediaMetronomeVolume)
        updateLiveLoop()
    }

    private fun applyRecordingSync() {
        sheet.api.updateSyncPoints()
        val map = AlphaTabMetronomeBridge.recordingMap(sheet.api.player, embedded.backingTrackDuration)
        recordingMetronomeMap = map
        embedded.setMetronome(practice.mediaMetronome(map), cacheDir, practice::mediaMetronomeVolume)
        updateLiveLoop()
    }

    private fun updateLiveLoop() {
        val range = sheet.api.playbackRange?.takeIf { sheet.api.isLooping }
        val timing = preparedScoreTime ?: return
        val audio = liveAmp
        if (audio == null) {
            if (audioMode) embedded.metronomeEndMs = range?.let { recordingMetronomeMap?.mediaAt(timing.scoreTimeAt(it.endTick)) }
            return
        }
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
        setReaderStatus(if (factory != null) {
            "已准备 ${factory.noteCount} 个演奏音符" +
                if (factory.substitutedArticulations > 0) " · ${factory.substitutedArticulations} 个音符使用替代技法采样" else ""
        } else "已准备 ${stems.guitarTracks.size} 个吉他声部")
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

    internal fun changeTheme(next: ReaderTheme) {
        if (theme == next || loading || preparingExport != null || themeChange.pending != null) return
        val retained = score?.let { current ->
            val reader = checkNotNull(captureReaderSession(allowTransient = true))
            ThemeDocument(current, fileLabel, documentKey, modified, reader, editor.session(), scoreEntry.isOpen,
                scoreEntry.saveSelection(), practice.snapshot(), practice.snapshotTones(),
                if (ampSetupDelegate.isInitialized()) ampSetup.snapshotTransientSettings() else emptyMap(),
                trainer.session())
        }
        themeChange.pending = ThemeRestart(retained)
        next.save(this)
        recreate()
    }

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
                    add(ReaderMenu.Action("练习面板", "panel") { openPractice(selectedPracticeTab) })
                    add(ReaderMenu.Action("音量与混音", "mix") { practice.show() })
                }
                add(ReaderMenu.Section("播放与练习", listOf(ReaderMenu.Action("逐轮加速练习", "tempo") { showTrainer() }), shortcuts))
                add(ReaderMenu.Section("谱面", listOf(
                    ReaderMenu.Action(if (scoreEntry.isOpen) "完成谱面编辑" else "在谱面编辑", "edit", enabled = !pdfReviewPlayback) { setScoreEditing(!scoreEntry.isOpen) },
                    ReaderMenu.Action("MIDI 输入", "audio", if (midiInputDelegate.isInitialized()) midiInput.portLabel.orEmpty() else "") { chooseMidiInput() },
                    ReaderMenu.Action("选择声部", "track", track.text.toString()) { chooseTrack() },
                    ReaderMenu.Action("谱面设置", "score") { chooseScoreDisplay() },
                    ReaderMenu.Action(if (fullSheet) "退出全屏" else "全屏看谱", "full") {
                        toggleFullSheet()
                    }
                )))
            }
            val other = buildList {
                if (available && !pdfPreview) add(ReaderMenu.Action("导入 GP 文件", "import") { pickFile() })
                add(ReaderMenu.Action("界面主题", "theme", theme.label, enabled = preparingExport == null) {
                    showReaderThemes(this@MainActivity, theme, ::changeTheme)
                })
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
                    if (!pdfReviewPlayback) add(ReaderMenu.Action(if (pdfPreview) "导出" else "编辑与导出", "file", children = fileActions))
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
        setReaderStatus(if (timing != null && hasAudio) "$description · 请试听改动附近的录音同步。" else description,
            prominent = timing != null && hasAudio)
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

    private fun setScoreEditing(enabled: Boolean, keepSource: Boolean = false) {
        if (enabled && (loading || score == null || pdfReviewPlayback)) return
        if (enabled) {
            cancelLoopPick()
            practiceInline = false
            pausePlayback()
            scoreEntry.open()
            if (audioMode && !keepSource) switchSource(false)
        } else scoreEntry.close()
        editScoreButton.text = if (enabled) "完成" else "编辑"
        editScoreButton.isSelected = enabled
        editScoreButton.contentDescription = if (enabled) "完成谱面编辑" else "编辑谱面"
        sheet.settings.player.enableUserInteraction = !enabled
        sheet.api.updateSettings()
        updateReaderChrome()
        refreshEditOutline()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var targetedTap = false
        if (::scoreEntry.isInitialized && score != null) {
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
                MotionEvent.ACTION_MOVE -> scoreTap?.let {
                    val dx = kotlin.math.abs(x - it.x)
                    val dy = kotlin.math.abs(y - it.y)
                    if (dy > slop && dy > dx && !scoreEntry.isOpen && !loopPicking) {
                        panningScore = true
                        sheet.settings.player.enableUserInteraction = false
                        if (playing && followScore) setReaderFollow(false)
                    }
                    if (dx > slop || dy > slop) scoreTap = null
                }
                MotionEvent.ACTION_UP -> {
                    val start = scoreTap
                    scoreTap = null
                    if (start != null && kotlin.math.abs(x - start.x) <= slop && kotlin.math.abs(y - start.y) <= slop) {
                        targetedTap = true
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> scoreTap = null
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (panningScore && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            panningScore = false
            sheet.settings.player.enableUserInteraction = !scoreEntry.isOpen && !loopPicking
        }
        if (targetedTap && scoreEntry.isOpen) selectScorePosition(event)
        else if (targetedTap && loopPicking) {
            val origin = IntArray(2).also(scoreOverlay::getLocationOnScreen)
            scoreOverlay.measureAt(event.rawX - origin[0], event.rawY - origin[1])?.let(::pickLoopBar)
        }
        return handled
    }

    private fun refreshEditOutline() {
        if (!::scoreEntry.isInitialized) return
        val selection = scoreEntry.resolvedSelection()?.takeIf { scoreEntry.isOpen }
        scoreOverlay.updateEditSelection(selection?.first, selection?.second)
    }

    private fun setReaderFollow(enabled: Boolean) {
        val changed = followScore != enabled
        followScore = enabled
        previewedVisit = Double.NaN
        sheet.settings.player.scrollMode = if (enabled) alphaTab.ScrollMode.Continuous else alphaTab.ScrollMode.Off
        followButton.visibility = if (enabled) View.GONE else View.VISIBLE
        if (changed && bottomContent.orientation == LinearLayout.VERTICAL) {
            timeline.layoutParams.height = dp(if (enabled && currentHeight in 1..420) 28 else if (enabled) 32 else 48)
            timeline.requestLayout()
        }
    }

    private fun returnToPlayhead() {
        setReaderFollow(true)
        sheet.api.scrollToCursor()
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
        draftIndicator.visibility = if (modified) View.VISIBLE else View.GONE
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
        if (pdfPreview) return
        val current = score ?: return
        io.execute {
            try { drafts.write(ScoreExport.guitarProBytes(current)) }
            catch (error: Exception) {
                android.util.Log.w("StringPage", "Failed to write score recovery copy", error)
                runOnUiThread { if (score === current && !isDestroyed) setReaderStatus("草稿写入失败，请导出副本保存修改。", prominent = true) }
            }
        }
    }

    private fun discardRecoveryDraft() {
        if (!pdfPreview) io.execute { drafts.delete() }
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
        if (pdfReviewPlayback) return
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
        setReaderStatus("正在准备 ${format.label}…")
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
                if (preparedMode) applyPreparedSync() else applyRecordingSync()
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
        setReaderStatus(if (audioMode) "播放内嵌录音" else "按谱合成演奏")
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
        preparedScoreTime?.let { practice.updateMetronomeBpm(it.scoreTempoAt(currentTick) * value) }
        if (!wasPlaying) { pausePlayback(); embedded.pause() }
        refreshSpeedControls()
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
            .setPositiveButton("应用", null).setNeutralButton("停止加速") { _, _ -> trainer.stop(); setReaderStatus("逐轮加速已停止") }
            .setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val input = fields.map { it.text.toString().toIntOrNull() ?: throw IllegalArgumentException("请输入整数。") }
                    val settings = ProgressivePractice.Configuration(input[0], input[1], input[2], input[3])
                    if (!startTrainer(settings)) throw IllegalArgumentException("请等待合成音源准备完成，并选择小节循环。")
                    setReaderStatus("逐轮加速 ${settings.start}% → ${settings.target}% · 每个速度 ${settings.repeats} 轮")
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
                updateZoomLabel()
            }.show()
    }

    private fun beginLoopPick() {
        if (score == null || !play.isEnabled) return
        if (scoreEntry.isOpen) {
            Toast.makeText(this, "完成编辑后可在谱面选段。", Toast.LENGTH_SHORT).show()
            return
        }
        loopPicking = true
        loopPickStart = null
        practiceInline = false
        loopHint.visibility = View.VISIBLE
        loopHintText.text = "点谱面选择 A 点"
        sheet.settings.player.enableUserInteraction = false
        positionPracticePanel()
        updateLoopStyle()
    }

    private fun pickLoopBar(bar: Int) {
        val start = loopPickStart
        if (start == null) {
            loopPickStart = bar
            loopHintText.text = "A · 第 $bar 小节，点谱面选择 B 点"
            updateLoopStyle()
        } else if (applyLoopRange(minOf(start, bar), maxOf(start, bar))) {
            cancelLoopPick()
        }
    }

    private fun cancelLoopPick() {
        if (!loopPicking) return
        loopPicking = false
        loopPickStart = null
        loopHint.visibility = View.GONE
        sheet.settings.player.enableUserInteraction = !scoreEntry.isOpen
        positionPracticePanel()
        updateLoopStyle()
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
        updateLoopStyle(); setReaderStatus("循环第 $from–$to 小节")
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
        setReaderStatus("循环已关闭")
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
        practice.destroy()
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
