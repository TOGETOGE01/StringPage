package com.stringpage.app

import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Mixes clicks on the same PCM frame clock as the amp and factory stems. */
internal class StemMetronome(
    private val clock: AlphaTabMetronomeBridge.Clock,
    private val volume: () -> Double,
    private val sampleRate: Int
) {
    private val tail = FloatArray((sampleRate * 0.025).roundToInt())
    private var speed = 0.0
    private var wave = FloatArray(0)

    fun reset() { clock.mute(); tail.fill(0f) }

    fun mix(samples: FloatArray, frame: Long, count: Int, rate: Double) {
        val gain = volume().toFloat()
        if (gain <= 0f) { reset(); return }
        if (rate != speed) {
            speed = rate
            wave = clickWave(sampleRate, rate)
        }
        for (i in 0 until minOf(count, tail.size)) mixFrame(samples, i, tail[i])
        if (count < tail.size) tail.copyInto(tail, 0, count)
        tail.fill(0f, (tail.size - count).coerceAtLeast(0))

        val start = frame * 1000.0 / sampleRate
        clock.render(start, (frame + count) * 1000.0 / sampleRate, rate) { time, _, beat, _, _ ->
            val offset = ((time - start) * sampleRate / 1000.0).roundToInt().coerceAtLeast(0)
            val level = gain * clickAccent(beat)
            for (i in wave.indices) {
                val dst = offset + i
                val sample = wave[i] * level
                if (dst < count) mixFrame(samples, dst, sample)
                else if (dst - count < tail.size) tail[dst - count] += sample
            }
        }
    }

    private fun mixFrame(samples: FloatArray, frame: Int, sample: Float) {
        val i = frame * 2
        samples[i] = (samples[i] + sample).coerceIn(-1f, 1f)
        samples[i + 1] = (samples[i + 1] + sample).coerceIn(-1f, 1f)
    }
}

/** Triggers one short sample from the decoder clock of an embedded recording. */
internal class RecordingMetronome(
    private val clock: AlphaTabMetronomeBridge.Clock,
    private val volume: () -> Double,
    private val play: (Float) -> Unit
) {
    private var previous: Double? = null

    fun reset() { previous = null; clock.mute() }

    fun prime(position: Double, rate: Double) {
        if (previous == null) previous = position - rate
    }

    fun update(position: Double, rate: Double) {
        val gain = volume().toFloat()
        if (gain <= 0f) { reset(); return }
        val last = previous
        if (last != null && position <= last && last - position <= 20 * rate) return
        // After a seek or delayed callback, discard beats from the old playback interval.
        val start = if (last == null || position < last || position - last > 80 * rate) {
            clock.mute()
            position - 1.0 * rate
        } else last
        var hit: Float? = null
        clock.render(start, position, rate) { _, _, beat, _, _ -> hit = gain * clickAccent(beat) }
        previous = position
        hit?.let(play)
    }
}

internal class RecordingClickSound(private val cacheDir: File) {
    private var pool: SoundPool? = null
    private var sample = 0
    private var ready = false
    private var stream = 0

    fun prepare(): Boolean {
        if (pool != null) return true
        return try {
            val output = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
            pool = output
            output.setOnLoadCompleteListener { owner, id, status ->
                if (pool === owner && sample == id) {
                    if (status == 0) ready = true else release()
                }
            }
            val file = File.createTempFile("practice-click-", ".wav", cacheDir)
            try {
                file.writeBytes(waveFile())
                sample = output.load(file.absolutePath, 1)
                check(sample != 0) { "SoundPool did not load the metronome sample" }
            } finally { file.delete() }
            true
        } catch (error: Exception) {
            release()
            Log.w("StringPageMetronome", "Could not prepare the recording metronome", error)
            false
        }
    }

    private fun waveFile(): ByteArray {
        val rate = 44_100
        val pcm = clickWave(rate, 1.0)
        val bytes = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(pcm.size * 2)
        for (value in pcm) bytes.putShort((value * 32767).roundToInt().toShort())
        return bytes.array()
    }

    fun play(volume: Float) {
        if (ready) stream = pool?.play(sample, volume, volume, 1, 0, 1f) ?: 0
    }

    fun stop() { if (stream != 0) pool?.stop(stream); stream = 0 }

    fun release() {
        stop()
        pool?.release()
        pool = null
        ready = false
        sample = 0
    }
}

private fun clickAccent(beat: Int) = if (beat == 0) 1f else 0.65f

private fun clickWave(sampleRate: Int, speed: Double): FloatArray {
    val size = (sampleRate * 0.017 * speed).roundToInt().coerceAtLeast(2)
    return FloatArray(size) { i ->
        val progress = i.toDouble() / (size - 1)
        (0.32 * sin(2 * PI * 1650 * i / sampleRate) * exp(-6.0 * progress) * (1.0 - progress)).toFloat()
    }
}
