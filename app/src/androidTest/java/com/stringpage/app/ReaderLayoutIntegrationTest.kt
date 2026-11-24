@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.ScrollMode
import alphaTab.synth.PlayerState
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ReaderLayoutIntegrationTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private fun <T> ui(action: () -> T): T {
        val result=AtomicReference<T>();instrument.runOnMainSync { result.set(action()) };return result.get()
    }
    @Test fun theCurrentWindowLeavesReadableScoreSpaceAndVisiblePlaybackControls() {
        val activity=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            assertFalse(instrument.targetContext.assets.list("").orEmpty().contains("morning.gp"))
            val fixture = instrument.practiceScoreUri()
            ui { activity.openUri(fixture) }
            val deadline=SystemClock.uptimeMillis()+15000
            while(!ui { activity.findViewById<Button>(R.id.play_pause).isEnabled } && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(40)
            instrument.waitForIdleSync()
            ui {
                assertTrue(activity.findViewById<Button>(R.id.play_pause).isEnabled)
                val score=activity.findViewById<AlphaTabView>(R.id.score_view)
                val visible=Rect();val shown=score.getGlobalVisibleRect(visible)
                val density=activity.resources.displayMetrics.density
                val config=activity.resources.configuration
                val brand=activity.findViewById<TextView>(R.id.app_brand)
                if(brand.text.isNotEmpty()) {
                    assertEquals("App name must remain on one line",1,brand.layout.lineCount)
                    assertEquals("App name must fit its header",0,brand.layout.getEllipsisCount(0))
                }
                assertTrue("No readable score viewport: ${config.screenWidthDp}x${config.screenHeightDp}dp, font=${config.fontScale}, visible=$visible, measured=${score.width}x${score.height}",
                    shown && visible.height()/density>=80 && visible.width()/density>=200)
                for(id in listOf(R.id.play_pause,R.id.speed_select,R.id.loop_select,R.id.reader_metronome_toggle,R.id.more_actions)) {
                    val control=activity.findViewById<Button>(id);val bounds=Rect()
                    assertTrue("Playback/import control is clipped: ${control.text}",control.getGlobalVisibleRect(bounds) && bounds.width()==control.width && bounds.height()==control.height)
                    if (id != R.id.more_actions) {
                        assertNotNull("The control has no accessible name", control.contentDescription)
                        assertEquals("Essential playback label is truncated: ${control.text}",0,control.layout?.getEllipsisCount(0) ?: 0)
                    }
                }
                assertTrue(activity.applyLoopRange(2, 3))
            }
            val seekDeadline = SystemClock.uptimeMillis() + 5000
            fun currentBar() = ui { activity.findViewById<TextView>(R.id.reader_bar_number).text.toString() }
            while (!currentBar().startsWith("小节 2 / ") && SystemClock.uptimeMillis() < seekDeadline) SystemClock.sleep(40)
            assertTrue("Paused seeks must update the bar counter: ${currentBar()}", currentBar().startsWith("小节 2 / "))
        } finally { ui { activity.finish() };instrument.waitForIdleSync() }
    }

    @Test fun readerPracticeControlsKeepThePlayerAndPickLoopEndpointsOnTheScore() {
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        fun button(id: Int) = activity.findViewById<Button>(id)
        val sheet = ui { activity.findViewById<AlphaTabView>(R.id.score_view) }
        val api = sheet.api
        try {
            ui { activity.openUri(instrument.practiceScoreUri()) }
            await("score ready") { button(R.id.play_pause).isEnabled && api.boundsLookup?.isFinished == true }
            val player = ui { api.player }
            ui {
                button(R.id.reader_metronome_toggle).performClick()
                assertTrue(activity.practice.metronomeUi().enabled)
                assertTrue(button(R.id.reader_metronome_toggle).isSelected)
                button(R.id.speed_select).performClick()
            }
            await("speed panel opens") { button(R.id.reader_speed_sixty).isShown }
            ui {
                val tabs = activity.findViewById<LinearLayout>(R.id.reader_practice_tabs)
                for ((index, label) in listOf("速度", "循环", "节拍").withIndex()) {
                    val tab = tabs.getChildAt(index) as Button
                    assertEquals(label, tab.text.toString())
                    assertEquals("Practice tab is truncated: $label", 0, tab.layout.getEllipsisCount(0))
                }
                button(R.id.reader_speed_sixty).performClick()
                assertEquals(0.6, api.playbackSpeed, 0.001)
                assertTrue(button(R.id.speed_select).text.contains("60%"))
                assertSame(player, api.player)
                button(R.id.loop_select).performClick()
                button(R.id.reader_loop_select_on_score).performClick()
                assertTrue(activity.findViewById<View>(R.id.reader_loop_selection).isShown)
            }
            await("score ready to pick an endpoint") { api.boundsLookup?.isFinished == true }
            ui {
                val hit = api.boundsLookup!!.findMasterBarByIndex(0.0)!!.realBounds
                val viewport = sheet.findViewById<View>(net.alphatab.R.id.outerScroll)
                val surface = sheet.findViewById<View>(net.alphatab.R.id.renderSurface)
                val origin = IntArray(2).also(surface::getLocationOnScreen)
                val scale = viewport.measuredWidth / api.container.width
                val x = (origin[0] + (hit.x + hit.w / 2) * scale).toFloat()
                val y = (origin[1] + (hit.y + hit.h / 2) * scale).toFloat()
                repeat(2) {
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        activity.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                assertTrue(api.isLooping)
                assertNotNull(api.playbackRange)
                assertFalse(activity.findViewById<View>(R.id.reader_loop_selection).isShown)
                assertTrue(button(R.id.loop_select).text.contains("1–1"))
                assertSame(player, api.player)
            }
            ui { button(R.id.play_pause).performClick() }
            await("the loop plays") { api.playerState == PlayerState.Playing }
            ui {
                val bounds = Rect().also { assertTrue(sheet.getGlobalVisibleRect(it)) }
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val down = SystemClock.uptimeMillis()
                for ((action, position) in listOf(MotionEvent.ACTION_DOWN to y, MotionEvent.ACTION_MOVE to y - 60f,
                    MotionEvent.ACTION_UP to y - 85f)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, position, 0)
                    activity.dispatchTouchEvent(event)
                    event.recycle()
                }
                assertEquals(ScrollMode.Off, sheet.settings.player.scrollMode)
                assertTrue(api.isLooping)
                assertTrue(button(R.id.reader_scroll_resume).isShown)
                button(R.id.reader_scroll_resume).performClick()
                assertEquals(ScrollMode.Continuous, sheet.settings.player.scrollMode)
                assertFalse(button(R.id.reader_scroll_resume).isShown)
                api.pause()
            }
        } finally { ui { activity.finish() }; instrument.waitForIdleSync() }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui(condition)) return
            SystemClock.sleep(40)
        }
        fail(description)
    }
}
