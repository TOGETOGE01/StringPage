package com.stringpage.app

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderThemeTest {
    @Test fun everyPaletteKeepsLabelsAndScoreGlyphsReadable() {
        for (theme in ReaderTheme.entries) {
            val p = theme.palette
            for ((text, surface) in listOf(p.ink to p.panel, p.muted to p.panel, p.ink to p.score,
                p.onAccent to p.accent, p.railInk to p.rail, p.railMuted to p.rail, p.railAccent to p.railActive)) {
                val contrast = ColorUtils.calculateContrast(ThemePalette.opaque(text), ThemePalette.opaque(surface))
                assertTrue("${theme.label} contrast $contrast", contrast >= 4.5)
            }
        }
    }
}
