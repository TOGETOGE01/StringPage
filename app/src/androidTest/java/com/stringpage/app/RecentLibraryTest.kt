package com.stringpage.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RecentLibraryTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val prefs get() = context.getSharedPreferences("recent-tests", 0)
    private lateinit var library: RecentLibrary
    private lateinit var directory: File
    @Before fun setup() {
        prefs.edit().clear().commit(); library = RecentLibrary(prefs)
        directory = File(context.cacheDir, "batch-tests-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun cleanup() {
        directory.listFiles()?.forEach { it.delete() }; directory.delete(); prefs.edit().clear().commit()
    }
    private fun gp(name: String): File = File(directory, name).apply {
        instrument.context.assets.open("no-audio.gp").use { input -> outputStream().use { input.copyTo(it) } }
    }

    @Test fun legacyRecordsMigrateAndMalformedEntriesDoNotBreakHistory() {
        prefs.edit().putString("recent", """[{"uri":"content://test/one","name":"Old"},null,{"name":"Missing URI"},{"uri":"content://test/one","name":"Duplicate"},{"uri":"content://test/two","name":"Second"}]""").commit()
        assertEquals(listOf("Old", "Second"), library.entries().map { it.name })
        library.collapsed = true
        assertTrue(RecentLibrary(prefs).collapsed)
        library.addBatch(listOf(RecentLibrary.Entry("content://test/three", "New", "new.gp")))
        assertEquals(listOf("New", "Old", "Second"), RecentLibrary(prefs).entries().map { it.name })
        assertEquals("new.gp", library.entries().first().fileName)
        prefs.edit().putString("recent", "broken JSON").commit()
        assertTrue(library.entries().isEmpty())
    }

    @Test fun importsBeyondTwelveRemainOrderedAndReimportsDoNotDuplicate() {
        val entries = (1..25).map { RecentLibrary.Entry("content://test/$it", "Score $it", "$it.gp") }
        library.addBatch(entries + entries[0])
        assertEquals(25, library.entries().size)
        assertEquals(entries, library.entries())
        val renamed = entries[12].copy(name = "Renamed")
        library.addBatch(listOf(renamed, entries[2]))
        assertEquals(listOf(renamed, entries[2]), library.entries().take(2))
        assertEquals(25, library.entries().size)
    }

    @Test fun removalClearsRestorePointerButNeverDeletesTheOriginalFile() {
        val file = gp("keep-original.gp")
        val bytes = file.readBytes()
        val uri = Uri.fromFile(file).toString()
        library.addBatch(listOf(RecentLibrary.Entry(uri, "Current", file.name), RecentLibrary.Entry("content://test/other", "Other")))
        prefs.edit().putString("lastUri", uri).commit()
        library.remove(setOf(uri))
        assertEquals("Other", library.entries().single().name)
        assertNull(prefs.getString("lastUri", null))
        assertArrayEquals(bytes, file.readBytes())
        library.clear(); assertTrue(library.entries().isEmpty()); assertTrue(file.exists())
    }

    @Test fun batchKeepsValidFilesReportsFailuresAndHandlesMoreThanTwelve() {
        val files = (1..15).map { gp("exercise-$it.GP") }
        val broken = File(directory, "broken.gp").apply { writeText("not a score") }
        val unsupported = gp("wrong-extension.wav")
        val uris = (files + broken + unsupported + files.first()).map { Uri.fromFile(it) }
        val report = GpBatchImporter(context, library).run(uris, false, AtomicBoolean(false)) { _, _, _ -> }
        assertEquals(17, report.total)
        assertEquals(15, report.imported.size)
        assertEquals(setOf("broken.gp", "wrong-extension.wav"), report.failures.map { it.fileName }.toSet())
        assertEquals(files.map { it.name }, library.entries().map { it.fileName })
        assertFalse(report.cancelled)
        assertEquals(0, report.transientGrants)
    }

    @Test fun cancelRetainsCompletedFilesAndDoesNotImportPendingOnes() {
        val files = (1..3).map { gp("cancel-$it.gp") }
        val cancelled = AtomicBoolean(false)
        val report = GpBatchImporter(context, library).run(files.map { Uri.fromFile(it) }, false, cancelled) { done, _, _ ->
            if (done == 1) cancelled.set(true)
        }
        assertTrue(report.cancelled)
        assertEquals(1, report.imported.size)
        assertEquals("cancel-1.gp", library.entries().single().fileName)
        assertTrue(report.failures.isEmpty())
    }

    @Test fun clipDataOnlyAndMixedResultsAreDeduplicatedInSelectionOrder() {
        val first = Uri.parse("content://test/first"); val second = Uri.parse("content://test/second")
        val clip = ClipData.newRawUri("Scores", first).apply { addItem(ClipData.Item(second)); addItem(ClipData.Item(first)) }
        val data = Intent().apply { clipData = clip }
        assertEquals(listOf(first, second), MainActivity.selectedImportUris(data))
        data.data = first
        assertEquals(listOf(first, second), MainActivity.selectedImportUris(data))
        assertEquals(listOf(first), MainActivity.selectedImportUris(Intent().setData(first)))
        assertTrue(MainActivity.selectedImportUris(Intent()).isEmpty())
    }
}
