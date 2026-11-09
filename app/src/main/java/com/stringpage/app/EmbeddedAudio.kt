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
    private var playRequested = false
    private var rate = 1.0
    private var volume = 1.0
    private var pendingSeek: Long? = null
    private var queuedSeek: Long? = null
    internal val currentPositionMs: Double?
        get() = if (ready) (queuedSeek ?: pendingSeek ?: media!!.currentPosition.toLong()).toDouble() else null

    /** Preserve the recording clock when the notation engine snaps a pause to a beat. */
    internal fun restorePausedPosition(time: Double) = requestSeek(time)
    /** The prepared RSE file may contain an anticipatory slide before the first
     * written beat. Seeking that first cue rewinds through its introduction. */
    var firstCueMs = 0.0
        set(value) { require(value.isFinite() && value >= 0);field=value }
    var output: IExternalMediaSynthOutput? = null
        set(value) { field?.handler = null; field = value; value?.handler = this }
    private val poll = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val current = media ?: return
            if (!ready || !playRequested) return
            if (pendingSeek == null) output?.updatePosition(current.currentPosition.toDouble())
            // Native start/seek/buffering can temporarily report not playing.
            // Keep the clock alive until the controller pauses or completes.
            // Position notification can synchronously release or replace us.
            if (media === current && ready && playRequested) {
                main.removeCallbacks(this)
                main.postDelayed(this, 40)
            }
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
        next.setOnSeekCompleteListener {
            if (media !== next || !ready) return@setOnSeekCompleteListener
            val queued = queuedSeek
            pendingSeek = null; queuedSeek = null
            if (queued != null) {
                requestSeek(queued.toDouble())
                return@setOnSeekCompleteListener
            }
            output?.updatePosition(next.currentPosition.toDouble())
            // The controller loops by seeking, without sending another play
            // command. A completed native player needs an explicit restart.
            if (media === next && ready && playRequested) startPlayback(next)
        }
        next.setOnCompletionListener {
            if (media === next && ready && pendingSeek == null) {
                main.removeCallbacks(poll)
                output?.updatePosition(it.duration.toDouble())
            }
        }
        next.setOnErrorListener { _, what, extra ->
            if (media !== next) return@setOnErrorListener true
            ready = false; playRequested = false; main.removeCallbacks(poll)
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
            // setPlaybackParams starts a prepared/paused MediaPlayer. Pausing
            // immediately afterwards still advances its physical clock, so
            // defer paused changes until the user actually starts playback.
            if (current.isPlaying) applyRate(current)
        }
    override var masterVolume: Double
        get() = volume
        set(value) { volume = value; if (ready) media?.setVolume(value.toFloat(), value.toFloat()) }

    override fun seekTo(time: Double) {
        val target=if(firstCueMs > 0.0 && kotlin.math.abs(time-firstCueMs) < 1.0) 0.0 else time
        requestSeek(target)
    }
    private fun requestSeek(time: Double) {
        if (!ready) return
        val current = media ?: return
        val target = time.coerceIn(0.0, backingTrackDuration).toLong()
        // MediaPlayer may complete an older seek after a newer request. Keep
        // only the latest destination and never publish the intermediate clock.
        if (pendingSeek != null) { queuedSeek = target; return }
        pendingSeek = target
        current.seekTo(target, MediaPlayer.SEEK_CLOSEST)
    }
    override fun play() {
        if (!ready) return
        val current=media ?: return
        playRequested = true
        startPlayback(current)
    }
    private fun startPlayback(current: MediaPlayer) {
        if (pendingSeek != null) return
        applyRate(current);current.start(); main.removeCallbacks(poll); main.post(poll)
    }
    private fun applyRate(current: MediaPlayer) {
        current.playbackParams=PlaybackParams().setSpeed(rate.toFloat()).setPitch(1f)
    }
    override fun pause() {
        playRequested = false
        if (ready && media?.isPlaying == true) media?.pause()
        main.removeCallbacks(poll)
    }
    fun release() {
        main.removeCallbacks(poll); ready = false; playRequested = false
        pendingSeek = null; queuedSeek = null
        firstCueMs = 0.0
        output = null
        media?.release(); media = null
    }
}
