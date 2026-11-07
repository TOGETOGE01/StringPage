@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/** Run on a document executor. Each document fails independently; successful bookmarks survive cancellation. */
class GpBatchImporter(private val context: Context, private val library: RecentLibrary) {
    data class Failure(val fileName: String, val reason: String)
    data class Report(val total: Int, val imported: List<RecentLibrary.Entry>, val failures: List<Failure>, val transientGrants: Int, val cancelled: Boolean)

    fun run(uris: List<Uri>, persistGrants: Boolean, cancelled: AtomicBoolean, progress: (Int, Int, String) -> Unit): Report {
        val selected = uris.distinctBy { it.toString() }
        val reader = GpDocumentReader(context)
        val resolver = context.contentResolver
        val existingGrants = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri }
        val imported = mutableListOf<RecentLibrary.Entry>()
        val failures = mutableListOf<Failure>()
        var transient = 0
        for ((index, uri) in selected.withIndex()) {
            if (cancelled.get() || Thread.currentThread().isInterrupted) break
            progress(index, selected.size, "正在校验第 ${index + 1} 份乐谱")
            var newGrant = false
            try {
                if (uri.scheme == "content" && uri !in existingGrants && persistGrants) {
                    try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); newGrant = true }
                    catch (_: SecurityException) { /* Some providers offer a transient grant only. */ }
                }
                val entry = reader.validate(uri)
                imported.add(entry)
                if (uri.scheme == "content" && uri !in existingGrants && !newGrant) transient++
                progress(index + 1, selected.size, entry.fileName)
            } catch (error: Throwable) {
                if (newGrant) {
                    try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) {}
                }
                if (error is InterruptedException) { cancelled.set(true); break }
                if (error !is Exception && error !is alphaTab.AlphaTabError) throw error
                val label = runCatching { reader.name(uri) }.getOrDefault("第 ${index + 1} 份乐谱")
                val reason = when (error) {
                    is alphaTab.AlphaTabError -> "乐谱数据不完整或格式暂不支持。"
                    is java.io.FileNotFoundException -> "文件不存在或已被移动。"
                    is SecurityException -> "无法读取文件，请重新选择并授予读取权限。"
                    is IllegalArgumentException -> error.message?.take(100) ?: "文件格式或大小不符合要求。"
                    else -> "读取失败，请检查文件是否完整且仍可访问。"
                }
                failures.add(Failure(label, reason))
                progress(index + 1, selected.size, "$label · 未导入")
            }
        }
        library.addBatch(imported)
        return Report(selected.size, imported, failures, transient, cancelled.get() || Thread.currentThread().isInterrupted)
    }
}
