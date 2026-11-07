package com.stringpage.app.rse

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONObject

/** All fixtures are generated here; no installed bank/audio data is bundled. */
@RunWith(AndroidJUnit4::class)
class Gp7BankTest {
    private fun u32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    private fun u64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private val xml = """<SoundBank id="test" name="Fixture" type="steelGuitar" description="" tuning="40 45 50 55 59 64"><SamplesTree><Node class="pick"><Node class="sustain"><Samples><Sample midi="40" string="1" dynamic="mf" option="none"><Wave pickup="none" file="note.ogg" repitch="0.5" gain="-1.0"/></Sample></Samples></Node></Node></SamplesTree></SoundBank>"""
    private fun chunk(tag: String, payload: ByteArray) = ascii(tag) + u32(payload.size.toLong()) + payload
    private fun tag(tag: String, payload: ByteArray) = tag + "=" + Base64.getEncoder().encodeToString(chunk(tag, payload))
    private fun page(packets: List<ByteArray>): ByteArray {
        val lacing = packets.flatMap { packet -> List(packet.size / 255) { 255.toByte() } + listOf((packet.size % 255).toByte()) }.toByteArray()
        return ascii("OggS") + byteArrayOf(0, 2) + ByteArray(8) + u32(42) + u32(0) + u32(0) + byteArrayOf(lacing.size.toByte()) + lacing + packets.fold(ByteArray(0)) { a, b -> a + b }
    }
    private fun audio(tags: List<String> = listOf(
        tag("cue ", u32(1) + u32(7) + u32(90) + ascii("data") + u32(0) + u32(0) + u32(90)),
        tag("inst", byteArrayOf(40, -2, -3, 0, 127, 0, 127)),
        tag("smpl", listOf(0L,0,22675,40,0,0,0,1,0,1,0,100,200,0,0).fold(ByteArray(0)) { a,b -> a + u32(b) }),
        tag("LIST", ascii("adtl") + chunk("labl", u32(7) + ascii("TAP") + byteArrayOf(0)))
    )): ByteArray {
        val identification = byteArrayOf(1) + ascii("vorbis") + u32(0) + byteArrayOf(2) + u32(44100) + ByteArray(12) + byteArrayOf(0x88.toByte(),1)
        fun text(value: String): ByteArray { val b=value.toByteArray(); return u32(b.size.toLong()) + b }
        val comments = byteArrayOf(3) + ascii("vorbis") + text("synthetic") + u32(tags.size.toLong()) + tags.fold(ByteArray(0)) { a,b -> a + text(b) } + byteArrayOf(1)
        // The third packet is deliberately irrelevant. Reader must stop before it.
        return page(listOf(identification, comments, byteArrayOf(99, 98, 97)))
    }
    private fun archive(xmlText: String = xml, audioBytes: ByteArray = audio(), name: String = "note.ogg"): ByteArray {
        val members = listOf("Samples.xml" to xmlText.toByteArray(), name to audioBytes)
        val indexLength = members.sumOf { 12 + it.first.toByteArray().size + 1 }
        var position = 25L + indexLength
        val index = members.fold(ByteArray(0)) { a,(n,b) ->
            (a + u64(position) + u32(b.size.toLong()) + n.toByteArray() + byteArrayOf(0)).also { position += b.size }
        }
        return ascii("AMARCHIVE") + u32(1) + u32(2) + u32(indexLength.toLong()) + u32(25) + index + members.fold(ByteArray(0)) { a, b -> a + b.second }
    }
    private fun <T> withBank(bytes: ByteArray, block: (File) -> T): T {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("synthetic-bank-", ".gp7bank", dir)
        return try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
    private fun rejects(bytes: ByteArray, metadata: Boolean = false, audio: Boolean = false) {
        withBank(bytes) { file ->
            try {
                val bank = Gp7Bank.open(file)
                if (metadata) bank.readMetadata()
                if (audio) bank.readAudioMetadata(bank.index.entry("note.ogg"))
                fail("Expected bounded parser rejection")
            } catch (_: BankFormatException) { }
        }
    }
    @Test fun readsIndexCoordinatesWaveCorrectionsAndEmbeddedCuesLoops() {
        withBank(archive()) { file ->
            val bank = Gp7Bank.open(file); val metadata = bank.readMetadata(); val sample = metadata.samples.single()
            assertEquals(listOf(40,45,50,55,59,64), metadata.tuning)
            assertEquals(listOf("pick","sustain"), sample.nodePath)
            assertEquals(40, sample.midi); assertEquals(1, sample.string)
            assertEquals(0.5, sample.waves.single().repitch, 0.0)
            val audio = bank.readAudioMetadata(sample.waves.single().entry)
            assertEquals(2, audio.channels); assertEquals(44100L, audio.sampleRate)
            assertEquals(90L, audio.cues.single().sampleOffset)
            assertEquals(-2, audio.instrument!!.fineTune)
            assertEquals(-3, audio.instrument!!.gain)
            assertEquals(100L, audio.sampler!!.loops.single().start)
            assertEquals(200L, audio.sampler!!.loops.single().end)
            assertEquals(RseLabel(7,"TAP"), audio.labels.single())
            assertEquals(bank.index.entry("note.ogg").length - 3, audio.headerBytesRead.toLong())
        }
    }
    @Test fun rejectsHeaderAndIntegerBoundsBeforeAllocating() {
        rejects(byteArrayOf(1,2,3))
        rejects(archive().also { it[0]=0 })
        rejects(archive().also { u32(0xffffffffL).copyInto(it,13) })
        rejects(archive().also { u32(0xffffffffL).copyInto(it,17) })
        rejects(archive().also { u64(Long.MAX_VALUE).copyInto(it,25) })
        rejects(archive().also { u32(0xffffffffL).copyInto(it,33) })
    }
    @Test fun rejectsOverlapDuplicateTraversalAndInvalidNames() {
        val bytes=archive(); val second=25 + 12 + "Samples.xml".length + 1
        rejects(bytes.copyOf().also { u64(25L + 12*2 + "Samples.xml".length + "note.ogg".length + 2).copyInto(it,second) })
        rejects(archive(name="../note.ogg"))
        rejects(archive(name="Samples.xml"))
        rejects(bytes.copyOf().also { it[37]=0xff.toByte() })
        rejects(bytes.copyOfRange(0,second + 12 + "note.ogg".length))
    }
    @Test fun rejectsEntityExpansionMissingReferencesAndNonfiniteCorrections() {
        rejects(archive(xmlText="<!DOCTYPE SoundBank [<!ENTITY a 'bad'>]>"+xml), metadata=true)
        rejects(archive(xmlText=xml.replace("note.ogg","missing.ogg")), metadata=true)
        rejects(archive(xmlText=xml.replace("gain=\"-1.0\"","gain=\"NaN\"")), metadata=true)
        rejects(archive(xmlText=xml.replace("midi=\"40\"","midi=\"128\"")), metadata=true)
    }
    @Test fun rejectsTruncatedOggPacketsInvalidBase64AndRiffLengths() {
        rejects(archive(audioBytes=byteArrayOf(1,2,3)), audio=true)
        rejects(archive(audioBytes=audio(listOf("smpl=invalid!"))), audio=true)
        rejects(archive(audioBytes=audio(listOf("smpl="+Base64.getEncoder().encodeToString(ascii("smpl")+u32(999))))), audio=true)
        rejects(archive(audioBytes=audio(listOf(tag("smpl", ByteArray(4))))), audio=true)
    }
    @Test fun preservesUnsupportedListDiagnosticsAlongsideValidCues() {
        withBank(archive(audioBytes=audio(listOf(tag("LIST", ascii("INFO") + chunk("ICMT",ascii("fixture"))), tag("cue ",u32(0)))))) { file ->
            val bank=Gp7Bank.open(file); val metadata=bank.readAudioMetadata(bank.index.entry("note.ogg"))
            assertEquals(1, metadata.uninterpretedLists.size)
            assertTrue(metadata.cues.isEmpty())
            assertTrue(metadata.labels.isEmpty())
        }
    }
    @Test fun nativeCueKeysAreCaseSensitiveAndLastCanonicalChunkWinsIncludingShortArrays() {
        val rows=InstrumentationRegistry.getInstrumentation().context.assets.open("native-cue-keys.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("rows")
        assertEquals(13,rows.length())
        repeat(rows.length()) { index ->
            val row=rows.getJSONObject(index);val comments=row.getJSONArray("comments")
            val tags=(0 until comments.length()).map { at ->
                val comment=comments.getJSONObject(at);val offsets=comment.getJSONArray("offsets")
                val payload=u32(offsets.length().toLong())+(0 until offsets.length()).fold(ByteArray(0)) { a,i ->
                    a+u32(i.toLong())+u32(0)+ascii("data")+u32(0)+u32(0)+u32(offsets.getLong(i)) }
                comment.getString("key")+"="+Base64.getEncoder().encodeToString(chunk("cue ",payload))
            }
            withBank(archive(audioBytes=audio(tags))) { file ->
                val bank=Gp7Bank.open(file);val metadata=bank.readAudioMetadata(bank.index.entry("note.ogg"))
                val offsets=metadata.nativeCues.map { it.sampleOffset.toInt().toLong() }
                repeat(3) { cue -> assertEquals(row.getString("name"),row.getJSONArray("cues").getLong(cue),
                    NativeSampleStart.decoderCuePoint(offsets,cue)) }
                // Inspection remains permissive, independently of playback.
                assertEquals(comments.getJSONObject(0).getJSONArray("offsets").length(),metadata.cues.size)
            }
        }
    }
    @Test fun detectsBankSizeChangesBetweenIndexAndMetadata() {
        withBank(archive()) { file ->
            val bank=Gp7Bank.open(file); file.appendBytes(byteArrayOf(0))
            try { bank.readMetadata(); fail("Expected changed bank rejection") } catch (_: BankFormatException) { }
        }
    }

    private fun <T> withLibrary(block: (BankLibrary, File) -> T): T {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File.createTempFile("synthetic-bank-library-", ".tmp", cache)
        check(directory.delete() && directory.mkdir())
        return try { block(BankLibrary(directory), directory) } finally {
            directory.listFiles().orEmpty().forEach { check(it.delete()) }
            check(directory.delete())
        }
    }

    private fun assertNoPartialFiles(directory: File) {
        assertTrue("Import left a .part file", directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun libraryImportsPrivateCopyWithoutChangingOriginalBytesOrFile() {
        val bytes = archive(); val original = bytes.copyOf()
        withBank(bytes) { source ->
            withLibrary { library, directory ->
                val result = source.inputStream().use { library.import(it) }
                assertTrue(result.added)
                assertEquals("test", result.item.bank.id)
                assertEquals(directory.canonicalFile, result.item.file.parentFile?.canonicalFile)
                assertNotEquals(source.canonicalFile, result.item.file.canonicalFile)
                assertArrayEquals(original, result.item.file.readBytes())
                assertArrayEquals(original, source.readBytes())
                assertArrayEquals(original, bytes)
                assertEquals(listOf(result.item.file), library.list().map { it.file })
                assertNoPartialFiles(directory)
            }
        }
    }

    @Test fun libraryDeduplicatesIdenticalImportsToOneCopy() {
        val bytes = archive(); val original = bytes.copyOf()
        withBank(bytes) { source ->
            withLibrary { library, directory ->
                val first = source.inputStream().use { library.import(it) }
                val second = bytes.inputStream().use { library.import(it) }
                assertTrue(first.added)
                assertFalse(second.added)
                assertEquals(first.item.file.canonicalFile, second.item.file.canonicalFile)
                assertEquals(1, library.list().size)
                assertEquals(1, directory.listFiles().orEmpty().size)
                assertArrayEquals(original, first.item.file.readBytes())
                assertArrayEquals(original, source.readBytes())
                assertArrayEquals(original, bytes)
                assertNoPartialFiles(directory)
            }
        }
    }

    @Test fun libraryCancellationDuringCopyPreservesExistingLibraryAndOriginal() {
        val bytes = archive(xmlText = xml.replace("id=\"test\"", "id=\"cancelled\"")); val original = bytes.copyOf()
        withBank(bytes) { source ->
            withLibrary { library, directory ->
                val existing = archive().inputStream().use { library.import(it) }.item
                val existingBytes = existing.file.readBytes()
                var cancelled = false; var copied = 0
                val input = object : FilterInputStream(source.inputStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, minOf(length, 16))
                        if (count > 0) { copied += count; cancelled = true }
                        return count
                    }
                }
                try {
                    input.use { library.import(it) { cancelled } }
                    fail("Expected cancellation during copy")
                } catch (_: IllegalStateException) { }
                assertEquals(16, copied)
                assertEquals(listOf(existing.file), library.list().map { it.file })
                assertEquals(1, directory.listFiles().orEmpty().size)
                assertArrayEquals(existingBytes, existing.file.readBytes())
                assertArrayEquals(original, source.readBytes())
                assertArrayEquals(original, bytes)
                assertNoPartialFiles(directory)
            }
        }
    }

    @Test fun libraryCancellationAfterReadingPreventsPublishingNewBank() {
        val bytes = archive(); val original = bytes.copyOf()
        withBank(bytes) { source ->
            withLibrary { library, directory ->
                var cancelled = false
                val input = object : FilterInputStream(source.inputStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        return super.read(buffer, offset, length).also { if (it < 0) cancelled = true }
                    }
                }
                try {
                    input.use { library.import(it) { cancelled } }
                    fail("Expected cancellation before publishing")
                } catch (_: IllegalStateException) { }
                assertTrue(cancelled)
                assertTrue(library.list().isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertArrayEquals(original, source.readBytes())
                assertArrayEquals(original, bytes)
                assertNoPartialFiles(directory)
            }
        }
    }

    @Test fun libraryRejectsCorruptBanksWithoutLeavingPartialFilesOrChangingSources() {
        val fixtures = listOf(byteArrayOf(1, 2, 3), archive().also { it[0] = 0 },
            archive(xmlText = xml.replace("note.ogg", "missing.ogg")))
        withLibrary { library, directory ->
            for (bytes in fixtures) {
                val original = bytes.copyOf()
                withBank(bytes) { source ->
                    try {
                        source.inputStream().use { library.import(it) }
                        fail("Expected corrupt-bank rejection")
                    } catch (_: BankFormatException) { }
                    assertTrue(library.list().isEmpty())
                    assertTrue(directory.listFiles().orEmpty().isEmpty())
                    assertArrayEquals(original, source.readBytes())
                    assertArrayEquals(original, bytes)
                    assertNoPartialFiles(directory)
                }
            }
        }
    }

    @Test fun libraryRemovalDeletesOnlyImportedCopyAndKeepsOriginal() {
        val bytes = archive(); val original = bytes.copyOf()
        withBank(bytes) { source ->
            withLibrary { library, directory ->
                val item = source.inputStream().use { library.import(it) }.item
                assertTrue(item.file.exists())
                library.remove(item)
                assertFalse(item.file.exists())
                assertTrue(library.list().isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertTrue(source.exists())
                assertArrayEquals(original, source.readBytes())
                assertArrayEquals(original, bytes)
                assertNoPartialFiles(directory)
            }
        }
    }
}
