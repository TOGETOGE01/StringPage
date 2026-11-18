package com.stringpage.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class ReaderTheme(val label: String, val style: Int, val palette: ThemePalette, val dark: Boolean = false) {
    Clay("暖陶", R.style.AppTheme, ThemePalette(
        ink = 0x272029, muted = 0x6c6066, canvas = 0xf1ebe5, panel = 0xfffbf6, control = 0xf5ede7,
        line = 0xe1d6d0, accent = 0xa13c2b, onAccent = 0xffffff, selected = 0xf8e3db, score = 0xffffff,
        rail = 0x272029, railSurface = 0x352b35, railLine = 0x4b3d49, railActive = 0x4d2f31,
        railInk = 0xfff5ef, railMuted = 0xccb9be, railAccent = 0xffaf97
    )),
    Forest("松林", R.style.AppThemeForest, ThemePalette(
        ink = 0x1b332c, muted = 0x4f655d, canvas = 0xe3ede7, panel = 0xf4f9f4, control = 0xe8f1ea,
        line = 0xc4d6ca, accent = 0x246449, onAccent = 0xffffff, selected = 0xd7eadc, score = 0xf9fcf7,
        rail = 0x172e26, railSurface = 0x223d33, railLine = 0x3e574b, railActive = 0x2c4c3c,
        railInk = 0xf0f8ef, railMuted = 0xb7cdbf, railAccent = 0xa9e1b5
    )),
    Ocean("海蓝", R.style.AppThemeOcean, ThemePalette(
        ink = 0x1e3147, muted = 0x52657a, canvas = 0xe6edf6, panel = 0xf6f9fd, control = 0xeaf0f9,
        line = 0xc8d6e7, accent = 0x285b9d, onAccent = 0xffffff, selected = 0xdce9fa, score = 0xfbfcff,
        rail = 0x192941, railSurface = 0x223852, railLine = 0x3e5370, railActive = 0x294566,
        railInk = 0xf0f7ff, railMuted = 0xb2c7e0, railAccent = 0xa5d2ff
    )),
    Night("夜墨", R.style.AppThemeNight, ThemePalette(
        ink = 0xe6edf1, muted = 0xacbbc5, canvas = 0x161e27, panel = 0x202b35, control = 0x293743,
        line = 0x465866, accent = 0x8ce0cd, onAccent = 0x122923, selected = 0x29443f, score = 0x1a232d,
        rail = 0x111820, railSurface = 0x1e2a35, railLine = 0x384a59, railActive = 0x233f3d,
        railInk = 0xe8f3f8, railMuted = 0xaabdc9, railAccent = 0x9ee9d9
    ), dark = true);

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, name).apply()
    }

    fun applyTo(activity: Activity) {
        AppUi.theme = this
        activity.setTheme(style)
    }

    companion object {
        private const val PREFS = "appearance"
        private const val KEY = "reader_theme"

        fun read(context: Context): ReaderTheme {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: Clay
        }
    }
}

internal data class ThemePalette(
    val ink: Int,
    val muted: Int,
    val canvas: Int,
    val panel: Int,
    val control: Int,
    val line: Int,
    val accent: Int,
    val onAccent: Int,
    val selected: Int,
    val score: Int,
    val rail: Int,
    val railSurface: Int,
    val railLine: Int,
    val railActive: Int,
    val railInk: Int,
    val railMuted: Int,
    val railAccent: Int
) {
    companion object {
        fun opaque(color: Int) = color or 0xff000000.toInt()
    }
}

internal fun showReaderThemes(activity: Activity, current: ReaderTheme, selected: (ReaderTheme) -> Unit) {
    fun dp(value: Int) = AppUi.dp(activity, value)
    val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(8))
    }
    val scroll = ScrollView(activity).apply { addView(list) }
    val dialog = AlertDialog.Builder(activity).setTitle("界面主题").setView(scroll).setNegativeButton("取消", null).create()
    for (theme in ReaderTheme.entries) {
        val active = theme == current
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = AppUi.ripple(activity, if (active) AppUi.selected else AppUi.control, 12)
            isSelected = active
            isFocusable = true
            contentDescription = if (active) "${theme.label}，当前主题" else theme.label
            setOnClickListener { dialog.dismiss(); if (!active) selected(theme) }
        }
        val swatch = LinearLayout(activity).apply {
            background = AppUi.surface(activity, ThemePalette.opaque(theme.palette.score), 7,
                ThemePalette.opaque(theme.palette.line))
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(View(activity).apply { setBackgroundColor(ThemePalette.opaque(theme.palette.rail)) }, dp(15), -1)
            addView(View(activity).apply {
                background = AppUi.surface(activity, ThemePalette.opaque(theme.palette.accent), 3)
            }, LinearLayout.LayoutParams(dp(16), dp(9)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(7) })
        }
        row.addView(swatch, dp(46), dp(34))
        row.addView(TextView(activity).apply {
            text = theme.label
            textSize = 16f
            setTextColor(AppUi.ink)
            setPadding(dp(12), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            typeface = AppUi.medium
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (active) row.addView(TextView(activity).apply {
            text = "当前"
            setTextColor(AppUi.accent)
            textSize = 12f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        list.addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) })
    }
    dialog.show()
}
