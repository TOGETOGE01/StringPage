package com.stringpage.app.pdf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.stringpage.app.AppUi
import com.stringpage.app.ReaderTheme
import com.stringpage.app.GpDocumentReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class PdfConvertActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var source: Uri? = null
    private var document: PdfConversionDocument? = null
    private var sourceFile: File? = null
    private var sourceName = ""
    private var page = 1
    private var pageData: PageData? = null
    private var wholeScore: List<PdfTabTrack>? = null
    private var wholeBpm: Int? = null
    private var skippedPages = emptyList<Int>()
    private var selectedTracks: Set<Int>? = null
    private val tuningOverrides = HashMap<Int, List<Int>>()
    private val corrections = HashMap<String, String>()
    private val confirmedCorrections = HashSet<String>()
    private val reviewDrafts by lazy { PdfReviewDrafts(this) }
    private var reviewId: String? = null
    private var meterOverride: PdfMeter? = null
    private var selected: IntRange? = null
    private var focused = -1
    private var tempo: Int? = null
    private var pendingSave: File? = null
    private var saveLocation: SaveLocation? = null
    private var busy = false
    private var message = "选择电子吉他谱或清晰的六线谱扫描 PDF。"
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var picker: Button

    private data class PageData(val bitmap: Bitmap?, val result: PdfTabResult?, val error: String?)
    private data class SaveLocation(val uri: Uri?)

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri, 1)
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        saveLocation = SaveLocation(uri)
        finishPendingSave()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReaderTheme.read(this).applyTo(this)
        super.onCreate(savedInstanceState)
        selectedTracks = savedInstanceState?.getIntArray("tracks")?.toSet()
        val restoredMeter = savedInstanceState?.getIntArray("scannedMeter")
        if (restoredMeter?.size == 2) meterOverride = PdfMeter(restoredMeter[0], restoredMeter[1])
        savedInstanceState?.getBundle("corrections")?.let { bundle ->
            for (key in bundle.keySet()) bundle.getString(key)?.let { corrections[key] = it }
        }
        savedInstanceState?.getStringArrayList("confirmedCorrections")?.let(confirmedCorrections::addAll)
        savedInstanceState?.getBundle("tunings")?.let { bundle ->
            for (key in bundle.keySet()) key.toIntOrNull()?.let { index ->
                bundle.getIntArray(key)?.let { tuningOverrides[index] = it.toList() }
            }
        }
        pendingSave = privatePreviewFile(cacheDir, savedInstanceState?.getString("pendingSave"))
        if (savedInstanceState?.getBoolean("saveLocationReturned") == true) {
            saveLocation = SaveLocation(savedInstanceState.getString("saveLocation")?.let(Uri::parse))
        }
        buildLayout()
        val uri = savedInstanceState?.getString("source")?.let(Uri::parse) ?: intent.data
        if (uri != null) {
            val from = savedInstanceState?.getInt("start", -1) ?: -1
            val to = savedInstanceState?.getInt("end", -1) ?: -1
            open(uri, savedInstanceState?.getInt("page", 1) ?: 1, savedInstanceState?.let { from..to }, savedInstanceState?.getInt("tempo", -1),
                savedInstanceState?.getBoolean("wholeScore") == true)
        }
        finishPendingSave()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("source", source?.toString())
        outState.putInt("page", page)
        outState.putInt("start", selected?.first ?: -1)
        outState.putInt("end", selected?.last ?: -1)
        outState.putInt("tempo", tempo ?: -1)
        outState.putIntArray("tracks", selectedTracks?.toIntArray())
        outState.putIntArray("scannedMeter", meterOverride?.let { intArrayOf(it.beats, it.unit) })
        outState.putBoolean("wholeScore", wholeScore != null)
        outState.putString("pendingSave", pendingSave?.name)
        outState.putBoolean("saveLocationReturned", saveLocation != null)
        outState.putString("saveLocation", saveLocation?.uri?.toString())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildLayout()
    }

    override fun onDestroy() {
        io.shutdownNow()
        sourceFile?.delete()
        if (isFinishing) pendingSave?.delete()
        super.onDestroy()
    }

    private fun buildLayout() {
        val root = column().apply { setBackgroundColor(AppUi.canvas) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val toolbar = row().apply { setPadding(dp(12), dp(4), dp(12), dp(4)); setBackgroundColor(AppUi.panel) }
        toolbar.addView(button("返回") { finish() })
        toolbar.addView(label("PDF 转 GP", 20f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        picker = button("选择 PDF", true) { openDocument.launch(arrayOf("application/pdf")) }
        toolbar.addView(picker)
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = label("", 14f, AppUi.muted).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)))
        content = column()
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderPage()
    }

    private fun open(uri: Uri, requestedPage: Int, savedRange: IntRange? = null, savedTempo: Int? = null, restoreWhole: Boolean = false) {
        if (busy || uri.scheme != "content") return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Access may be temporary for files shared by another app. */ }
        runTask("正在读取 PDF…", {
            val label = GpDocumentReader(this).name(uri).substringBeforeLast('.').trim().take(90).ifEmpty { "六线谱" }
            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val size = stream.read(buffer)
                    if (size < 0) break
                    pdfRequire(output.size() <= VectorPdf.MAX_FILE_BYTES - size, "PDF 超过 16 MB，请先拆分文件。")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            } ?: throw PdfConversionException("无法读取所选文件。")
            val file = File.createTempFile("pdf-source-", ".pdf", cacheDir)
            try {
                file.writeBytes(bytes)
                val model = PdfConversionDocument(file, bytes, assets)
                val id = PdfReviewDrafts.id(bytes)
                val draft = reviewDrafts.load(id)
                val index = ((if (savedRange == null) draft?.page else null) ?: requestedPage).coerceIn(1, model.pageCount)
                OpenPdf(label, model, file, index, scan(model, index), id, draft)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }) { opened ->
            if (savedRange == null || opened.draft != null) {
                selectedTracks = null; tuningOverrides.clear(); meterOverride = null; corrections.clear()
                confirmedCorrections.clear()
                opened.draft?.let { draft ->
                    selectedTracks = draft.tracks
                    tuningOverrides.putAll(draft.tunings)
                    meterOverride = draft.meter
                    corrections.putAll(draft.corrections)
                    confirmedCorrections.addAll(draft.confirmed)
                }
            }
            reviewId = opened.id
            source = uri
            sourceFile?.delete()
            sourceFile = opened.file
            sourceName = opened.label
            document = opened.model
            wholeScore = null
            wholeBpm = null
            skippedPages = emptyList()
            page = opened.page
            tempo = opened.draft?.bpm ?: savedTempo?.takeIf { it in 20..400 }
            val resumeWhole = if (savedRange != null) restoreWhole else opened.draft?.whole == true
            val resumeRange = savedRange ?: opened.draft?.range
            showPage(opened.data, resumeRange.takeUnless { resumeWhole }, persist = !resumeWhole)
            if (resumeWhole) scanWhole(resumeRange)
        }
    }

    private data class OpenPdf(val label: String, val model: PdfConversionDocument, val file: File, val page: Int,
                               val data: PageData, val id: String, val draft: PdfReviewDraft?)

    private fun persistReview() {
        val id = reviewId ?: return
        reviewDrafts.save(id, PdfReviewDraft(page, wholeScore != null, selected, tempo, meterOverride, selectedTracks,
            tuningOverrides, corrections, confirmedCorrections))
    }

    private fun scan(model: PdfConversionDocument, index: Int): PageData {
        val preview = runCatching { model.preview(index) }.getOrNull()
        return try { PageData(preview, model.read(index), null) }
        catch (error: PdfConversionException) { PageData(preview, null, error.message) }
    }

    private fun turnPage(change: Int, onShown: (() -> Unit)? = null) {
        val model = document ?: return
        val index = page + change
        if (index !in 1..model.pageCount || busy) return
        runTask("正在识别第 $index 页…", { scan(model, index) }) { result ->
            page = index
            showPage(result, selected.takeIf { wholeScore != null })
            onShown?.invoke()
        }
    }

    private data class WholePdf(val bpm: Int?, val tracks: List<PdfTabTrack>, val skipped: List<Int>)

    private fun scanWhole(savedRange: IntRange? = null) {
        val model = document ?: return
        runTask("正在识别全部 ${model.pageCount} 页…", {
            val skipped = ArrayList<Int>()
            var first: PdfTabResult? = null
            var all = emptyList<ArrayList<PdfTabBar>>()
            var firstError: String? = null
            for (index in 1..model.pageCount) {
                try {
                    val result = model.read(index)
                    if (first == null) {
                        first = result
                        all = result.tracks.map { ArrayList() }
                    } else {
                        val compatible = result.tracks.size == first.tracks.size && result.tracks.zip(first.tracks).all { (track, base) ->
                            track.strings == base.strings && (track.tuning == null || base.tuning == null || track.tuning == base.tuning)
                        }
                        pdfRequire(compatible, "声部或调弦与前面不同，请单独转换这一页。")
                    }
                    for ((track, staff) in result.tracks.withIndex()) all[track].addAll(staff.bars)
                } catch (error: PdfConversionException) {
                    skipped.add(index)
                    if (firstError == null) firstError = "第 $index 页。${error.message.orEmpty()}"
                }
                pdfRequire(all.sumOf { it.size } <= 8_000, "声部小节总数超过 8,000，请分段转换。")
            }
            val start = first ?: throw PdfConversionException(firstError ?: "没有找到可转换的谱页。")
            WholePdf(start.bpm, start.tracks.zip(all) { track, bars -> track.copy(bars = bars) }, skipped)
        }) { result ->
            wholeBpm = result.bpm
            wholeScore = result.tracks
            skippedPages = result.skipped
            pageData?.let { showPage(it, savedRange) }
        }
    }

    private fun showPage(data: PageData, savedRange: IntRange? = null, persist: Boolean = true) {
        pageData = data
        focused = -1
        val tracks = chosenTracks()
        val bars = scopeBars(tracks)
        selected = savedRange?.takeIf { !it.isEmpty() && it.first >= 0 && it.last in bars.indices }
            ?: bars.indices.takeIf { bars.isNotEmpty() && bars.all { it.issues.isEmpty() } && !pdfRangeHasPageGap(bars, it) }
        val blocked = bars.count { it.issues.isNotEmpty() }
        val review = bars.count { it.review.isNotEmpty() && it.issues.isEmpty() }
        val scope = when {
            data.result?.scanned == true && wholeScore != null -> "扫描识别 · 整份 PDF"
            data.result?.scanned == true -> "扫描识别 · 当前页"
            wholeScore != null -> "整份 PDF"
            else -> "当前页"
        }
        val summary = "$scope · ${bars.size} 小节 · ${tracks.size} 声部 · $blocked 小节需校对 · $review 小节有技巧提示"
        message = when {
            wholeScore != null && skippedPages.isNotEmpty() -> "$summary · 已跳过第 ${skippedPages.joinToString("、", limit = 6)} 页"
            else -> data.error ?: summary
        }
        renderPage()
        if (persist) persistReview()
    }

    private fun availableTracks() = wholeScore ?: pageData?.result?.tracks.orEmpty()

    private fun chosenTrackEntries(): List<Pair<Int, PdfTabTrack>> {
        val available = availableTracks()
        val selectedIndices = selectedTracks?.filter { it in available.indices }?.takeIf { it.isNotEmpty() }
        return available.mapIndexedNotNull { index, track ->
            if (selectedIndices?.contains(index) == false) null else index to track.copy(
                tuning = tuningOverrides[index]?.takeIf { it.size == track.strings } ?: track.tuning,
                bars = track.bars.map { bar -> correctedBar(index, track.strings, bar) })
        }
    }

    private fun chosenTracks() = chosenTrackEntries().map { it.second }

    private fun correctedBar(index: Int, strings: Int, original: PdfTabBar): PdfTabBar {
        val meter = meterOverride.takeIf { original.provisionalMeter || original.scanned }
        val bar = if (meter != null && meter != original.meter) {
            val issues = original.issues.filterNot { isPdfMeasureLengthIssue(it, original.meter) }
            original.copy(meter = meter, issues = if (pdfBeatsFit(original.beats, meter)) issues else issues + "识别出的节奏未填满 $meter 小节")
        } else original
        val key = "$index/${bar.number}"
        val edited = corrections[key] ?: return bar
        return try { PdfTabCorrection.review(bar, strings, edited, key in confirmedCorrections) }
        catch (error: PdfConversionException) { bar.copy(issues = bar.issues + error.message.orEmpty()) }
    }

    private fun scopeBars(tracks: List<PdfTabTrack>): List<PdfTabBar> {
        val first = tracks.firstOrNull()?.bars.orEmpty()
        if (tracks.size < 2) return first
        return first.mapIndexed { i, bar ->
            bar.copy(issues = tracks.flatMap { it.bars[i].issues }.distinct(), review = tracks.flatMap { it.bars[i].review }.distinct())
        }
    }

    private fun startTempo(bars: List<PdfTabBar>, from: Int = selected?.first ?: 0): Int? = tempo ?: bars.take(from + 1)
        .asReversed().firstNotNullOfOrNull { it.bpm } ?: if (wholeScore != null) wholeBpm else pageData?.result?.bpm

    private fun renderPage() {
        content.removeAllViews()
        updateStatus()
        val model = document ?: return
        val data = pageData ?: return
        val nav = row().apply { setPadding(dp(12), 0, dp(12), dp(4)) }
        nav.addView(button("上一页") { turnPage(-1) }.apply { isEnabled = !busy && page > 1 })
        nav.addView(label("第 $page / ${model.pageCount} 页", 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(button("下一页") { turnPage(1) }.apply { isEnabled = !busy && page < model.pageCount })
        if (model.pageCount > 1) nav.addView(button(if (wholeScore == null) "整份转换" else "仅当前页") {
            if (wholeScore == null) scanWhole() else { wholeScore = null; showPage(data) }
        }.apply { isEnabled = !busy })
        content.addView(nav)
        val panels = row().apply { setPadding(dp(12), 0, dp(12), dp(8)) }
        val wide = resources.configuration.screenWidthDp >= 720
        if (!wide) panels.orientation = LinearLayout.VERTICAL
        content.addView(panels, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val controls = column().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val list = ScrollView(this).apply { addView(controls) }
        val available = availableTracks()
        val entries = chosenTrackEntries()
        val tracks = entries.map { it.second }
        val bars = scopeBars(tracks)
        val marks = entries.flatMap { (index, track) ->
            track.bars.filter { it.page == page }.map { EditableBar(index, track, it) }
        }
        val bitmap = data.bitmap
        var preview: PagePreview? = null
        if (bitmap != null) {
            preview = PagePreview(bitmap, marks).apply { contentDescription = "第 $page 页 PDF 原谱，点按小节校对" }
            val pane = ScrollView(this).apply { isFillViewport = wide; addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            panels.addView(pane, if (wide) LinearLayout.LayoutParams(0, MATCH_PARENT, 1.2f)
                else LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            preview.host = pane
        }
        panels.addView(list, if (wide) LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        controls.addView(label(sourceName, 18f, bold = true))
        val instruments = row()
        if (available.size > 1) instruments.addView(button("声部 ${tracks.size} / ${available.size}") { chooseTracks(available) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val scanned = data.result?.scanned == true
        val needsMeter = needsMeterConfirmation(bars)
        val bpm = startTempo(bars)
        val exportProblems = exportProblems(data, tracks, bars, bpm, needsMeter)
        val output = exportControls(exportProblems)
        if (bars.isEmpty()) {
            if (instruments.childCount > 0) controls.addView(instruments)
            addExportControls(output, controls, wide)
            return
        }
        val needsTuning = tracks.any { it.tuning == null }
        if (!busy && (needsTuning || needsMeter || bpm == null)) {
            controls.addView(suggestSettings(data, entries, bars, bpm, needsMeter))
        }
        instruments.addView(button(if (needsTuning) "设置调弦" else "调弦") { chooseTuning(available) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(instruments)
        val configuration = row()
        configuration.addView(button(bpm?.let { "$it BPM" } ?: "设置速度") { editTempo(bpm) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        configuration.addView(button("选择小节范围") { editRange(bars) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.5f))
        controls.addView(configuration)
        val verification = row()
        if (scanned || bars.any { it.provisionalMeter }) verification.addView(button(if (needsMeter) "确认拍号" else "拍号设置") {
            editMeter(meterOverride ?: (bars.firstOrNull { it.provisionalMeter } ?: bars.first()).meter)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        verification.addView(button("修正小节") { chooseBarToCorrect(marks) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(verification)
        if (scanned) controls.addView(label("请对照原谱检查扫描识别的指法、节奏和技巧。", 13f, AppUi.muted))
        val range = selected?.takeIf { validRange(it, bars) }
        val blockedAt = (range ?: bars.indices).firstOrNull { bars[it].issues.isNotEmpty() }
        if (!busy && blockedAt != null) {
            val entry = entries.firstNotNullOfOrNull { (index, track) ->
                track.bars[blockedAt].takeIf { it.issues.isNotEmpty() }?.let { EditableBar(index, track, it) }
            }
            if (entry != null) controls.addView(button("校对${barLabel(entry.bar, blockedAt)}") { openReview(entry) })
        }
        if (!busy && (blockedAt != null || range == null || pdfRangeHasPageGap(bars, range))) {
            pdfLongestReadyRange(bars) { index -> tracks.any { it.bars[index].beats.any { beat -> beat.notes.isNotEmpty() } } }?.let { ready ->
                controls.addView(button("选用可导出段 · ${rangeLabel(bars, ready)}") {
                    showPage(data, ready)
                })
            }
        }
        controls.addView(label(if (range == null) "请选择连续可导出的小节。"
            else "已选 ${rangeLabel(bars, range)}",
            13f, AppUi.muted))
        addExportControls(output, controls, wide)
        if (marks.all { it.bar.issues.isEmpty() && it.bar.review.isEmpty() }) return
        controls.addView(label("校对小节", 15f, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        for ((index, entry) in marks.withIndex()) {
            val (_, track, bar) = entry
            if (bar.issues.isEmpty() && bar.review.isEmpty()) continue
            val details = (bar.issues + bar.review).joinToString(" · ")
            val context = if (tracks.size > 1) "${track.name} · ${barLabel(bar, index)}" else barLabel(bar, index)
            controls.addView(button("$context · ${if (bar.issues.isEmpty()) "技巧提示" else "待校对"}") {
                focused = index
                preview?.focusBar(index)
                AlertDialog.Builder(this).setTitle(context).setMessage(details)
                    .setNegativeButton("修正") { _, _ -> editBar(entry) }
                    .setPositiveButton("关闭", null).show()
            })
        }
    }

    private fun validRange(range: IntRange, bars: List<PdfTabBar>) =
        !range.isEmpty() && range.first in bars.indices && range.last in bars.indices

    private fun rangeLabel(bars: List<PdfTabBar>, range: IntRange): String {
        val start = barLabel(bars[range.first], range.first)
        return if (range.first == range.last) start else "$start 至 ${barLabel(bars[range.last], range.last)}"
    }

    private fun needsMeterConfirmation(bars: List<PdfTabBar>): Boolean {
        val range = selected?.takeIf { validRange(it, bars) } ?: bars.indices
        return meterOverride == null && range.any { bars[it].provisionalMeter }
    }

    private fun suggestSettings(data: PageData, entries: List<Pair<Int, PdfTabTrack>>, bars: List<PdfTabBar>,
                                bpm: Int?, needsMeter: Boolean) = column().apply {
        val untuned = entries.filter { it.second.tuning == null }
        val suggestedMeter = bars.firstOrNull { it.provisionalMeter }?.meter?.takeIf { needsMeter }
        val suggestions = listOfNotNull("100 BPM（试听参考）".takeIf { bpm == null },
            "标准调弦".takeIf { untuned.isNotEmpty() }, suggestedMeter?.let { "暂按 $it" })
        addView(label(suggestions.joinToString(" · "), 13f, AppUi.muted))
        addView(button("应用建议设置") {
            if (bpm == null) tempo = 100
            for ((index, track) in untuned) tuningOverrides[index] = PdfTabTuning.standard(track.strings)
            if (suggestedMeter != null) meterOverride = suggestedMeter
            showPage(data, selected)
        })
    }

    private fun openReview(entry: EditableBar) {
        if (entry.bar.page == page) editBar(entry)
        else turnPage(entry.bar.page - page) { editBar(entry) }
    }

    private fun exportProblems(data: PageData, tracks: List<PdfTabTrack>, bars: List<PdfTabBar>,
                               bpm: Int?, needsMeter: Boolean): List<String> {
        if (bars.isEmpty()) return listOf(data.error ?: if (availableTracks().isEmpty()) {
            "当前页没有识别到可导出的小节。请换一页，或选择清晰的六线谱 PDF。"
        } else "请选择包含可导出小节的声部。")
        return buildList {
            if (bpm == null) add("缺少速度。请点击「设置速度」输入原谱的 BPM。")
            val untuned = tracks.filter { it.tuning == null }
            if (untuned.isNotEmpty()) {
                add("${untuned.joinToString("、", limit = 3) { it.name }}缺少调弦。请点击「设置调弦」确认各弦音高。")
            }
            if (needsMeter) add("扫描谱的拍号尚未确认。请点击「确认拍号」核对原谱。")
            val range = selected?.takeIf { validRange(it, bars) }
            val toReview = (range ?: bars.indices).firstOrNull { bars[it].issues.isNotEmpty() }
            if (toReview != null) {
                val bar = bars[toReview]
                val location = if (bar.page == page) "" else "切到第 ${bar.page} 页，"
                val issue = bar.issues.first().trimEnd('。', '！', '？')
                add("${barLabel(bar, toReview)}待校对。$issue。请${location}点击「修正小节」，或选择不含待校对小节的范围。")
            } else if (range == null) add("还未选择导出范围。请点击「选择小节范围」。")
            if (range != null && pdfRangeHasPageGap(bars, range)) {
                add("范围中间有跳过的谱页。请选择一段连续的谱页导出。")
            }
        }
    }

    private fun exportControls(problems: List<String>) = column().apply {
        val waiting = busy || pendingSave != null
        if (!waiting && problems.isNotEmpty()) addView(label("无法保存 GP · ${problems.first()}", 13f, AppUi.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        val actions = row()
        actions.addView(button("试听") { encode(true) }.apply { isEnabled = !waiting && problems.isEmpty() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(button("保存 GP", problems.isEmpty()) { encode(false) }.apply { isEnabled = !waiting },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(actions)
    }

    private fun addExportControls(output: LinearLayout, controls: LinearLayout, wide: Boolean) {
        if (wide) controls.addView(output) else {
            output.setPadding(dp(12), dp(4), dp(12), dp(8))
            output.setBackgroundColor(AppUi.canvas)
            content.addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private data class EditableBar(val index: Int, val track: PdfTabTrack, val bar: PdfTabBar)

    private fun chooseBarToCorrect(marks: List<EditableBar>) {
        if (busy || marks.isEmpty()) return
        val labels = marks.map { "${it.track.name} · 第 ${it.bar.number} 小节" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择当前页的小节").setItems(labels) { _, index -> editBar(marks[index]) }.show()
    }

    private fun editBar(entry: EditableBar) {
        if (busy) return
        val (index, track, bar) = entry
        val key = "$index/${bar.number}"
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTypeface(Typeface.MONOSPACE)
            minLines = 4; maxLines = 11
            setText(corrections[key] ?: PdfTabCorrection.format(bar.beats, track.strings))
        }
        val feedback = label("", 13f, AppUi.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val advanced = column().apply { visibility = View.GONE }
        var suggestedRests = emptyList<PdfTabBeat>()
        val timingOnly = bar.issues.isNotEmpty() && bar.issues.all { isPdfMeasureLengthIssue(it, bar.meter) }
        val mustConfirm = bar.issues.any { !isPdfMeasureLengthIssue(it, bar.meter) } || bar.review.isNotEmpty() || key in confirmedCorrections
        val confirmed = CheckBox(this).apply {
            text = "已对照原谱确认指法与其他标记"
            isChecked = key in confirmedCorrections
            visibility = if (mustConfirm) View.VISIBLE else View.GONE
        }
        val rest = button("补尾部休止符") {
            field.append("\n" + PdfTabCorrection.format(suggestedRests, track.strings))
        }
        val duration = button("点选时值") { editBeatDuration(field) }
        val audition = button("试听已识别音符") {
            val beats = try { PdfTabCorrection.parseBeats(bar, track.strings, field.text.toString()) }
            catch (error: PdfConversionException) { feedback.text = error.message; return@button }
            previewReviewBar(track, bar, beats)
        }
        fun updateBeatTools() {
            val beats = try { PdfTabCorrection.parseBeats(bar, track.strings, field.text.toString()) }
            catch (_: PdfConversionException) { emptyList() }
            val complete = pdfBeatsFit(beats, bar.meter)
            suggestedRests = if (timingOnly) pdfTrailingRests(beats, bar.meter) else emptyList()
            feedback.text = when {
                beats.isEmpty() -> "请在高级文本编辑中检查指法或录入缺失的音符。"
                complete -> "时值已填满 ${bar.meter} 小节。"
                pdfBeatLength(beats) > bar.meter.length -> "时值超过 ${bar.meter} 小节。"
                suggestedRests.isNotEmpty() -> "时值尚未填满 ${bar.meter} 小节。原谱末尾有休止时，可直接补齐。"
                else -> "时值尚未填满 ${bar.meter} 小节。"
            }
            rest.visibility = if (suggestedRests.isEmpty()) View.GONE else View.VISIBLE
            duration.isEnabled = beats.isNotEmpty()
            audition.isEnabled = beats.any { it.notes.isNotEmpty() }
        }
        field.doAfterTextChanged { updateBeatTools() }
        val form = column().apply {
            setPadding(dp(22), 0, dp(22), 0)
            sourceMeasure(bar)?.let { fragment ->
                addView(ImageView(this@PdfConvertActivity).apply {
                    contentDescription = "第 ${bar.number} 小节原谱"
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(fragment)
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(MATCH_PARENT, dp(112)))
            }
            if (bar.issues.isNotEmpty() || bar.review.isNotEmpty()) {
                addView(label((bar.issues + bar.review).joinToString("\n"), 13f, AppUi.muted))
            }
            addView(feedback)
            val quick = row()
            quick.addView(duration, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            quick.addView(rest, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(quick)
            addView(audition)
            addView(confirmed)
            addView(button("高级文本编辑") { advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
            advanced.addView(label("每行填写时值与弦号-品位。和弦 4 1-3 2-0，休止符 8 R。\n8. 6-3m 表示附点八分音符加闷音。推弦 ^1 表示一个半音，^0>2>0 表示推两个半音后释放。指法末尾加 ~ 表示与上一个同弦同品音延续。时值加 t 表示三连音，加 g 表示拍前装饰音。", 13f))
            advanced.addView(label("泛音加 @类型品位。NH 自然、AH 人工、PH 拨片、TH 点弦、SH 半泛音、FB 反馈。6-5@AH12 表示按第 5 品，触弦位置再高 12 品。@0 清除泛音。", 13f))
            advanced.addView(label("快速拨弦在时值后加 r8、r16 或 r32。4r16 表示四分音符内按十六分音符反复拨弦。", 13f))
            advanced.addView(label("颤音杆在时值后加 w 和音高偏移，单位为全音。4w0>-0.5>0 表示降半个全音再回原音。", 13f))
            advanced.addView(field)
            addView(advanced)
        }
        updateBeatTools()
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("第 ${bar.number} 小节 · ${bar.meter}").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("应用", null)
            .setNeutralButton(if (corrections.containsKey(key)) "恢复识别" else null) { _, _ ->
                corrections.remove(key); confirmedCorrections.remove(key); pageData?.let { showPage(it, selected) }
            }.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString()
            try {
                val checked = PdfTabCorrection.review(bar, track.strings, value, confirmed.isChecked)
                corrections[key] = PdfTabCorrection.format(checked.beats, track.strings)
                if (confirmed.isChecked) confirmedCorrections.add(key) else confirmedCorrections.remove(key)
                dialog.dismiss(); pageData?.let { showPage(it, selected) }
            } catch (error: PdfConversionException) { feedback.text = error.message }
        }
    }

    private fun editBeatDuration(field: EditText) {
        val lines = field.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toMutableList()
        val prefix = Regex("^\\d{1,2}")
        val values = listOf(1, 2, 4, 8, 16, 32, 64)
        val names = arrayOf("全音符", "二分音符", "四分音符", "八分音符", "十六分音符", "三十二分音符", "六十四分音符")
        val groups = lines.mapIndexed { i, line ->
            val before = prefix.find(line)?.value?.toIntOrNull()
            val name = names.getOrNull(before?.let(values::indexOf) ?: -1) ?: "时值待确认"
            "第 ${i + 1} 组 · $name · ${line.substringAfter(' ').take(24)}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择音符组或休止符").setItems(groups) { _, beat ->
            AlertDialog.Builder(this).setTitle("第 ${beat + 1} 组选时值").setItems(names) { _, choice ->
                lines[beat] = lines[beat].replaceFirst(prefix, values[choice].toString())
                field.setText(lines.joinToString("\n"))
            }.show()
        }.show()
    }

    private fun sourceMeasure(bar: PdfTabBar): Bitmap? {
        val data = pageData ?: return null
        if (bar.page != page) return null
        val bitmap = data.bitmap ?: return null
        val pageWidth = data.result?.pageWidth ?: return null
        val scale = bitmap.width / pageWidth
        val box = bar.bounds
        val left = (box.left * scale).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box.top * scale).toInt().coerceIn(0, bitmap.height - 1)
        val right = (box.right * scale + 1).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom * scale + 1).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun editMeter(current: PdfMeter) {
        if (busy) return
        val beats = numberField("每小节拍数", current.beats.toString())
        val unit = numberField("以几分音符为一拍", current.unit.toString())
        val form = column().apply { setPadding(dp(24), 0, dp(24), 0); addView(beats); addView(unit) }
        val dialog = AlertDialog.Builder(this).setTitle("拍号设置").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("确认", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val count = beats.text.toString().toIntOrNull()
            val base = unit.text.toString().toIntOrNull()
            if (count == null || count !in 1..32) beats.error = "请输入 1 到 32"
            else if (base == null || base !in setOf(1, 2, 4, 8, 16, 32)) unit.error = "可输入 1、2、4、8、16 或 32"
            else { meterOverride = PdfMeter(count, base); dialog.dismiss(); pageData?.let { showPage(it, selected) } }
        }
    }

    private fun chooseTracks(tracks: List<PdfTabTrack>) {
        if (busy) return
        val chosen = selectedTracks?.toMutableSet() ?: tracks.indices.toMutableSet()
        val values = tracks.map { "${it.name} · ${it.strings} 弦" }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle("导出的声部")
            .setMultiChoiceItems(values, BooleanArray(values.size) { it in chosen }) { _, index, checked ->
                if (checked) chosen.add(index) else chosen.remove(index)
            }.setNegativeButton("取消", null).setPositiveButton("确定", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (chosen.isEmpty()) {
                android.widget.Toast.makeText(this, "请选择至少一个声部。", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                selectedTracks = chosen.toSet()
                dialog.dismiss()
                pageData?.let { showPage(it, selected) }
            }
        }
    }

    private fun chooseTuning(tracks: List<PdfTabTrack>) {
        if (busy) return
        if (tracks.size == 1) { editTuning(0, tracks.first()); return }
        val options = tracks.mapIndexed { index, track ->
            val tuning = (tuningOverrides[index] ?: track.tuning)?.joinToString(" ", transform = PdfTabTuning::name)
            "${track.name} · ${tuning ?: "待设置"}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("各声部调弦").setItems(options) { _, i -> editTuning(i, tracks[i]) }.show()
    }

    private fun editTuning(index: Int, track: PdfTabTrack) {
        val pitches = tuningOverrides[index] ?: track.tuning ?: PdfTabTuning.standard(track.strings)
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(pitches.joinToString(" ", transform = PdfTabTuning::name))
        }
        val form = column().apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(label("从第 1 弦到第 ${track.strings} 弦，输入带八度的音名，以空格分隔。", 14f))
            addView(field)
        }
        val dialog = AlertDialog.Builder(this).setTitle("${track.name} · 调弦").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val words = field.text.toString().trim().split(Regex("\\s+"))
            val parsed = words.mapNotNull(PdfTabTuning::parseNamedPitch)
            if (words.size != track.strings || parsed.size != track.strings) {
                field.error = "请填写 ${track.strings} 个音名，如 E4 或 F#3。"
            } else { tuningOverrides[index] = parsed; dialog.dismiss(); renderPage(); persistReview() }
        }
    }

    private fun editTempo(bpm: Int?) {
        if (busy) return
        val field = numberField("BPM", bpm?.toString().orEmpty())
        val form = column().apply { setPadding(dp(24), 0, dp(24), 0); addView(field) }
        val dialog = AlertDialog.Builder(this).setTitle("播放速度（BPM）").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed = field.text.toString().toIntOrNull()
            if (parsed == null || parsed !in 20..400) field.error = "请输入 20 到 400"
            else { tempo = parsed; dialog.dismiss(); renderPage(); persistReview() }
        }
    }

    private fun editRange(bars: List<PdfTabBar>) {
        if (busy) return
        val offset = bars.first().number.takeIf { start -> start > 0 && bars.withIndex().all { it.value.number == start + it.index } } ?: 1
        val from = numberField("起始小节", ((selected?.first ?: 0) + offset).toString())
        val to = numberField("结束小节", ((selected?.last ?: bars.lastIndex) + offset).toString())
        val form = column().apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(label("可选 $offset–${offset + bars.lastIndex}。有待校对标记的小节需排除。", 14f))
            addView(from); addView(to)
        }
        val dialog = AlertDialog.Builder(this).setTitle("选择小节范围").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val first = from.text.toString().toIntOrNull()?.minus(offset)
            val last = to.text.toString().toIntOrNull()?.minus(offset)
            if (first == null || last == null || first !in bars.indices || last !in first..bars.lastIndex) {
                to.error = "请输入有效的起止小节"
            } else if ((first..last).any { bars[it].issues.isNotEmpty() }) {
                to.error = "所选范围包含待校对小节"
            } else if (pdfRangeHasPageGap(bars, first..last)) {
                to.error = "所选范围跨过无法识别的页，请分段导出。"
            } else { selected = first..last; dialog.dismiss(); renderPage(); persistReview() }
        }
    }

    private fun encode(play: Boolean) {
        if (busy || pendingSave != null) return
        val data = pageData ?: return
        val tracks = chosenTracks()
        val bars = scopeBars(tracks)
        val bpm = startTempo(bars)
        val needsMeter = needsMeterConfirmation(bars)
        val problems = exportProblems(data, tracks, bars, bpm, needsMeter)
        if (problems.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle(if (play) "现在无法试听" else "现在无法保存 GP")
                .setMessage(problems.joinToString("\n\n"))
                .setPositiveButton("知道了", null).show()
            return
        }
        val range = checkNotNull(selected)
        val speed = checkNotNull(bpm)
        val extract = tracks.map { track -> track.copy(bars = track.bars.slice(range)) }
        val name = if (wholeScore != null) sourceName else "$sourceName · 第 $page 页"
        runTask("正在生成 GP…", { buildPreviewFile(name, speed, extract) },
            failure = "无法生成 GP 文件。请检查储存空间，或选择较短的小节范围后重试。") { file ->
            if (play) {
                message = "已生成 ${range.count()} 小节 · ${tracks.size} 声部 · $speed BPM"
                updateStatus()
                startActivity(Intent(this, PdfPreviewActivity::class.java).putExtra(PREVIEW_EXTRA, file.name))
            } else {
                pendingSave = file
                message = "请选择 GP 文件的保存位置。"
                try {
                    saveDocument.launch("$name.gp")
                } catch (_: ActivityNotFoundException) {
                    pendingSave = null
                    file.delete()
                    message = "无法打开保存窗口。请检查系统的「文件」应用是否已启用，再重试。"
                }
                renderPage()
            }
        }
    }

    private fun previewReviewBar(track: PdfTabTrack, bar: PdfTabBar, beats: List<PdfTabBeat>) {
        val bars = scopeBars(chosenTracks())
        val from = bars.indexOfFirst { it.number == bar.number }.coerceAtLeast(0)
        val speed = startTempo(bars, from) ?: 100
        val sampleBar = bar.copy(beats = beats + pdfTrailingRests(beats, bar.meter), issues = emptyList())
        val sample = track.copy(bars = listOf(sampleBar), tuning = track.tuning ?: PdfTabTuning.standard(track.strings))
        runTask("正在准备第 ${bar.number} 小节试听…", {
            buildPreviewFile("校对试听 · 第 ${bar.number} 小节", speed, listOf(sample))
        }, failure = "这小节的已识别音符暂时无法试听。") { file ->
            message = "已准备第 ${bar.number} 小节试听 · 仅播放已识别的音符"
            updateStatus()
            startActivity(Intent(this, PdfPreviewActivity::class.java).putExtra(PREVIEW_EXTRA, file.name)
                .putExtra(REVIEW_PLAYBACK_EXTRA, true))
        }
    }

    private fun buildPreviewFile(name: String, bpm: Int, tracks: List<PdfTabTrack>): File {
        val bytes = PdfTabGp.encodeTracks(name, bpm, tracks)
        val directory = File(cacheDir, PREVIEW_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        val output = File.createTempFile("tab-", ".gp", directory)
        try { output.writeBytes(bytes) } catch (error: Exception) { output.delete(); throw error }
        return output
    }

    private fun finishPendingSave() {
        // A picker result can arrive while the PDF is reopening after activity recreation.
        if (busy) return
        val location = saveLocation ?: return
        saveLocation = null
        val file = pendingSave
        pendingSave = null
        val uri = location.uri
        if (uri == null || file?.isFile != true) {
            message = if (uri == null) "已取消保存，未写入 GP 文件。" else "待保存的 GP 临时文件已失效。请重新点击「保存 GP」。"
            file?.delete()
            renderPage()
            return
        }
        runTask("正在保存 GP…", {
            try {
                file.inputStream().use { input ->
                    val out = contentResolver.openOutputStream(uri, "wt")
                        ?: throw PdfConversionException("无法写入所选位置。请换到本机「下载」目录后重试。")
                    out.use { input.copyTo(it) }
                }
            } catch (_: SecurityException) {
                throw PdfConversionException("没有所选位置的写入权限。请重新选择保存位置。")
            } finally {
                file.delete()
            }
        }, failure = "写入 GP 文件失败。请检查储存空间，或换到本机「下载」目录后重试。") {
            message = "GP 文件已保存。"
            updateStatus()
        }
    }

    private fun <T> runTask(working: String, operation: () -> T,
                            failure: String = "无法完成 PDF 操作，请重新选择文件。", display: (T) -> Unit) {
        if (busy) return
        busy = true
        message = working
        renderPage()
        io.execute {
            try {
                val result = operation()
                runOnUiThread {
                    if (isDestroyed || isFinishing) {
                        if (result is OpenPdf) result.file.delete()
                        if (result is File) result.delete()
                        return@runOnUiThread
                    }
                    busy = false
                    renderPage()
                    display(result)
                    finishPendingSave()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    busy = false
                    message = (error as? PdfConversionException)?.message ?: failure
                    renderPage()
                    finishPendingSave()
                }
            }
        }
    }

    private fun updateStatus() {
        status.text = message
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        picker.isEnabled = !busy
    }

    private fun barLabel(bar: PdfTabBar, index: Int) = if (bar.number > 0) "第 ${bar.number} 小节" else "本页第 ${index + 1} 小节"
    private fun dp(value: Int) = AppUi.dp(this, value)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Float, color: Int = AppUi.ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }
    private fun button(value: String, primary: Boolean = false, clicked: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false
        setTextColor(if (primary) AppUi.onAccent else AppUi.ink)
        background = AppUi.ripple(context, if (primary) AppUi.accent else AppUi.panel, border = if (primary) null else AppUi.line)
        setPadding(dp(8), 0, dp(8), 0); minimumHeight = dp(44); minHeight = dp(44)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { clicked() }
    }
    private fun numberField(hint: String, value: String) = EditText(this).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER; setText(value) }

    private inner class PagePreview(private val bitmap: Bitmap, private val entries: List<EditableBar>) : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val destination = RectF()
        private val overlay = RectF()
        var host: ScrollView? = null
        private var touched = -1
        init { isClickable = entries.isNotEmpty() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val scale = (pageData?.result?.pageWidth ?: 0.0) / width.coerceAtLeast(1)
                val x = event.x * scale
                val y = event.y * scale
                touched = entries.indexOfFirst { entry ->
                    val box = entry.bar.bounds
                    x >= box.left && x < box.right && y in box.top..box.bottom
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            val handled = super.performClick()
            val entry = entries.getOrNull(touched) ?: return handled
            focused = touched
            touched = -1
            invalidate()
            editBar(entry)
            return true
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, resolveSize((width * bitmap.height.toFloat() / bitmap.width).toInt(), heightMeasureSpec))
        }
        override fun onDraw(canvas: Canvas) {
            val imageHeight = width * bitmap.height.toFloat() / bitmap.width
            destination.set(0f, 0f, width.toFloat(), imageHeight)
            paint.color = Color.WHITE
            canvas.drawBitmap(bitmap, null, destination, paint)
            val result = pageData?.result ?: return
            val scale = width / result.pageWidth.toFloat()
            for ((index, entry) in entries.withIndex()) {
                val bar = entry.bar
                if (bar.page != page) continue
                paint.color = when {
                    focused == index -> 0x884ca98e.toInt()
                    bar.issues.isNotEmpty() -> 0x38c2562f
                    bar.review.isNotEmpty() -> 0x36e2a020
                    else -> continue
                }
                overlay.set((bar.bounds.left * scale).toFloat(), (bar.bounds.top * scale).toFloat(),
                    (bar.bounds.right * scale).toFloat(), (bar.bounds.bottom * scale).toFloat())
                canvas.drawRect(overlay, paint)
            }
        }
        fun focusBar(index: Int) {
            val result = pageData?.result ?: return
            val scale = width / result.pageWidth.toFloat()
            host?.smoothScrollTo(0, (entries[index].bar.bounds.top * scale - dp(16)).toInt().coerceAtLeast(0))
            invalidate()
        }
    }

    companion object {
        internal const val PREVIEW_EXTRA = "com.stringpage.app.PDF_PREVIEW"
        internal const val REVIEW_PLAYBACK_EXTRA = "com.stringpage.app.PDF_REVIEW_PLAYBACK"
        private const val PREVIEW_DIR = "pdf-gp-previews"
        internal fun privatePreviewFile(cache: File, name: String?): File? {
            if (name == null || !name.matches(Regex("tab-[0-9]+\\.gp"))) return null
            val root = File(cache, PREVIEW_DIR).canonicalFile
            val file = File(root, name).canonicalFile
            return file.takeIf { it.parentFile == root && it.isFile && it.length() in 1..4_194_304 }
        }
    }
}
