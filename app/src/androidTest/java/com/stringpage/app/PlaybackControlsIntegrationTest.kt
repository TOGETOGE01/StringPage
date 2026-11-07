@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.exporter.Gp7Exporter
import alphaTab.importer.ScoreLoader
import alphaTab.synth.IExternalMediaSynthOutput
import android.content.Intent
import android.os.SystemClock
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackControlsIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var controls: PlaybackControls
    private val api get() = activity.findViewById<AlphaTabView>(R.id.score_view).api
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<T>()
        instrument.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun awaitReady() {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui { activity.findViewById<Button>(R.id.play_pause).isEnabled }) return
            SystemClock.sleep(50)
        }
        fail("Score and audio did not become ready")
    }
    private fun synthesized() {
        ui { activity.switchSource(false) }; awaitReady()
        ui { assertTrue(controls.reapply()) }
    }

    @Before fun launch() {
        activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        ui { activity.openDemo() }; awaitReady()
        ui {
            controls = PlaybackControls(activity, { api },
                { api.player?.output !is IExternalMediaSynthOutput },
                { activity.switchSource(false) })
            controls.reset(api.score!!)
        }
    }
    @After fun close() {
        ui { controls.close(); activity.finish() }
        instrument.waitForIdleSync()
    }

    @Test fun recordingModeRejectsFakeStemAndMetronomeControls() {
        ui {
            val original = controls.snapshot()
            assertFalse(controls.setTrackMute(0, true))
            assertFalse(controls.setTrackSolo(0, true))
            assertFalse(controls.setTrackVolume(0, 0.0))
            assertFalse(controls.setTransposition(7))
            assertFalse(controls.setMetronomeEnabled(true))
            assertFalse(controls.setCountInEnabled(true))
            assertEquals(original, controls.snapshot())
            assertFalse(controls.reapply())
        }
    }

    @Test fun progressiveTrainerChangesActualSynthTempoAtLoopBoundary() {
        synthesized()
        ui {
            assertTrue(activity.applyLoopRange(2, 2))
            assertTrue(activity.startTrainer(ProgressivePractice.Configuration(100, 120, 10, 1)))
            activity.findViewById<Button>(R.id.play_pause).performClick()
        }
        val deadline = SystemClock.uptimeMillis() + 10000
        var reached = false
        while (SystemClock.uptimeMillis() < deadline && !reached) {
            reached = ui { api.playbackSpeed >= 1.19 }
            SystemClock.sleep(60)
        }
        ui { api.pause() }
        assertTrue("Loop completion must drive the real playback speed to the target", reached)
        ui { assertEquals(1.2, api.playbackSpeed, 0.001) }
    }

    @Test fun mixerMetadataRoundTripsWithAudioAndPracticeDoesNotRewriteNotes() {
        synthesized()
        ui {
            val score = api.score!!
            val note = score.tracks[0].staves[0].bars[0].voices[0].beats[0].notes[0]
            val fret = note.fret
            val string = note.string
            val audioLength = score.backingTrack!!.rawAudioFile!!.length
            assertTrue(controls.setTrackVolume(0, 0.5))
            assertTrue(controls.setTrackMute(0, true))
            assertTrue(score.tracks[0].playbackInfo.isMute)
            val mutedFile = ScoreLoader.loadScoreFromBytes(Gp7Exporter().export(score), api.settings)
            assertTrue(mutedFile.tracks[0].playbackInfo.isMute)
            assertEquals(8.0, mutedFile.tracks[0].playbackInfo.volume, 0.001)
            assertEquals(audioLength, mutedFile.backingTrack!!.rawAudioFile!!.length, 0.0)
            assertTrue(controls.setTrackSolo(0, true))
            assertFalse(score.tracks[0].playbackInfo.isMute)
            assertTrue(score.tracks[0].playbackInfo.isSolo)
            val soloFile = ScoreLoader.loadScoreFromBytes(Gp7Exporter().export(score), api.settings)
            assertTrue(soloFile.tracks[0].playbackInfo.isSolo)
            assertFalse(soloFile.tracks[0].playbackInfo.isMute)
            assertTrue(controls.setTransposition(5))
            assertEquals(fret, note.fret, 0.0)
            assertEquals(string, note.string, 0.0)
        }
    }

    @Test fun readyReplayRestoresMetronomeCountInAndTrackStateAcrossSources() {
        synthesized()
        ui {
            controls.setTrackVolume(0, 0.6)
            controls.setTrackMute(0, true)
            controls.setTransposition(-3)
            controls.setMetronomeEnabled(true)
            controls.setMetronomeVolume(0.4)
            controls.setCountInEnabled(true)
            assertEquals(0.4, api.metronomeVolume, 0.001)
            assertEquals(1.0, api.countInVolume, 0.001)
            activity.switchSource(true)
        }
        awaitReady()
        ui { assertFalse(controls.reapply()); activity.switchSource(false) }
        awaitReady()
        ui {
            assertTrue(controls.reapply())
            assertEquals(0.4, api.metronomeVolume, 0.001)
            assertEquals(1.0, api.countInVolume, 0.001)
            assertEquals(-3, controls.snapshot().transposition)
            assertTrue(api.score!!.tracks[0].playbackInfo.isMute)
            assertEquals(9.6, api.score!!.tracks[0].playbackInfo.volume, 0.001)
            // A new imported score resets practice options but keeps its imported mix defaults.
            val next = ScoreLoader.loadScoreFromBytes(Uint8Array(activity.assets.open("morning.gp").use { it.readBytes() }.asUByteArray()), api.settings)
            next.tracks[0].playbackInfo.isSolo = true
            controls.reset(next)
            assertEquals(0, controls.snapshot().transposition)
            assertFalse(controls.snapshot().metronomeEnabled)
            assertFalse(controls.snapshot().countInEnabled)
            assertTrue(controls.snapshot().tracks[0].solo)
        }
    }
}
