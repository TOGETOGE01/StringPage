package com.stringpage.app.rse

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

class BankFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class BankEntry(val name: String, val offset: Long, val length: Long) {
    val end: Long get() = offset + length
}
data class BankIndex(val version: Int, val fileSize: Long, val indexOffset: Long,
    val indexLength: Int, val entries: List<BankEntry>) {
    fun entry(name: String): BankEntry = entries.firstOrNull { it.name == name }
        ?: throw BankFormatException("Missing bank member: $name")
}
data class RseWave(val pickup: String, val file: String, val repitch: Double,
    val gain: Double, val entry: BankEntry, val ratio: Double = 1.0,
    val peak: Double = 0.0, val velocity: Int = -1)
data class RseSample(val nodePath: List<String>, val midi: Int, val string: Int,
    val dynamic: String, val option: String, val waves: List<RseWave>)
data class RseSoundBank(val id: String, val name: String, val type: String,
    val description: String, val tuning: List<Int>, val samples: List<RseSample>)
data class RseCue(val id: Long, val position: Long, val chunkId: String,
    val chunkStart: Long, val blockStart: Long, val sampleOffset: Long)
data class RseInstrument(val unshiftedNote: Int, val fineTune: Int, val gain: Int,
    val lowNote: Int, val highNote: Int, val lowVelocity: Int, val highVelocity: Int)
data class RseLoop(val id: Long, val type: Long, val start: Long, val end: Long,
    val fraction: Long, val playCount: Long)
data class RseSampler(val manufacturer: Long, val product: Long, val samplePeriod: Long,
    val midiUnityNote: Long, val midiPitchFraction: Long, val smpteFormat: Long,
    val smpteOffset: Long, val loops: List<RseLoop>, val samplerDataBytes: Long)
data class RseLabel(val cueId: Long, val label: String)
data class RseListDiagnostic(val typeOrPrefixHex: String, val size: Int)
data class RseAudioMetadata(val channels: Int, val sampleRate: Long, val vendor: String,
    val cues: List<RseCue>, val instrument: RseInstrument?, val sampler: RseSampler?,
    val labels: List<RseLabel>, val uninterpretedLists: List<RseListDiagnostic>,
    val headerBytesRead: Int, val nativeCues: List<RseCue> = emptyList())

/** Independent, read-only AMARCHIVE v1 reader. No audio extraction or DSP is performed.
 * Correction values, node paths and cue/loop coordinates are authored metadata;
 * this class deliberately does not assign engine selection or transition semantics.
 */
class Gp7Bank private constructor(private val file: File, val index: BankIndex) {
    companion object {
        private const val HEADER_SIZE = 25
        private const val MAX_INDEX = 4 * 1024 * 1024
        private const val MAX_XML = 8 * 1024 * 1024
        private const val MAX_PACKET = 65536
        private const val MAX_HEADER_READ = 131072

        fun open(file: File): Gp7Bank = RandomAccessFile(file, "r").use { source ->
            val size = source.length()
            val header = exact(source, HEADER_SIZE)
            checkFormat(header.copyOfRange(0, 9).contentEquals("AMARCHIVE".toByteArray()), "Invalid AMARCHIVE magic")
            val h = Cursor(header, 9)
            val version = h.u32(); val count = h.u32(); val length = h.u32(); val offset = h.u32()
            checkFormat(version == 1L, "Unsupported AMARCHIVE version")
            checkFormat(count in 1..65536 && length in 1..MAX_INDEX.toLong() && length >= count * 13, "Invalid index limits")
            checkFormat(offset >= HEADER_SIZE && offset <= size && length <= size - offset, "Index outside file")
            source.seek(offset)
            val c = Cursor(exact(source, length.toInt()))
            val entries = ArrayList<BankEntry>(count.toInt()); val names = HashSet<String>()
            repeat(count.toInt()) {
                val position = c.i64(); val bytes = c.u32(); val name = c.cString(4096)
                val normalized = name.replace('\\', '/')
                checkFormat(name.isNotEmpty() && !normalized.startsWith('/') && ':' !in normalized && ".." !in normalized.split('/'), "Unsafe bank member name")
                checkFormat(names.add(name), "Duplicate bank member name")
                checkFormat(position >= offset + length && position <= size && bytes <= size - position, "Member outside data region")
                entries.add(BankEntry(name, position, bytes))
            }
            checkFormat(c.remaining == 0, "Unparsed index bytes")
            var previous = offset + length
            entries.sortedWith(compareBy<BankEntry> { it.offset }.thenBy { it.length }).forEach {
                checkFormat(it.length == 0L || it.offset >= previous, "Overlapping bank members")
                previous = maxOf(previous, it.end)
            }
            Gp7Bank(file, BankIndex(1, size, offset, length.toInt(), entries.toList()))
        }

        private fun checkFormat(ok: Boolean, message: String) {
            if (!ok) throw BankFormatException(message)
        }
        private fun exact(source: RandomAccessFile, count: Int): ByteArray {
            val data = ByteArray(count)
            try { source.readFully(data) } catch (e: IOException) { throw BankFormatException("Truncated bank data", e) }
            return data
        }
        private fun utf8(data: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (e: Exception) { throw BankFormatException("Invalid UTF-8", e) }
    }

    private fun openVerified(): RandomAccessFile {
        val source = RandomAccessFile(file, "r")
        if (source.length() != index.fileSize) { source.close(); throw BankFormatException("Bank size changed after opening") }
        return source
    }

    fun readMetadata(): RseSoundBank {
        val entry = index.entry("Samples.xml")
        checkFormat(entry.length in 1..MAX_XML.toLong(), "XML size exceeds limits")
        val xml = openVerified().use { it.seek(entry.offset); utf8(exact(it, entry.length.toInt())) }
        checkFormat(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true), "XML declarations are not supported")
        val handler = MetadataHandler(index)
        try {
            val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser().xmlReader
            parser.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities forbidden") }
            parser.contentHandler = handler
            parser.errorHandler = handler
            parser.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            if (e is BankFormatException) throw e
            throw BankFormatException("Invalid Samples.xml", e)
        }
        return handler.result()
    }

