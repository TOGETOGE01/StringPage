@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.synth.IExternalMediaHandler
import alphaTab.synth.IExternalMediaSynthOutput
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import java.io.File

/** Owns Android's audio lifecycle, keeping the recording clock as the source of truth. */
class EmbeddedAudio(private val failed: (Throwable) -> Unit) : IExternalMediaHandler {
    private val main = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var ready = false
    private var rate = 1.0
    private var volume = 1.0
    /** The prepared RSE file may contain an anticipatory slide before the first
     * written beat. Seeking that first cue rewinds through its introduction. */
    var firstCueMs = 0.0
        set(value) { require(value.isFinite() && value >= 0);field=value }
    var output: IExternalMediaSynthOutput? = null
        set(value) { field?.handler = null; field = value; value?.handler = this }
    private val poll = object : Runnable {
        override fun run() {
            val current = media ?: return
            if (!ready) return
            output?.updatePosition(current.currentPosition.toDouble())
            main.removeCallbacks(this)
            if (current.isPlaying) main.postDelayed(this, 40)
        }
    }

    fun load(file: File, onReady: () -> Unit) {
        release()
        rate = 1.0
        val next = MediaPlayer()
        media = next
        next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        next.setOnPreparedListener {
            if (media !== next) return@setOnPreparedListener
            ready = true
            onReady()
        }
        next.setOnSeekCompleteListener { if (ready) output?.updatePosition(it.currentPosition.toDouble()) }
        next.setOnCompletionListener {
            if (ready) {
                main.removeCallbacks(poll)
                output?.updatePosition(it.duration.toDouble())
            }
        }
        next.setOnErrorListener { _, what, extra ->
            ready = false; main.removeCallbacks(poll)
            failed(IllegalArgumentException("内嵌音频无法解码（$what/$extra），可切换到合成音源。")); true
        }
        next.setDataSource(file.absolutePath)
        next.prepareAsync()
    }

    override val backingTrackDuration get() = if (ready) media!!.duration.toDouble() else 0.0
    override var playbackRate: Double
        get() = rate
        set(value) {
            rate = value
            val current = media ?: return
            if (!ready) return
            val running = current.isPlaying
            current.playbackParams = PlaybackParams().setSpeed(value.toFloat()).setPitch(1f)
            // setPlaybackParams starts a prepared/paused MediaPlayer on Android.
            if (!running) current.pause()
        }
    override var masterVolume: Double
        get() = volume
        set(value) { volume = value; if (ready) media?.setVolume(value.toFloat(), value.toFloat()) }

    override fun seekTo(time: Double) {
        val target=if(firstCueMs > 0.0 && kotlin.math.abs(time-firstCueMs) < 1.0) 0.0 else time
        if (ready) media?.seekTo(target.coerceIn(0.0, backingTrackDuration).toLong(), MediaPlayer.SEEK_CLOSEST)
    }
    override fun play() {
        if (!ready) return
        media?.start(); main.removeCallbacks(poll); main.post(poll)
    }
    override fun pause() {
        if (ready && media?.isPlaying == true) media?.pause()
        main.removeCallbacks(poll)
    }
    fun release() {
        main.removeCallbacks(poll); ready = false
        firstCueMs = 0.0
        output = null
        media?.release(); media = null
    }
}
