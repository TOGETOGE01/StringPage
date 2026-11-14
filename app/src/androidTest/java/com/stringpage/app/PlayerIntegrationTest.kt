@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.synth.IExternalMediaSynthOutput
import alphaTab.synth.PlayerState
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlayerIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val api get() = activity.findViewById<AlphaTabView>(R.id.score_view).api
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>()
        instrument.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun await(description: String, timeout: Long = 15000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (ui(condition)) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $description")
    }
    private fun awaitReady() = await("audio and score ready") {
        activity.findViewById<Button>(R.id.play_pause).isEnabled
    }
    private fun demo() { ui { activity.openDemo() }; awaitReady() }
    private fun playPause() = ui { activity.findViewById<Button>(R.id.play_pause).performClick() }

    @Before fun launch() {
        val intent = Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity = instrument.startActivitySync(intent) as MainActivity
        instrument.waitForIdleSync()
    }
    @After fun close() { ui { activity.finish() }; instrument.waitForIdleSync() }

    @Test fun tenPercentSlowsBothTheRecordingAndTheSynthesizer() {
        demo()
        val durationAtNormalSpeed = ui { api.endTime }
        assertTrue(durationAtNormalSpeed > 0.0)
        ui { activity.changeSpeed(0.1) }
        await("recording timeline is stretched to ten times its duration") {
            kotlin.math.abs(api.endTime - durationAtNormalSpeed * 10) < 50
        }
        ui { assertEquals(PlayerState.Paused, api.playerState) }
        playPause()
        await("the recording actually advances at ten percent") {
            val output = api.player!!.output as IExternalMediaSynthOutput
            val handler = output.handler as EmbeddedAudio
            val field = EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible = true }
            val player = field.get(handler) as android.media.MediaPlayer
            player.isPlaying && player.currentPosition > 100 &&
                kotlin.math.abs(player.playbackParams.speed - 0.1f) < 0.001f
        }
        playPause()
        ui { activity.switchSource(false) }
        awaitReady()
        await("synthesizer also uses the ten percent timeline") {
            api.player!!.output !is IExternalMediaSynthOutput &&
                kotlin.math.abs(api.endTime - durationAtNormalSpeed * 10) < 50
        }
        val startingTick = ui { api.tickPosition }
        playPause()
        await("synthesizer progresses at the restored slow speed") { api.tickPosition > startingTick + 120 }
        playPause()
    }

    @Test fun embeddedRecordingPlaysSeeksAndPauses() {
        demo()
        ui {
            assertTrue(api.player!!.output is IExternalMediaSynthOutput)
            assertTrue(api.score!!.backingTrack!!.rawAudioFile!!.length > 1000000)
            assertEquals(16, api.score!!.masterBars.sumOf { it.syncPoints?.length?.toInt() ?: 0 })
            assertEquals(PlayerState.Paused, api.playerState)
        }
        playPause()
        await("recording clock advances") { api.timePosition > 1200 }
        val paused = ui {
            val handler=(api.player!!.output as IExternalMediaSynthOutput).handler as EmbeddedAudio
            val media=EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }.get(handler) as android.media.MediaPlayer
            // The API briefly reports alphaTab's beat-snapped pause while the
            // native seek settles. The physical recording clock is authoritative.
            val physical=media.currentPosition
            activity.findViewById<Button>(R.id.play_pause).performClick()
            physical.toDouble()
        }
        SystemClock.sleep(500)
        ui { assertEquals(PlayerState.Paused,api.playerState);assertEquals(paused, api.timePosition, 50.0) }
        ui { api.timePosition = 12000.0 }
        await("seek follows recording") { api.timePosition > 11000 }
        ui { assertTrue(api.tickPosition > 15000) }
    }

    @Test fun speedPreservesPauseAndLoopActuallyWraps() {
        demo()
        ui { activity.changeSpeed(0.5) }
        SystemClock.sleep(600)
        ui { assertEquals(PlayerState.Paused, api.playerState); assertTrue(api.timePosition < 300) }
        ui { activity.changeSpeed(1.0); assertTrue(activity.applyLoopRange(2, 2)) }
        playPause()
        var sawEnd = false
        var wrapped = false
        val observations = ArrayList<String>()
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline && !wrapped) {
            val tick = ui { api.tickPosition }
            if (observations.size < 20 && (observations.isEmpty() || SystemClock.uptimeMillis() % 500 < 65))
                observations.add(ui { "tick=${api.tickPosition.toInt()},ms=${api.timePosition.toInt()},state=${api.playerState}" })
            if (tick > 6500) sawEnd = true
            if (sawEnd && tick < 5000) wrapped = true
            SystemClock.sleep(60)
        }
        val transport=ui {
            val output=api.player?.output as? IExternalMediaSynthOutput
            val handler=output?.handler as? EmbeddedAudio
            val media=handler?.let { EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }.get(it) as? android.media.MediaPlayer }
            "physical=${media?.currentPosition}, nativePlaying=${media?.isPlaying}, outputCurrent=${handler?.output===output}"
        }
        assertTrue("The recording must loop back to the selected bar; sawEnd=$sawEnd; $observations; $transport", wrapped)
        ui { assertFalse(activity.applyLoopRange(5, 2)) }
    }

    @Test fun noAudioFallbackAndSourceSwitching() {
        demo()
        ui { activity.switchSource(false) }; awaitReady()
        ui { assertFalse(api.player!!.output is IExternalMediaSynthOutput) }
        playPause(); await("synth clock advances") { api.timePosition > 500 }; playPause()
        ui { activity.switchSource(true) }; awaitReady()
        ui { assertTrue(api.player!!.output is IExternalMediaSynthOutput) }
        val file = File(instrument.targetContext.cacheDir, "without-audio.gp")
        instrument.context.assets.open("no-audio.gp").use { input -> file.outputStream().use { input.copyTo(it) } }
        ui { activity.openUri(Uri.fromFile(file)) }
        await("score without embedded recording loads") { api.score?.backingTrack == null && activity.findViewById<Button>(R.id.play_pause).isEnabled }
        ui { assertTrue(activity.findViewById<TextView>(R.id.playback_status).text.contains("没有可读取的内嵌音频")) }
        file.delete()
    }

    @Test fun tabletPanelsAndFullScreenPreservePlayer() {
        demo()
        val width = activity.resources.configuration.screenWidthDp
        if (width >= 1200) ui {
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.library_panel).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.practice_panel).visibility)
        }
        val original = ui { api }
        ui { activity.findViewById<Button>(R.id.full_screen).performClick() }
        instrument.waitForIdleSync()
        ui {
            assertEquals(View.GONE, activity.findViewById<View>(R.id.library_panel).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.practice_panel).visibility)
            assertSame(original, api)
            assertTrue(api.isReadyForPlayback)
            activity.findViewById<Button>(R.id.full_screen).performClick()
        }
    }

    @Test fun corruptFileKeepsPreviouslyLoadedScore() {
        demo()
        val file = File(instrument.targetContext.cacheDir, "broken.gp").apply { writeText("invalid score") }
        ui { activity.openUri(Uri.fromFile(file)) }
        await("failed import is reported") { activity.findViewById<TextView>(R.id.playback_status).text.contains("导入失败") }
        ui { assertEquals("Morning Strings", api.score!!.title) }
        file.delete()
    }

    @Test fun gp8OggCompatibilityFixture() {
        // Optional upstream fixture; download with tools/fetch-test-fixture.ps1.
        assumeTrue(instrument.context.assets.list("")!!.contains("gp8-compatibility.gp"))
        val file = File(instrument.targetContext.cacheDir, "compatibility.gp")
        instrument.context.assets.open("gp8-compatibility.gp").use { input -> file.outputStream().use { input.copyTo(it) } }
        ui { activity.openUri(Uri.fromFile(file)) }; awaitReady()
        ui {
            assertTrue(api.player!!.output is IExternalMediaSynthOutput)
            assertTrue(api.score!!.tracks.length > 1)
            assertTrue(api.score!!.backingTrack!!.rawAudioFile!!.length > 500000)
        }
        playPause(); await("GP8 OGG clock advances") { api.timePosition > 1500 }; playPause()
        ui { api.timePosition = 30000.0 }
        await("GP8 sync points follow seek") { api.timePosition > 29000 }
        ui { activity.changeSpeed(0.75) }
        playPause(); await("OGG plays at changed speed") { api.playerState == PlayerState.Playing }
        SystemClock.sleep(500)
        ui { assertTrue(api.timePosition > 30000) }
        file.delete()
    }
}
