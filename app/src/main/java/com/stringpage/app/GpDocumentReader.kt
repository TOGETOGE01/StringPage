@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Reads one document at a time so a batch never keeps every score and audio file in memory. */
class GpDocumentReader(private val context: Context) {
    fun name(uri: Uri): String = (if (uri.scheme == "file") File(uri.path ?: "").name else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }).orEmpty().ifBlank { "本地乐谱" }

    fun read(uri: Uri, requireGpExtension: Boolean = false): Pair<String, ByteArray> {
        val label = name(uri)
        if (requireGpExtension) require(label.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS) {
            "不是支持的 GP 文件（GP3/4/5/GPX/GP/GTP）。"
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MainActivity.MAX_FILE) { "文件超过 128 MB。" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("文件不可读取，请重新选择文件。")
        return label to bytes
    }

    fun validate(uri: Uri): RecentLibrary.Entry {
        val (label, bytes) = read(uri, true)
        val score = ScoreLoader.loadScoreFromBytes(Uint8Array(bytes.asUByteArray()), Settings())
        require(score.tracks.length > 0 && score.masterBars.length > 0) { "文件中没有可读取的乐谱。" }
        return RecentLibrary.Entry(uri.toString(), score.title.ifBlank { label.substringBeforeLast('.') }.take(512), label)
    }

    companion object { private val EXTENSIONS = setOf("gp", "gpx", "gp3", "gp4", "gp5", "gtp") }
}
