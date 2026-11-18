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
    private var appliedRate = 1.0
    private var volume = 1.0
    private var pendingSeek: Long? = null
    private var queuedSeek: Long? = null
    private val dispatchSeek = Runnable {
        val current = media ?: return@Runnable
        val target = queuedSeek ?: return@Runnable
        if (!ready || pendingSeek != null) return@Runnable
        queuedSeek = null
        if (current.currentPosition.toLong() == target) publishSeek(current)
        else {
            pendingSeek = target
            current.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        }
    }
    internal val currentPositionMs: Double?
        get() = if (ready) (queuedSeek ?: pendingSeek ?: media!!.currentPosition.toLong()).toDouble() else null
    internal var onSeekSettled: (() -> Unit)? = null

    /** Preserve the recording clock when the notation engine snaps a pause to a beat. */
    internal fun restorePausedPosition(time: Double) = requestSeek(time)
    var output: IExternalMediaSynthOutput? = null
        set(value) { field?.handler = null; field = value; value?.handler = this }
    private val poll = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val current = media ?: return
            if (!ready || !playRequested) return
            if (pendingSeek == null && queuedSeek == null) output?.updatePosition(current.currentPosition.toDouble())
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
        appliedRate = 1.0
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
            pendingSeek = null
            if (queuedSeek != null) main.post(dispatchSeek) else publishSeek(next)
        }
        next.setOnCompletionListener {
            if (media === next && ready && pendingSeek == null && queuedSeek == null) {
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

    override fun seekTo(time: Double) = requestSeek(time)
    private fun requestSeek(time: Double) {
        if (!ready || media == null) return
        val target = time.coerceIn(0.0, backingTrackDuration).toLong()
        // Source restoration updates sync points, the loop and the playhead
        // in one UI turn. Only seek the native decoder to the final position.
        queuedSeek = target.takeUnless { it == pendingSeek }
        if (pendingSeek == null) {
            main.removeCallbacks(dispatchSeek)
            main.post(dispatchSeek)
        }
    }
    private fun publishSeek(current: MediaPlayer) {
        output?.updatePosition(current.currentPosition.toDouble())
        if (media === current && ready && pendingSeek == null && queuedSeek == null) onSeekSettled?.invoke()
        // The controller loops by seeking without a separate play command.
        if (media === current && ready && playRequested) startPlayback(current)
    }
    override fun play() {
        if (!ready) return
        val current=media ?: return
        playRequested = true
        startPlayback(current)
    }
    private fun startPlayback(current: MediaPlayer) {
        if (pendingSeek != null || queuedSeek != null) return
        applyRate(current)
        if (!current.isPlaying) current.start()
        main.removeCallbacks(poll)
        main.post(poll)
    }
    private fun applyRate(current: MediaPlayer) {
        if (appliedRate == rate) return
        current.playbackParams = PlaybackParams().setSpeed(rate.toFloat()).setPitch(1f)
        appliedRate = rate
    }
    override fun pause() {
        playRequested = false
        if (ready && media?.isPlaying == true) media?.pause()
        main.removeCallbacks(poll)
    }
    fun release() {
        main.removeCallbacks(poll); main.removeCallbacks(dispatchSeek); ready = false; playRequested = false
        pendingSeek = null; queuedSeek = null
        output = null
        media?.release(); media = null
    }
}
