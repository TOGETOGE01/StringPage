package com.stringpage.app

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/** The reader's anchored menu and its editing subpage. */
internal class ReaderMenu(private val context: Context) {
    data class Action(
        val label: String,
        val icon: String,
        val value: String? = null,
        val enabled: Boolean = true,
        val featured: Boolean = false,
        val children: List<Action> = emptyList(),
        val run: () -> Unit = {}
    )

    data class Section(val title: String, val actions: List<Action>, val shortcuts: List<Action> = emptyList())

    private var popup: PopupWindow? = null

    fun dismiss() { popup?.dismiss() }

    fun show(anchor: View, sections: List<Section>) {
        if (popup?.isShowing == true) { dismiss(); return }
        val viewport = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val width = minOf(dp(354), viewport.width() - dp(24))
        val maxHeight = viewport.bottom - location[1] - anchor.height - dp(18)
        if (!anchor.isAttachedToWindow || width <= 0 || maxHeight <= 0) return

        val content = column().apply { setPadding(dp(12), dp(9), dp(12), dp(10)) }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
                else minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            clipToOutline = true
            background = AppUi.surface(context, AppUi.panel, 17)
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        }
        val window = PopupWindow(scroll, width, WRAP_CONTENT, true).apply {
            setBackgroundDrawable(AppUi.surface(context, AppUi.panel, 17, AppUi.line))
            elevation = dp(15).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        popup = window

        fun render(subpage: Action? = null, previousScroll: Int = 0) {
            content.removeAllViews()
            ViewCompat.setAccessibilityPaneTitle(scroll, subpage?.label ?: "更多")
            fun select(action: Action) {
                if (action.children.isNotEmpty()) render(action, scroll.scrollY)
                else { window.dismiss(); action.run() }
            }
            if (subpage != null) {
                content.addView(row(Action("返回", "back")) { render(previousScroll = previousScroll) })
                content.addView(heading(subpage.label))
                subpage.children.forEach { content.addView(row(it) { select(it) }) }
            } else {
                for ((index, section) in sections.withIndex()) {
                    if (index > 0) content.addView(View(context).apply { setBackgroundColor(AppUi.line) },
                        LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(5); bottomMargin = dp(5) })
                    content.addView(heading(section.title))
                    for (pair in section.shortcuts.chunked(2)) {
                        val shortcuts = LinearLayout(context)
                        for ((column, item) in pair.withIndex()) {
                            val shortcut = shortcut(item) { select(item) }
                            shortcuts.addView(shortcut, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                                marginStart = dp(if (column == 0) 0 else 6)
                            })
                        }
                        content.addView(shortcuts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
                    }
                    section.actions.forEach { content.addView(row(it) { select(it) }) }
                }
            }
            scroll.post { scroll.scrollTo(0, if (subpage == null) previousScroll else 0) }
        }

        val idle = anchor.background
        anchor.background = AppUi.ripple(context, AppUi.railSurface, 10)
        window.setOnDismissListener {
            anchor.background = idle
            if (popup === window) popup = null
        }
        render()
        window.showAsDropDown(anchor, 0, dp(8), Gravity.END)
    }

    private fun heading(value: String) = caption(value, 12f, AppUi.muted).apply {
        typeface = AppUi.medium
        setPadding(dp(8), dp(6), dp(8), dp(6))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun shortcut(action: Action, selected: () -> Unit) = column().apply {
        val color = if (action.featured) AppUi.accent else AppUi.ink
        background = AppUi.ripple(context, if (action.featured) AppUi.selected else AppUi.control, 11)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(66)
        setPadding(dp(11), dp(7), dp(11), dp(7))
        addView(image(action.icon, color, 19), LinearLayout.LayoutParams(dp(19), dp(19)).apply { bottomMargin = dp(3) })
        addView(caption(action.label, 13f, color).apply { typeface = AppUi.medium; maxLines = 2 })
        bind(action, selected)
    }

    private fun row(action: Action, selected: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(45)
        setPadding(dp(7), dp(5), dp(7), dp(5))
        background = AppUi.ripple(context, Color.TRANSPARENT, 9)
        addView(image(action.icon, AppUi.accent, 17), LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(11) })
        addView(caption(action.label, 14f, AppUi.ink), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        action.value?.takeIf(String::isNotBlank)?.let { value ->
            addView(caption(value, 12f, AppUi.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(96)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        if (action.children.isNotEmpty()) addView(image("chevron", AppUi.muted, 14), LinearLayout.LayoutParams(dp(14), dp(14)))
        bind(action, selected)
    }

    private fun View.bind(action: Action, selected: () -> Unit) {
        contentDescription = listOfNotNull(action.label, action.value).joinToString("，")
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        setOnClickListener { selected() }
        isEnabled = action.enabled
        isFocusable = action.enabled
        if (!action.enabled) alpha = 0.46f
    }

    private fun image(icon: String, color: Int, size: Int) = View(context).apply {
        background = AppUi.icon(context, icon, color, size)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun caption(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(value: Int) = AppUi.dp(context, value)
}
