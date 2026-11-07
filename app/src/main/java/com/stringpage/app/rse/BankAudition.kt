package com.stringpage.app.rse

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import kotlin.math.pow

object BankAudition {
    /** Runs on a worker. Never exports decoded recordings; cancellation also stops queued playback. */
    fun play(file: File, wave: RseWave, semitones: Int, cancelled: () -> Boolean): RseAudioMetadata {
        val bank = Gp7Bank.open(file)
        val metadata = bank.readAudioMetadata(wave.entry)
        val raw = BankSampleDecoder.decode(file, wave.entry.offset, wave.entry.length, cancelled)
        require(raw.sampleRate.toLong() == metadata.sampleRate) { "采样解码后的采样率与音色库不一致。" }
        val audio = NativeSourceRateConverter.normalize(raw, cancelled)
        val loop = NativeStreamPolicy.forwardLoop(wave.file, metadata.sampler?.loops.orEmpty(), audio.frames, raw.sampleRate)
        val rate = 44100
        // Native repitch correction is stored in cents (native parser multiplies by .01).
        val gain = (0.2 * 10.0.pow(wave.gain / 20.0)).coerceIn(0.0, 1.0).toFloat()
        val voice = SampleVoice(audio, rate, semitones + wave.repitch / 100.0, gain,
            loop?.start?.toInt(), loop?.end?.toInt())
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0)
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(minimum, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            check(!cancelled()) { "已停止试听。" }
            track.play()
            val block = FloatArray(1024)
            val holdFrames = rate * 2
            val releaseFrames = rate / 4
            val total = holdFrames + releaseFrames
            var rendered = 0
            while (rendered < total && !voice.finished && !cancelled()) {
                val frames = minOf(block.size / 2, total - rendered,
                    if (rendered < holdFrames) holdFrames - rendered else total - rendered)
                voice.render(block, frames, rendered < holdFrames)
                for (frame in 0 until frames) {
                    val envelope = if (rendered + frame < holdFrames) 1f
                        else (1f - (rendered + frame - holdFrames).toFloat() / releaseFrames).coerceIn(0f, 1f)
                    for (channel in 0..1) block[frame * 2 + channel] = (block[frame * 2 + channel] * envelope).coerceIn(-1f, 1f)
                }
                var written = 0
                while (written < frames * 2 && !cancelled()) {
                    val count = track.write(block, written, frames * 2 - written, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "音频输出已中断。" }; written += count
                }
                rendered += frames
            }
            // Wait for the final short release to reach the device, while remaining cancellable.
            val drainDeadline = android.os.SystemClock.elapsedRealtime() + 2000
            while (!cancelled() && android.os.SystemClock.elapsedRealtime() < drainDeadline &&
                (track.playbackHeadPosition.toLong() and 0xffffffffL) < rendered) Thread.sleep(5)
        } finally {
            try { track.pause(); track.flush(); track.stop() } catch (_: Exception) { }
            track.release()
        }
        return metadata
    }
}
