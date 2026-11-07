package com.stringpage.app

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Private recovery copy. All calls are serialized on the activity's document executor. */
class DraftStore(context: Context) {
    val file = File(context.filesDir, "recovery.gp")
    private val atomic = AtomicFile(file)
    fun exists() = file.isFile || File(file.path + ".bak").isFile
    fun read(): ByteArray = atomic.openRead().use { it.readBytes() }
    fun write(bytes: ByteArray) {
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun delete() = atomic.delete()
}
