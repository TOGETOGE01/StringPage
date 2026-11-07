@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.synth.IExternalMediaSynthOutput
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stringpage.app.rse.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FactoryPlaybackIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>(); instrument.runOnMainSync { result.set(action()) }; return result.get()
    }
    private fun await(description: String, timeout: Long = 20000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (ui(check)) return; SystemClock.sleep(50) }
        fail(description)
    }
    @Test fun originalBankPlaysTheActualGpScoreWithSeekSpeedLoopAndSourceSwitching() {
        val context = instrument.targetContext
        val bankFile = File(context.filesDir, "rse-reference.gp7bank")
        assumeTrue("Original local bank must be provisioned separately", bankFile.isFile)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val api = ui { activity.findViewById<AlphaTabView>(R.id.score_view).api }
        val button = ui { activity.findViewById<Button>(R.id.play_pause) }
        var generated: File? = null
        try {
            ui { activity.openDemo() }; await("demo ready") { button.isEnabled }
            val score = ui { api.score!! }
            val originalAudio = score.backingTrack!!.rawAudioFile
            val syncCount = score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 }
            val performance = ScorePerformance.compile(score)
            val bank = Gp7Bank.open(bankFile).readMetadata()
            val routes = performance.notes.map { it.track }.distinct().associate { FactoryScoreRenderer.Route(it) to BankLibrary.Item(bankFile, bank) }
            val output = File(context.cacheDir, "factory-integration.wav"); generated = output
            val result = FactoryScoreRenderer.render(performance, routes, output)
            assertTrue(result.noteCount > 0); assertTrue(result.peak > 0.001f)
            assertTrue(kotlin.math.abs(44L + (result.durationMs * 44.1).toLong() * 4 - output.length()) <= 4L)
            ui { activity.acceptFactoryAudio(score, result) }
            await("factory score ready") { button.isEnabled && activity.findViewById<Button>(R.id.audio_source).text == "原厂采样" }
            ui {
                assertSame(originalAudio, api.score!!.backingTrack!!.rawAudioFile)
                assertEquals(syncCount, score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
                assertEquals(result.durationMs, (api.player!!.output as IExternalMediaSynthOutput).handler!!.backingTrackDuration, 30.0)
                button.performClick()
            }
            await("factory audio clock advances") { api.timePosition > 1100.0 }
            ui {
                assertEquals(performance.msToTick(api.timePosition), api.tickPosition, 80.0)
                button.performClick(); api.timePosition = 5000.0
            }
            await("factory seek follows the actual score") { api.timePosition > 4700.0 }
            val tick = ui { api.tickPosition }
            ui { activity.changeSpeed(0.5) }
            SystemClock.sleep(300)
            ui { assertEquals(tick, api.tickPosition, 100.0); activity.changeSpeed(1.0); activity.applyLoopRange(1,1); button.performClick() }
            var endSeen = false; var wrapped = false
            val loopEnd = performance.bars.first().endTick
            val until = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < until && !wrapped) {
                val at = ui { api.tickPosition }
                if (at > loopEnd * 0.7) endSeen = true
                if (endSeen && at < loopEnd * 0.3) wrapped = true
                SystemClock.sleep(50)
            }
            assertTrue("Prepared original-bank score must loop", wrapped)
            ui { api.pause(); activity.switchSource(true) }
            await("original embedded recording restored without recreating the external player") {
                button.isEnabled && activity.findViewById<Button>(R.id.audio_source).text == "内嵌原曲"
            }
            assertFalse("Prepared file must be removed when returning to embedded audio", output.exists())
            ui { button.performClick() }
            await("restored recording advances") { api.timePosition > 800.0 }
            ui { api.pause(); activity.switchSource(false) }
            await("GeneralUser source restored") { button.isEnabled && api.player!!.output !is IExternalMediaSynthOutput }
            ui { assertSame(originalAudio, api.score!!.backingTrack!!.rawAudioFile) }
            assertFalse("Private prepared file must be removed on source change", output.exists())
        } finally { ui { activity.finish() }; generated?.delete(); instrument.waitForIdleSync() }
    }
}
