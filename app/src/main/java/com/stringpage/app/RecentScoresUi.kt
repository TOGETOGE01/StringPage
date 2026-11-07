package com.stringpage.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared sidebar and searchable manager, including the same remove controls on phones. */
class RecentScoresUi(
    private val context: Context,
    private val library: RecentLibrary,
    private val onOpen: (Uri) -> Unit,
    private val onImport: () -> Unit
) {
    private fun dp(value: Int) = AppUi.dp(context, value)
    private val preview = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; id = R.id.recent_list }
    private val toggle = action("最近打开") { library.collapsed = !library.collapsed; refresh() }.apply {
        id = R.id.recent_toggle; gravity = Gravity.CENTER_VERTICAL; textSize = 12f
        setPadding(dp(4), 0, dp(4), 0); setTextColor(AppUi.muted)
    }
    val section = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(toggle, LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(action("") { show() }.apply {
            contentDescription = "管理最近打开"; tooltipText = "管理最近打开"; id = R.id.manage_recents
            AppUi.iconButton(this, "more", AppUi.muted); compoundDrawablePadding = 0; setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(preview)
    }
    private var manager: AlertDialog? = null
    private var refreshManager: (() -> Unit)? = null

    private fun label(text: String) = TextView(context).apply { this.text = text; textSize = 12f; setTextColor(AppUi.muted) }
    private fun action(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text; textSize = 13f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minimumHeight = dp(44); minHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setTextColor(AppUi.ink); background = AppUi.ripple(context, Color.WHITE, 8)
        stateListAnimator = null; setOnClickListener { onClick() }
    }

    fun refresh() {
        val entries = library.entries()
        toggle.text = "最近打开 · ${entries.size}"
        toggle.contentDescription = if (library.collapsed) "展开最近打开" else "折叠最近打开"
        toggle.setCompoundDrawables(null, null, AppUi.icon(context, if (library.collapsed) "chevron" else "down", AppUi.muted, 14), null)
        toggle.compoundDrawablePadding = dp(4)
        preview.visibility = if (library.collapsed) View.GONE else View.VISIBLE
        preview.removeAllViews()
        if (entries.isEmpty()) preview.addView(label("暂无记录").apply { setPadding(dp(4), dp(6), 0, dp(6)) })
        entries.take(PREVIEW_COUNT).forEach { entry -> preview.addView(entryRow(entry, false)) }
        if (entries.size > PREVIEW_COUNT) preview.addView(action("查看全部（${entries.size}）") { show() }.apply {
            setTextColor(AppUi.accent)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        refreshManager?.invoke()
    }

    private fun entryRow(entry: RecentLibrary.Entry, detail: Boolean): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val open = action(entry.name) { manager?.dismiss(); onOpen(Uri.parse(entry.uri)) }.apply {
            gravity = Gravity.CENTER_VERTICAL; textSize = if (detail) 14f else 12f
            tooltipText = listOf(entry.name, entry.fileName).filter { it.isNotBlank() }.joinToString("\n")
            contentDescription = "打开 ${entry.name} ${entry.fileName}"
            if (detail && entry.fileName.isNotBlank()) { text = "${entry.name}\n${entry.fileName}"; maxLines = 2 }
        }
        addView(open, LinearLayout.LayoutParams(0, dp(if (detail) 64 else 48), 1f))
        addView(action("") { remove(setOf(entry.uri)) }.apply {
            contentDescription = "移除记录 ${entry.name} ${entry.fileName}"; tooltipText = "移除记录"
            AppUi.iconButton(this, "trash", AppUi.muted, 17); compoundDrawablePadding = 0; setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
    }

    private fun remove(uris: Set<String>) {
        library.remove(uris)
        // Drop only persisted read grants owned by these bookmarks. Source files are never touched.
        uris.forEach { uri ->
            try { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* A transient or already released grant needs no cleanup. */ }
        }
        refresh()
        Toast.makeText(context, "已移除 ${uris.size} 条记录，原文件保留。", Toast.LENGTH_SHORT).show()
    }

    fun show() {
        manager?.dismiss()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(4)) }
        val search = EditText(context).apply {
            hint = "搜索曲名或文件名"; contentDescription = "搜索最近打开"; setSingleLine(); textSize = 14f
            id = R.id.recent_search; setPadding(dp(12), 0, dp(12), 0)
            background = AppUi.surface(context, AppUi.canvas, 10)
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(label("点按打开；垃圾桶仅移除记录，保留原文件。").apply { setPadding(dp(4), dp(10), 0, dp(6)) })
        val actions = LinearLayout(context)
        val select = action("批量移除") { selectToRemove(search.text.toString()) }
        val clear = action("清空记录") {
            val entries = library.entries()
            AlertDialog.Builder(context).setTitle("清空 ${entries.size} 条最近记录？")
                .setMessage("原始 GP 文件和当前打开的乐谱均保留。")
                .setPositiveButton("清空记录") { _, _ -> remove(entries.mapTo(HashSet()) { it.uri }) }
                .setNegativeButton("取消", null).show()
        }
        actions.addView(select, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(clear, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(actions)
        var visible = emptyList<RecentLibrary.Entry>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?) = entryRow(visible[position], true)
        }
        val list = object : ListView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.50).toInt()
                val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight else minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
        }.apply { this.adapter = adapter; id = R.id.recent_manager_list; dividerHeight = dp(1) }
        val empty = label("暂无记录，可在导入时多选 GP 文件。").apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) }
        content.addView(empty); content.addView(list, LinearLayout.LayoutParams(-1, -2)); list.emptyView = empty
        val dialog = AlertDialog.Builder(context).setTitle("最近打开").setView(content)
            .setNeutralButton("导入 GP 文件") { _, _ -> onImport() }.setNegativeButton("关闭", null).create()
        manager = dialog
        fun update() {
            val all = library.entries()
            visible = filter(all, search.text.toString())
            dialog.setTitle("最近打开 · ${all.size}")
            empty.text = if (all.isEmpty()) "暂无记录，可在导入时多选 GP 文件。" else "没有匹配的乐谱。"
            select.isEnabled = visible.isNotEmpty(); clear.isEnabled = all.isNotEmpty()
            adapter.notifyDataSetChanged()
        }
        refreshManager = ::update
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update() }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.setOnDismissListener { if (manager === dialog) { manager = null; refreshManager = null } }
        dialog.setOnShowListener {
            dialog.window?.setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(24), dp(640)), -2)
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        update(); dialog.show()
    }

    private fun filter(entries: List<RecentLibrary.Entry>, query: String): List<RecentLibrary.Entry> = entries.filter {
        query.isBlank() || it.name.contains(query.trim(), true) || it.fileName.contains(query.trim(), true)
    }

    private fun selectToRemove(query: String) {
        val entries = filter(library.entries(), query)
        val selected = HashSet<String>()
        AlertDialog.Builder(context).setTitle("批量移除记录")
            .setMultiChoiceItems(entries.map { if (it.fileName.isBlank()) it.name else "${it.name} · ${it.fileName}" }.toTypedArray(), null) { _, which, checked ->
                if (checked) selected.add(entries[which].uri) else selected.remove(entries[which].uri)
            }.setPositiveButton("移除所选") { _, _ -> if (selected.isNotEmpty()) remove(selected) }
            .setNegativeButton("取消", null).show()
    }

    fun close() { manager?.dismiss() }
    companion object { const val PREVIEW_COUNT = 8 }
}
