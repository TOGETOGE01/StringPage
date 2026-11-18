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
        var stems: AmpStems? = null
        try {
            val fixture = instrument.practiceScoreUri()
            ui { activity.openUri(fixture) }; await("fixture ready") { button.isEnabled }
            val score = ui { api.score!! }
            val originalAudio = score.backingTrack!!.rawAudioFile
            val syncCount = score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 }
            val performance = ScorePerformance.compile(score)
            val bank = Gp7Bank.open(bankFile).readMetadata()
            val routes = performance.notes.map { it.track }.distinct().associate { FactoryScoreRenderer.Route(it) to BankLibrary.Item(bankFile, bank) }
            val output = File(context.cacheDir, "factory-integration.wav"); generated = output
            val dry = FactoryScoreRenderer.render(performance, routes, output)
            val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(output.readBytes()).toList()
            val result = FactoryScoreRenderer.renderWithAmps(performance, routes, output, emptySet(), 0,
                mapOf(0 to GuitarAmpSettings(AmpPreset.Crunch)))
            assertEquals(dry.durationMs, result.durationMs, 0.0)
            assertNotEquals(checksum, java.security.MessageDigest.getInstance("SHA-256").digest(output.readBytes()).toList())
            assertTrue(result.noteCount > 0); assertTrue(result.peak > 0.001f)
            assertTrue(kotlin.math.abs(44L + (result.durationMs * 44.1).toLong() * 4 - output.length()) <= 4L)
            val prepared = FactoryScoreRenderer.prepareStems(performance, routes, context.cacheDir, emptySet(), 0, setOf(0))
            stems = prepared.stems
            assertEquals(dry.noteCount, prepared.noteCount)
            ui { activity.acceptFactoryAudio(score, prepared) }
            await("factory score ready") { button.isEnabled && activity.findViewById<Button>(R.id.audio_source).text == "原厂采样" }
            val stream = ui {
                assertSame(originalAudio, api.score!!.backingTrack!!.rawAudioFile)
                assertEquals(syncCount, score.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
                val handler = (api.player!!.output as IExternalMediaSynthOutput).handler as StemAudio
                assertEquals(result.durationMs, handler.backingTrackDuration, 30.0)
                button.performClick()
                handler
            }
            await("factory audio clock advances") { api.timePosition > 1100.0 }
            ui {
                assertEquals(performance.msToTick(api.timePosition), api.tickPosition, 80.0)
                assertTrue(activity.applyLiveAmps(mapOf(0 to GuitarAmpSettings(AmpPreset.Crunch))))
                assertSame(stream, (api.player!!.output as IExternalMediaSynthOutput).handler)
            }
            await("factory amp change keeps physical cursor advancing") { api.timePosition > 1400.0 }
            ui {
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
            assertTrue("Loop is already queued on the same audio stream", stream.completedLoops > 0)
            ui { api.pause(); activity.switchSource(true) }
            await("original embedded recording restored without recreating the external player") {
                button.isEnabled && activity.findViewById<Button>(R.id.audio_source).text == "内嵌原曲"
            }
            await("Prepared stems removed on source change") { prepared.stems.parts.none { it.original.file.exists() } }
            ui { button.performClick() }
            await("restored recording advances") { api.timePosition > 800.0 }
            ui { api.pause(); activity.switchSource(false) }
            await("GeneralUser source restored") { button.isEnabled && api.player!!.output !is IExternalMediaSynthOutput }
            ui { assertSame(originalAudio, api.score!!.backingTrack!!.rawAudioFile) }
        } finally { ui { activity.finish() }; generated?.delete(); stems?.close(); instrument.waitForIdleSync() }
    }
}
