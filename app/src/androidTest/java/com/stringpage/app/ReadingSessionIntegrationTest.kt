@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.AlphaTabView
import alphaTab.Settings
import alphaTab.StaveProfile
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.synth.IExternalMediaSynthOutput
import alphaTab.synth.PlayerState
import android.content.Intent
import android.net.Uri
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class ReadingSessionIntegrationTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var file: File
    private val api get()=activity.findViewById<AlphaTabView>(R.id.score_view).api
    private fun <T> ui(action: () -> T): T {
        val result=AtomicReference<T>();instrument.runOnMainSync { result.set(action()) };return result.get()
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<deadline) {
            if(ui(condition)) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $description")
    }
    private fun ready()=await("score and playback ready") { activity.findViewById<Button>(R.id.play_pause).isEnabled }
    private fun selectSecondTrack() {
        MainActivity::class.java.getDeclaredMethod("selectTrack",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,1)
    }
    private fun recreate() {
        val previous=activity
        ui { previous.recreate() }
        await("replacement activity") {
            val next=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { it!==previous }
            if(next!=null) { activity=next;true } else false
        }
        ready()
    }
    private fun nativePosition(): Int {
        val handler=(api.player!!.output as IExternalMediaSynthOutput).handler as EmbeddedAudio
        val media=EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }.get(handler) as MediaPlayer
        return media.currentPosition
    }
    @Before fun launch() {
        activity=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        file=File.createTempFile("reading-session-",".gp",instrument.targetContext.cacheDir)
        val raw=instrument.targetContext.assets.open("morning.gp").use { it.readBytes() }
        val score=ScoreLoader.loadScoreFromBytes(Uint8Array(raw.asUByteArray()),Settings())
        val second=ScoreLoader.loadScoreFromBytes(Uint8Array(raw.asUByteArray()),Settings()).tracks[0]
        second.name="Second part";second.playbackInfo.primaryChannel=1.0;second.playbackInfo.secondaryChannel=2.0
        val firstVoice=second.staves[0].bars[0].voices[0]
        firstVoice.beats=alphaTab.collections.List()
        firstVoice.addBeat(alphaTab.model.Beat().apply { duration=alphaTab.model.Duration.Whole })
        score.addTrack(second);score.finish(Settings())
        file.writeBytes(ScoreExport.guitarProBytes(score))
        ui { activity.openUri(Uri.fromFile(file)) };ready()
    }
    @After fun close() {
        ui { activity.finish() };instrument.waitForIdleSync();file.delete()
    }
    @Test fun switchingAudioSourcesPreservesTheSelectedPassPositionAndLoop() {
        ui {
            activity.changeSpeed(.73);api.masterVolume=.35;selectSecondTrack()
            assertTrue(activity.applyLoopRange(4,6));api.tickPosition=12800.0
        }
        await("initial seek") { kotlin.math.abs(api.tickPosition-12800.0)<20 }
        val range=ui { api.playbackRange!!.let { it.startTick to it.endTick } }
        ui { activity.switchSource(false) };ready()
        ui {
            assertFalse(api.player!!.output is IExternalMediaSynthOutput)
            assertEquals("Switching to synthesized playback lost the practice position",12800.0,api.tickPosition,20.0)
            assertEquals(.73,api.playbackSpeed,0.0);assertEquals(.35,api.masterVolume,0.0001)
            assertEquals(1,api.tracks[0].index.toInt())
            assertTrue("Switching sources must preserve looping",api.isLooping)
            assertEquals(range.first,api.playbackRange!!.startTick,0.0)
            assertEquals(range.second,api.playbackRange!!.endTick,0.0)
            assertEquals(PlayerState.Paused,api.playerState)
            activity.switchSource(true)
        }
        ready();await("recording returns to the same score position") { kotlin.math.abs(api.tickPosition-12800.0)<20 }
        ui {
            assertTrue(api.isLooping)
            assertEquals(.73, api.playbackSpeed, 0.0)
            assertEquals(PlayerState.Paused, api.playerState)
        }
    }
    @Test fun recreatingTheScreenRestoresTheReadingAndPracticeContextWithoutAutoplay() {
        ui { activity.switchSource(false) };ready()
        ui {
            selectSecondTrack();activity.changeSpeed(.75)
            api.settings.display.scale=1.15;api.settings.display.staveProfile=StaveProfile.Tab
            api.updateSettings();api.render()
            assertTrue(activity.applyLoopRange(3,5));api.tickPosition=9000.0
            activity.findViewById<Button>(R.id.full_screen).performClick()
        }
        await("initial reading position") { kotlin.math.abs(api.tickPosition-9000.0)<20 }
        recreate()
        ui {
            assertFalse("Screen recreation changed the selected audio source",api.player!!.output is IExternalMediaSynthOutput)
            assertEquals("Screen recreation lost the selected track",1,api.tracks[0].index.toInt())
            assertEquals(.75,api.playbackSpeed,0.0)
            assertEquals(1.15,api.settings.display.scale,0.0);assertEquals(StaveProfile.Tab,api.settings.display.staveProfile)
            assertTrue(api.isLooping);assertNotNull(api.playbackRange)
            assertEquals("Screen recreation lost the practice position",9000.0,api.tickPosition,20.0)
            assertEquals(View.GONE,activity.findViewById<View>(R.id.library_panel).visibility)
            assertEquals("退出全屏",activity.findViewById<Button>(R.id.full_screen).text.toString())
            assertEquals("The screen must reopen paused",PlayerState.Paused,api.playerState)
        }
    }
    @Test fun recreatingEmbeddedAudioRestoresTheActualRecordingPositionAfterPreparation() {
        ui { activity.changeSpeed(.73);api.masterVolume=.4;selectSecondTrack();api.tickPosition=12800.0 }
        var previous=-1;var stable=0
        await("initial physical recording seek settles") {
            val position=nativePosition()
            stable=if(position>0 && position==previous && kotlin.math.abs(api.tickPosition-12800.0)<20) stable+1 else 0
            previous=position;stable>=3
        }
        val position=ui { nativePosition() }
        recreate()
        await("physical recording position restored") { kotlin.math.abs(nativePosition()-position)<30 && kotlin.math.abs(api.tickPosition-12800.0)<20 }
        ui {
            assertTrue(api.player!!.output is IExternalMediaSynthOutput)
            assertEquals(.73,api.playbackSpeed,0.0);assertEquals(.4,api.masterVolume,0.0001)
            assertEquals(1,api.tracks[0].index.toInt());assertEquals(PlayerState.Paused,api.playerState)
        }
        val held=ui { nativePosition() };SystemClock.sleep(200)
        ui { assertTrue("Restoring the screen must not start the recording",kotlin.math.abs(held-nativePosition())<=20) }
    }
    @Test fun recreatingTheBuiltInExerciseKeepsItsPositionWithoutAFileUri() {
        ui { activity.openDemo() };ready()
        ui { activity.changeSpeed(.5);api.tickPosition=4200.0 }
        await("exercise seek") { kotlin.math.abs(api.tickPosition-4200.0)<20 }
        recreate()
        await("exercise position restored") { kotlin.math.abs(api.tickPosition-4200.0)<20 }
        ui { assertEquals("Morning Strings",api.score!!.title);assertEquals(.5,api.playbackSpeed,0.0);assertEquals(PlayerState.Paused,api.playerState) }
    }
    @Test fun pausingRecordingInsideAWholeBarRestPreservesThePhysicalAndScorePosition() {
        ui { selectSecondTrack();activity.findViewById<Button>(R.id.play_pause).performClick() }
        await("recording reaches the middle of a whole-bar rest") { nativePosition()>900 && api.timePosition>900 }
        val before=ui {
            val raw=nativePosition();activity.findViewById<Button>(R.id.play_pause).performClick();raw
        }
        SystemClock.sleep(250)
        ui {
            assertEquals(PlayerState.Paused,api.playerState)
            assertTrue("Pause rewound the recording: $before -> ${nativePosition()} ms",kotlin.math.abs(before-nativePosition())<=50)
            assertEquals("Pause rewound the score clock",before.toDouble(),api.timePosition,50.0)
        }
    }
    @Test fun switchingSourceWhileRecordingPlaysPreservesThePositionBeforePauseSnapping() {
        ui { selectSecondTrack();activity.findViewById<Button>(R.id.play_pause).performClick() }
        await("recording advances") { nativePosition()>900 && api.timePosition>900 }
        val before=ui { val tick=api.tickPosition;activity.switchSource(false);tick }
        ready()
        ui {
            assertEquals(PlayerState.Paused,api.playerState)
            assertEquals("Source change must preserve the pre-pause position",before,api.tickPosition,40.0)
        }
    }
}
