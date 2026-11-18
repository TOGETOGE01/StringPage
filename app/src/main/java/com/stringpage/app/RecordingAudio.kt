@file:OptIn(ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.core.ecmaScript.Uint8Array
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Validates the selected stream before it enters the score or its undo history. */
internal object RecordingAudio {
    const val MAX_BYTES = 32 * 1024 * 1024

    data class Clip(val bytes: Uint8Array, val durationMs: Long)

    fun read(input: InputStream, cache: File): Clip {
        val file = File.createTempFile("recording-import-", ".audio", cache)
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_BYTES) { "录音文件超过 32 MB。" }
                    output.write(buffer, 0, read)
                }
            }
            val duration = withAudioTrack(file) { source, format ->
                require(source.sampleTime >= 0L) { "录音文件没有可播放的音频。" }
                require(format.containsKey(MediaFormat.KEY_DURATION)) { "无法读取录音时长。" }
                (format.getLong(MediaFormat.KEY_DURATION) / 1000).also {
                    require(it > 0) { "录音文件没有可播放的音频。" }
                }
            }
            return Clip(Uint8Array(file.readBytes().asUByteArray()), duration)
        } finally {
            file.delete()
        }
    }

    fun <T> withAudioTrack(file: File, use: (MediaExtractor, MediaFormat) -> T): T {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd) }
            val index = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("没有找到支持的音频轨道。")
            extractor.selectTrack(index)
            return use(extractor, extractor.getTrackFormat(index))
        } finally {
            extractor.release()
        }
    }

    fun materialize(bytes: Uint8Array, cache: File): File {
        val file = File.createTempFile("embedded-recording-", ".audio", cache)
        try {
            file.outputStream().use { it.write(bytes.buffer.asByteArray(), bytes.byteOffset.toInt(), bytes.length.toInt()) }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
