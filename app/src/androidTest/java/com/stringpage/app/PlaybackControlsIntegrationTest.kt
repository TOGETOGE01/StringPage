@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.exporter.Gp7Exporter
import alphaTab.importer.AlphaTexImporter
import alphaTab.importer.ScoreLoader
import alphaTab.midi.AlphaTabMetronomeEvent
import alphaTab.midi.MidiEventType
import alphaTab.model.Automation
import alphaTab.synth.IExternalMediaSynthOutput
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackControlsIntegrationTest {
    @get:Rule val testName = TestName()
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
        val fixture = if (testName.methodName == "automaticMetronomeChangesTempoAndGroupsCompoundMeter") {
            mixedMeterFixture()
        } else instrument.practiceScoreUri()
        ui { activity.openUri(fixture) }; awaitReady()
        ui {
            controls = PlaybackControls(activity, { api },
                { api.player?.output !is IExternalMediaSynthOutput },
                { activity.switchSource(false) })
            controls.reset(api.score!!)
        }
    }
    @After fun close() {
        ui { if (::controls.isInitialized) controls.close(); activity.finish() }
        instrument.waitForIdleSync()
    }

    @Test fun recordingModeSupportsMetronomeWithoutEnablingStemControls() {
        ui {
            val original = controls.snapshot()
            assertFalse(controls.setTrackMute(0, true))
            assertFalse(controls.setTrackSolo(0, true))
            assertFalse(controls.setTrackVolume(0, 0.0))
            assertFalse(controls.setTransposition(7))
            assertFalse(controls.setCountInEnabled(true))
            assertEquals(original, controls.snapshot())
            assertTrue(controls.setMetronomeEnabled(true))
            assertTrue(controls.setMetronomeVolume(0.35))
            assertTrue(controls.setMetronomeBpm(126))
            assertTrue(controls.setMetronomeRhythm(MetronomeRhythm.Triplet))
            assertEquals(0.35, controls.mediaMetronomeVolume(), 0.001)
            assertEquals(126, controls.snapshot().customMetronomeBpm)
            assertEquals(MetronomeRhythm.Triplet, controls.snapshot().metronomeRhythm)
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
            val next = ScoreLoader.loadScoreFromBytes(Uint8Array(instrument.practiceScoreBytes().asUByteArray()), api.settings)
            next.tracks[0].playbackInfo.isSolo = true
            controls.reset(next)
            assertEquals(0, controls.snapshot().transposition)
            assertFalse(controls.snapshot().metronomeEnabled)
            assertFalse(controls.snapshot().countInEnabled)
            assertTrue(controls.snapshot().tracks[0].solo)
        }
    }

    @Test fun scoreMetronomePlaysTripletsInTheSynthesizer() {
        val events = metronomeClicks(bpm = null, rhythm = MetronomeRhythm.Triplet, speed = 0.6)
        for ((first, second) in events.zipWithNext()) assertEquals(320.0, second.tick - first.tick, 0.01)
        for (event in events) assertEquals(200.0, event.metronomeDurationInMilliseconds, 0.01)
    }

    @Test fun customMetronomeBpmUsesActualPlaybackTimeAtReducedScoreSpeed() {
        val events = metronomeClicks(bpm = 150, rhythm = MetronomeRhythm.Eighth, speed = 0.5)
        assertEquals(0.0, events[0].metronomeNumerator, 0.0)
        for (event in events) assertEquals(200.0, event.metronomeDurationInMilliseconds / 0.5, 0.01)
        // The first beat keeps the same MIDI tempo, so consecutive click ticks are evenly spaced.
        val firstGap = events[1].tick - events[0].tick
        assertTrue(firstGap > 0.0 && firstGap < 480.0)
        assertEquals(firstGap, events[2].tick - events[1].tick, 0.01)
    }

    @Test fun automaticMetronomeChangesTempoAndGroupsCompoundMeter() {
        val clicks = metronomeClicks(null, MetronomeRhythm.Meter, 1.0, count = 5)
        val compound = clicks.first { it.metronomeDurationInTicks == 1440.0 }
        assertEquals(0.0, compound.metronomeNumerator, 0.0)
        assertEquals(1000.0, compound.metronomeDurationInMilliseconds, 0.01)
        assertTrue(clicks.any { it.metronomeDurationInTicks == 960.0 && it.metronomeDurationInMilliseconds == 500.0 })
    }

    private fun mixedMeterFixture(): Uri {
        val score = AlphaTexImporter().run {
            initFromString("""\tempo 120 . 0.1.4 2.1.4 3.1.4 5.1.4 | 7.1.8 5.1.8 3.1.8 2.1.8 3.1.8 5.1.8""", Settings())
            readScore()
        }
        score.masterBars[1].apply {
            timeSignatureNumerator = 6.0
            timeSignatureDenominator = 8.0
            tempoAutomations.push(Automation.buildTempoAutomation(false, 0.0, 90.0, 2.0))
        }
        val file = File(instrument.targetContext.cacheDir, "metronome-meter-test.gp").apply {
            val bytes = Gp7Exporter().export(score)
            outputStream().use { it.write(bytes.buffer.asByteArray(), bytes.byteOffset.toInt(), bytes.length.toInt()) }
        }
        return Uri.fromFile(file)
    }

    private fun metronomeClicks(bpm: Int?, rhythm: MetronomeRhythm, speed: Double, count: Int = 3): List<AlphaTabMetronomeEvent> {
        ui { activity.switchSource(false) }
        awaitReady()
        val events = LinkedBlockingQueue<AlphaTabMetronomeEvent>()
        val stop = ui {
            assertFalse(activity.practice.setMetronomeBpm(19))
            assertTrue(activity.practice.setMetronomeBpm(bpm))
            assertTrue(activity.practice.setMetronomeRhythm(rhythm))
            assertTrue(activity.practice.setMetronomeEnabled(true))
            activity.changeSpeed(speed)
            api.midiEventsPlayedFilter = alphaTab.collections.List<MidiEventType>().apply { push(MidiEventType.AlphaTabMetronome) }
            api.midiEventsPlayed.on { args ->
                for (event in args.events) if (event is AlphaTabMetronomeEvent) events.add(event)
            }.also { activity.findViewById<Button>(R.id.play_pause).performClick() }
        }
        return try {
            List(count) { events.poll(5, TimeUnit.SECONDS) ?: error("Synth did not render click ${it + 1}") }
        } finally {
            ui { stop(); api.pause() }
        }
    }
}
