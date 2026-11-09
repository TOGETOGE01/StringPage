@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.AlphaTabView
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.widget.Button
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
            ui { activity.openDemo() }
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
                for(id in listOf(R.id.play_pause,R.id.speed_select,R.id.loop_select,R.id.import_file,R.id.more_actions)) {
                    val control=activity.findViewById<Button>(id);val bounds=Rect()
                    assertTrue("Playback/import control is clipped: ${control.text}",control.getGlobalVisibleRect(bounds) && bounds.width()==control.width && bounds.height()==control.height)
                    if(id==R.id.play_pause || id==R.id.speed_select || id==R.id.loop_select)
                        assertEquals("Essential playback label is truncated: ${control.text}",0,control.layout?.getEllipsisCount(0) ?: 0)
                }
            }
        } finally { ui { activity.finish() };instrument.waitForIdleSync() }
    }
}