    /** Reads identification + comment packets only; stops before Vorbis setup/audio. */
    fun readAudioMetadata(entry: BankEntry): RseAudioMetadata {
        checkFormat(entry in index.entries && entry.name != "Samples.xml", "Entry is not an indexed audio member")
        val packets = ArrayList<ByteArray>(2); val packet = ByteArrayOutputStream(); var consumed = 0
        var serial: Long? = null; var lastSequence: Long? = null
        openVerified().use { source ->
            source.seek(entry.offset)
            fun read(count: Int): ByteArray {
                checkFormat(count >= 0 && count.toLong() <= entry.length - consumed && consumed + count <= MAX_HEADER_READ, "Ogg metadata outside limits")
                consumed += count
                return exact(source, count)
            }
            while (packets.size < 2) {
                val header = read(27)
                checkFormat(header.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) && header[4].toInt() == 0, "Unsupported Ogg header")
                checkFormat((header[5].toInt() and 1 != 0) == (packet.size() > 0), "Invalid Ogg continuation")
                val cursor = Cursor(header, 14); val thisSerial = cursor.u32(); val sequence = cursor.u32()
                checkFormat(serial == null || serial == thisSerial, "Multiplexed Ogg metadata unsupported")
                checkFormat(lastSequence == null || sequence == ((lastSequence!! + 1) and 0xffffffffL), "Ogg page sequence gap")
                serial = thisSerial; lastSequence = sequence
                val lacing = read(header[26].toInt() and 255)
                for (value in lacing) {
                    val length = value.toInt() and 255
                    checkFormat(packet.size() + length <= MAX_PACKET, "Ogg packet exceeds limit")
                    packet.write(read(length))
                    if (length < 255) {
                        packets.add(packet.toByteArray()); packet.reset()
                        if (packets.size == 2) break
                    }
                }
            }
        }
        val identification = Cursor(packets[0]); val comment = Cursor(packets[1])
        checkFormat(identification.bytes(7).contentEquals(byteArrayOf(1) + "vorbis".toByteArray()), "Missing Vorbis identification")
        checkFormat(identification.u32() == 0L, "Unsupported Vorbis version")
        val channels = identification.u8(); val rate = identification.u32()
        checkFormat(channels > 0 && rate > 0 && packets[0].size >= 30, "Invalid Vorbis identification")
        checkFormat(comment.bytes(7).contentEquals(byteArrayOf(3) + "vorbis".toByteArray()), "Missing Vorbis comments")
        fun text(): String { val length = comment.u32(); checkFormat(length <= MAX_PACKET, "Oversized Vorbis comment"); return utf8(comment.bytes(length.toInt())) }
        val vendor = text(); val count = comment.u32()
        checkFormat(count <= 1024, "Too many Vorbis comments")
        val tags = List(count.toInt()) { text() }
        checkFormat(comment.remaining == 1 && comment.u8() and 1 == 1, "Invalid Vorbis comment framing")
        return RiffComments.decode(channels, rate, vendor, tags, consumed)
    }

    private class Cursor(private val data: ByteArray, var position: Int = 0) {
        val remaining: Int get() = data.size - position
        fun bytes(count: Int): ByteArray {
            checkFormat(count >= 0 && count <= remaining, "Truncated metadata field")
            return data.copyOfRange(position, position + count).also { position += count }
        }
        fun u8() = bytes(1)[0].toInt() and 255
        fun i8() = bytes(1)[0].toInt()
        fun u32() = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun i64() = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
        fun cString(limit: Int): String {
            val start = position
            while (position < data.size && position - start <= limit && data[position] != 0.toByte()) position++
            checkFormat(position < data.size && position - start <= limit, "Unterminated or oversized member name")
            return utf8(data.copyOfRange(start, position)).also { position++ }
        }
    }

    private class MetadataHandler(private val index: BankIndex) : DefaultHandler() {
        private val elements = ArrayList<String>(); private val path = ArrayList<String>()
        private val samples = ArrayList<RseSample>(); private var current: RseSample? = null
        private var waves = ArrayList<RseWave>(); private var root: RseSoundBank? = null; private var events = 0
        private fun Attributes.required(name: String) = getValue(name) ?: throw BankFormatException("Missing XML attribute $name")
        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            checkFormat(++events <= 500000 && elements.size < 64, "XML structure exceeds limits")
            val parent = elements.lastOrNull()
            val allowed = when (qName) {
                "SoundBank" -> parent == null && root == null
                "SamplesTree" -> parent == "SoundBank"
                "Node" -> parent == "SamplesTree" || parent == "Node"
                "Samples" -> parent == "Node" || parent == "SamplesTree"
                "Sample" -> parent == "Samples"
                "Wave" -> parent == "Sample"
                else -> false
            }
            checkFormat(allowed, "Unexpected XML structure: $qName")
            elements.add(qName)
            when (qName) {
                "SoundBank" -> {
                    val tuning = attrs.required("tuning").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }
                    checkFormat(tuning.all { it in 0..127 } && tuning.size <= 16, "Invalid tuning")
                    root = RseSoundBank(attrs.required("id"), attrs.required("name"), attrs.required("type"), attrs.required("description"), tuning, emptyList())
                }
                "Node" -> path.add(attrs.required("class").also { checkFormat(it.isNotEmpty(), "Empty Node class") })
                "Sample" -> {
                    val midi = attrs.required("midi").toInt(); val string = attrs.required("string").toInt()
                    checkFormat(midi in -1..127 && string in -1..16 && samples.size < 65536, "Invalid sample coordinate")
                    waves = ArrayList()
                    current = RseSample(path.toList(), midi, string, attrs.required("dynamic"), attrs.required("option"), emptyList())
                }
                "Wave" -> {
                    val name = attrs.required("file"); val repitch = attrs.required("repitch").toDouble(); val gain = attrs.required("gain").toDouble()
                    val ratio = attrs.getValue("ratio")?.toDouble() ?: 1.0
                    val peak = attrs.getValue("peak")?.toDouble() ?: 0.0
                    val velocity = attrs.getValue("velocity")?.toInt() ?: -1
                    checkFormat(repitch.isFinite() && gain.isFinite() && ratio.isFinite() && peak.isFinite() && name != "Samples.xml", "Invalid wave correction or reference")
                    waves.add(RseWave(attrs.required("pickup"), name, repitch, gain, index.entry(name), ratio, peak, velocity))
                }
            }
        }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            checkFormat(elements.lastOrNull() == qName, "Mismatched XML element")
            when (qName) {
                "Node" -> path.removeAt(path.lastIndex)
                "Sample" -> {
                    checkFormat(waves.isNotEmpty(), "Sample has no Wave")
                    samples.add(current!!.copy(waves = waves.toList())); current = null
                }
            }
            elements.removeAt(elements.lastIndex)
        }
        override fun error(e: org.xml.sax.SAXParseException) { throw e }
        override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        fun result(): RseSoundBank {
            checkFormat(elements.isEmpty() && root != null && samples.isNotEmpty(), "Incomplete soundbank metadata")
            return root!!.copy(samples = samples.toList())
        }
    }

    private object RiffComments {
        fun decode(channels: Int, rate: Long, vendor: String, tags: List<String>, consumed: Int): RseAudioMetadata {
            val cues = ArrayList<RseCue>(); var instrument: RseInstrument? = null; var sampler: RseSampler? = null
            var nativeCues: List<RseCue>? = null
            val labels = ArrayList<RseLabel>(); val diagnostics = ArrayList<RseListDiagnostic>(); val seen = HashSet<String>()
            for (tag in tags) {
                val split = tag.indexOf('='); if (split < 0) continue
                val rawKey = tag.substring(0, split)
                val key = rawKey.trim().lowercase(java.util.Locale.ROOT)
                if (key !in setOf("cue", "inst", "list", "smpl")) continue
                val first = seen.add(key)
                checkFormat(first || key == "cue", "Duplicate RIFF metadata chunk")
                val bytes = try { Base64.getDecoder().decode(tag.substring(split + 1)) } catch (e: IllegalArgumentException) { throw BankFormatException("Invalid Base64 RIFF metadata", e) }
                val c = Cursor(bytes); val fourcc = String(c.bytes(4), Charsets.US_ASCII); val size = c.u32()
                checkFormat(size == c.remaining.toLong(), "RIFF metadata size mismatch")
                checkFormat(fourcc.trim().lowercase(java.util.Locale.ROOT) == key, "RIFF comment FourCC mismatch")
                when (fourcc) {
                    "cue " -> {
                        val count = c.u32(); checkFormat(count <= 1024 && c.remaining.toLong() == count * 24, "Invalid cue array")
                        val parsed = List(count.toInt()) { RseCue(c.u32(), c.u32(), String(c.bytes(4), Charsets.US_ASCII), c.u32(), c.u32(), c.u32()) }
                        if (first) cues.addAll(parsed)
                        // The native Vorbis reader uses an exact case-sensitive
                        // key and replaces earlier canonical cue chunks.
                        if (rawKey == "cue") nativeCues = parsed
                    }
                    "inst" -> {
                        checkFormat(c.remaining in 7..8, "Invalid instrument chunk")
                        instrument = RseInstrument(c.u8(), c.i8(), c.i8(), c.u8(), c.u8(), c.u8(), c.u8())
                    }
                    "smpl" -> {
                        val manufacturer = c.u32(); val product = c.u32(); val period = c.u32(); val unity = c.u32(); val fraction = c.u32()
                        val format = c.u32(); val offset = c.u32(); val count = c.u32(); val extra = c.u32()
                        checkFormat(count <= 1024 && c.remaining.toLong() == count * 24 + extra, "Invalid loop array")
                        val loops = List(count.toInt()) {
                            val loop = RseLoop(c.u32(), c.u32(), c.u32(), c.u32(), c.u32(), c.u32())
                            checkFormat(loop.end >= loop.start, "Reversed sample loop"); loop
                        }
                        sampler = RseSampler(manufacturer, product, period, unity, fraction, format, offset, loops, extra)
                    }
                    "LIST" -> {
                        val data = c.bytes(c.remaining)
                        if (data.size < 4 || !data.copyOfRange(0, 4).contentEquals("adtl".toByteArray())) {
                            diagnostics.add(RseListDiagnostic(data.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }, data.size))
                            continue
                        }
                        val list = Cursor(data, 4)
                        while (list.remaining > 0) {
                            val subTag = String(list.bytes(4), Charsets.US_ASCII); val length = list.u32()
                            checkFormat(length <= list.remaining, "Truncated LIST subchunk")
                            val value = list.bytes(length.toInt())
                            if (subTag == "labl") {
                                val label = Cursor(value); val id = label.u32(); val text = label.bytes(label.remaining)
                                checkFormat(text.isNotEmpty() && text.last() == 0.toByte(), "Invalid cue label")
                                labels.add(RseLabel(id, utf8(text.dropLastWhile { it == 0.toByte() }.toByteArray())))
                            }
                            if (length and 1L != 0L && list.remaining > 0 && data[list.position] == 0.toByte()) list.position++
                        }
                    }
                    else -> throw BankFormatException("Unsupported RIFF chunk")
                }
            }
            return RseAudioMetadata(channels, rate, vendor, cues.toList(), instrument, sampler, labels.toList(), diagnostics.toList(), consumed,nativeCues.orEmpty())
        }
    }
}
