@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.synth.IExternalMediaSynthOutput
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EmbeddedAudioLifecycleTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private fun <T> ui(action: () -> T): T {
        val value=AtomicReference<T>();instrument.runOnMainSync { value.set(action()) };return value.get()
    }
    @Test fun loopingFromThePhysicalEndResumesWithoutAnotherPlayCommand() {
        val file=File.createTempFile("end-loop-",".wav",instrument.targetContext.cacheDir)
        val samples=16000;val bytes=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        file.writeBytes(bytes.array())
        val ready=AtomicBoolean(false);val failure=AtomicReference<Throwable>()
        val handler=ui { EmbeddedAudio { failure.set(it) }.also { it.load(file) { ready.set(true) } } }
        try {
            val deadline=SystemClock.uptimeMillis()+10000
            while(!ready.get() && failure.get()==null && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
            assertNull(failure.get());assertTrue(ready.get())
            val media=ui { EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }.get(handler) as MediaPlayer }
            var ends=0;var resumed=false;var awaitingRewind=false
            val output=Proxy.newProxyInstance(IExternalMediaSynthOutput::class.java.classLoader,
                arrayOf(IExternalMediaSynthOutput::class.java)) { _, method, args ->
                when(method.name) {
                    "setHandler" -> null
                    "updatePosition" -> {
                        val position=args!![0] as Double
                        // AlphaSynth's looping completion path requests a seek;
                        // it does not send another play command to the handler.
                        // Wait for the actual native completion rather than a
                        // last periodic clock update that could seek early.
                        if(position>=handler.backingTrackDuration && !media.isPlaying && !awaitingRewind) {
                            ends++;awaitingRewind=true;handler.seekTo(0.0)
                        } else if(position<200) awaitingRewind=false
                        if(ends>=1 && !awaitingRewind && position in 250.0..800.0) resumed=true
                        null
                    }
                    else -> error("Unexpected output method ${method.name}")
                }
            } as IExternalMediaSynthOutput
            ui { handler.output=output;handler.play() }
            val loopDeadline=SystemClock.uptimeMillis()+6000
            while(!ui { resumed } && SystemClock.uptimeMillis()<loopDeadline) SystemClock.sleep(20)
            ui { assertTrue("After $ends ends: physical=${media.currentPosition}, nativePlaying=${media.isPlaying}",resumed) }
        } finally { ui { handler.release() };file.delete() }
    }
    @Test fun transientNativePauseDoesNotPermanentlyStopTheRecordingClock() {
        val file=File.createTempFile("recording-clock-",".wav",instrument.targetContext.cacheDir)
        val samples=64000;val bytes=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        file.writeBytes(bytes.array())
        val ready=AtomicBoolean(false);val error=AtomicReference<Throwable>();val lastPosition=AtomicReference(0.0)
        val output=Proxy.newProxyInstance(IExternalMediaSynthOutput::class.java.classLoader,
            arrayOf(IExternalMediaSynthOutput::class.java)) { _, method, args ->
            when(method.name) {
                "setHandler" -> null
                "updatePosition" -> { lastPosition.set(args!![0] as Double);null }
                else -> error("Unexpected output method ${method.name}")
            }
        } as IExternalMediaSynthOutput
        val handler=ui { EmbeddedAudio { error.set(it) }.also { it.load(file) { ready.set(true) } } }
        try {
            val deadline=SystemClock.uptimeMillis()+10000
            while(!ready.get() && error.get()==null && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
            assertNull(error.get());assertTrue(ready.get())
            val field=EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }
            val media=ui { field.get(handler) as MediaPlayer }
            ui { handler.output=output;handler.play() }
            val startDeadline=SystemClock.uptimeMillis()+5000
            while(lastPosition.get()<150 && SystemClock.uptimeMillis()<startDeadline) SystemClock.sleep(20)
            assertTrue("Initial clock must advance",lastPosition.get()>=150)
            // Model the native player's transient stopped state without changing
            // the controller's requested transport state.
            ui { media.pause() }
            SystemClock.sleep(160)
            val held=lastPosition.get()
            ui { media.start() }
            val resumeDeadline=SystemClock.uptimeMillis()+5000
            while(ui { media.currentPosition }<held+250 && SystemClock.uptimeMillis()<resumeDeadline) SystemClock.sleep(20)
            assertTrue("The physical recording must have resumed",ui { media.currentPosition }>=held+250)
            SystemClock.sleep(120)
            assertTrue("Recording clock stopped at $held ms despite physical position ${ui { media.currentPosition }} ms",lastPosition.get()>=held+150)
            ui { handler.pause() }
            val paused=lastPosition.get();SystemClock.sleep(160)
            assertEquals("Controller pause must stop periodic clock notifications",paused,lastPosition.get(),0.0)
        } finally { ui { handler.release() };file.delete() }
    }
    @Test fun changingSpeedWhilePausedKeepsTheActualAudioPositionAndAppliesTheRateWhenPlaying() {
        val file=File.createTempFile("paused-speed-",".wav",instrument.targetContext.cacheDir)
        val samples=32000;val bytes=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        file.writeBytes(bytes.array())
        val ready=AtomicBoolean(false);val error=AtomicReference<Throwable>()
        val handler=ui { EmbeddedAudio { error.set(it) }.also { it.load(file) { ready.set(true) } } }
        try {
            val deadline=SystemClock.uptimeMillis()+10000
            while(!ready.get() && error.get()==null && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
            assertNull(error.get());assertTrue(ready.get())
            val field=EmbeddedAudio::class.java.getDeclaredField("media").apply { isAccessible=true }
            val media=ui { field.get(handler) as MediaPlayer }
            val start=ui { media.currentPosition }
            val positions=ArrayList<Int>()
            for(i in 0 until 80) ui {
                handler.playbackRate=listOf(.5,1.25,.75,1.0)[i%4]
                assertFalse("Changing speed must preserve the paused state",media.isPlaying)
                positions.add(media.currentPosition)
            }
            val after=ui { media.currentPosition }
            assertTrue("Paused speed changes moved the physical clock from $start to $after ms; max=${positions.maxOrNull()}",positions.all { kotlin.math.abs(it-start)<=20 })
            ui { handler.playbackRate=1.25;handler.play() }
            val playDeadline=SystemClock.uptimeMillis()+5000
            while(ui { media.currentPosition }<start+100 && SystemClock.uptimeMillis()<playDeadline) SystemClock.sleep(20)
            ui {
                assertTrue(media.currentPosition>=start+100);assertTrue(media.isPlaying)
                assertEquals(1.25f,media.playbackParams.speed,0f);assertEquals(1f,media.playbackParams.pitch,0f)
                handler.pause()
            }
        } finally { ui { handler.release() };file.delete() }
    }
}
