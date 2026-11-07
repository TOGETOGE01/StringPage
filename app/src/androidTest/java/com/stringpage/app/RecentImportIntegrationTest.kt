@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.AlphaTabView
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RecentImportIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val prefs get() = context.getSharedPreferences("library", 0)
    private lateinit var activity: MainActivity
    private val files = mutableListOf<File>()
    private val api get() = activity.findViewById<AlphaTabView>(R.id.score_view).api
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>(); instrument.runOnMainSync { result.set(action()) }; return result.get()
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) { if (ui(condition)) return; SystemClock.sleep(60) }
        fail("Timed out: $description")
    }
    private fun gp(name: String) = File(context.cacheDir, name).apply {
        instrument.context.assets.open("no-audio.gp").use { input -> outputStream().use { input.copyTo(it) } }; files.add(this)
    }
    @Before fun setup() {
        prefs.edit().clear().commit()
        activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    @After fun close() { ui { activity.finish() }; instrument.waitForIdleSync(); prefs.edit().clear().commit(); files.forEach { it.delete() } }

    @Suppress("DEPRECATION")
    @Test fun multiSelectResultImportsValidFilesWithoutReplacingOpenScore() {
        ui { activity.openDemo() }
        await("demo ready") { activity.findViewById<Button>(R.id.play_pause).isEnabled }
        val current = ui { api.score }
        val first = gp("batch-a.gp"); val second = gp("batch-b.gp")
        val broken = File(context.cacheDir, "batch-broken.gp").apply { writeText("broken"); files.add(this) }
        val clip = ClipData.newRawUri("Scores", Uri.fromFile(first)).apply {
            addItem(ClipData.Item(Uri.fromFile(second))); addItem(ClipData.Item(Uri.fromFile(broken)))
        }
        ui {
            assertTrue(activity.importIntent().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            activity.acceptImportSelection(Intent().apply { clipData = clip })
        }
        await("batch finished") { activity.findViewById<TextView>(R.id.playback_status).text.contains("批量导入完成") }
        ui {
            assertSame(current, api.score)
            assertTrue(activity.findViewById<Button>(R.id.play_pause).isEnabled)
            assertEquals(2, RecentLibrary(prefs).entries().size)
        }
    }

    @Test fun collapsePersistsAndRemovingCurrentBookmarkKeepsScoreAndFile() {
        val file = gp("remove-bookmark.gp")
        ui { activity.openUri(Uri.fromFile(file)) }
        await("GP ready") { activity.findViewById<Button>(R.id.play_pause).isEnabled }
        val current = ui { api.score }
        ui {
            activity.findViewById<Button>(R.id.recent_toggle).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.recent_list).visibility)
            assertTrue(RecentLibrary(prefs).collapsed)
            activity.findViewById<Button>(R.id.recent_toggle).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recent_list).visibility)
            val list = activity.findViewById<LinearLayout>(R.id.recent_list)
            ((list.getChildAt(0) as LinearLayout).getChildAt(1) as Button).performClick()
            assertTrue(RecentLibrary(prefs).entries().isEmpty())
            assertNull(prefs.getString("lastUri", null))
            assertSame(current, api.score)
            assertTrue(activity.findViewById<Button>(R.id.play_pause).isEnabled)
            assertTrue(file.exists())
        }
    }
}
