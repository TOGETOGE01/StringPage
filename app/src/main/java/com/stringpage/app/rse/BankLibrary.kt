package com.stringpage.app.rse

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Owns only the app's imported copies; original documents are never changed. */
class BankLibrary(private val directory: File) {
    data class Item(val file: File, val bank: RseSoundBank)
    data class ImportResult(val item: Item, val added: Boolean)
    init { check(directory.isDirectory || directory.mkdirs()) }
    fun list(): List<Item> = directory.listFiles().orEmpty()
        .filter { it.extension == "gp7bank" && it.isFile }
        .mapNotNull { file -> try { Item(file, Gp7Bank.open(file).readMetadata()) } catch (_: Exception) { null } }
        .sortedBy { it.bank.name.lowercase() }

    fun import(input: InputStream, cancelled: () -> Boolean = { false }): ImportResult {
        val temporary = File(directory, ".${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!cancelled() && !Thread.currentThread().isInterrupted) { "已取消导入。" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    bytes += count
                    require(bytes <= 128L * 1024 * 1024) { "单个音色库不能超过 128 MB。" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            val bank = Gp7Bank.open(temporary).readMetadata()
            require(bank.samples.isNotEmpty()) { "该音色库没有可用采样。" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(directory, "$hash.gp7bank")
            if (destination.isFile) return ImportResult(Item(destination, bank), false)
            val used = directory.listFiles().orEmpty().filter { it.extension == "gp7bank" }.sumOf { it.length() }
            require(used + bytes <= 2L * 1024 * 1024 * 1024) { "音色库总容量已达到 2 GB，请先移除部分音色库。" }
            check(!cancelled()) { "已取消导入。" }
            check(temporary.renameTo(destination)) { "无法保存音色库。" }
            return ImportResult(Item(destination, bank), true)
        } finally { temporary.delete() }
    }

    fun remove(item: Item) {
        require(item.file.parentFile?.canonicalFile == directory.canonicalFile && item.file.extension == "gp7bank")
        check(!item.file.exists() || item.file.delete()) { "无法移除音色库。" }
    }
}
