@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app.rse

import alphaTab.model.Score
import alphaTab.model.Note
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.WeakHashMap
import java.util.Locale
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

class GpRsePropertiesException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Read-only GPIF fields omitted by the score model. Raw names remain available;
 * unknown explicit values are never silently treated as the native default.
 * The bounded GPX reader is independent: alphaTab's GpxFileSystem is internal.
 */
object GpRseProperties {
    enum class Container { Zip, Gpx, LegacyOrOther }
    data class ArticulationInfo(
        val elementIndex: Int, val articulationIndex: Int, val id: String?,
        val rawOutputRseSound: String?, val outputRseSound: List<String>, val outputMidiNumber: Int?
    )
    data class TrackInfo(
        val id: String, val instrumentType: String?, val instrumentReference: String?,
        val rawPlayingStyle: String?, val playingStyleCore: Int?,
        val articulations: List<ArticulationInfo>
    ) {
        val hasExplicitPlayingStyle: Boolean get() = rawPlayingStyle != null
    }
    data class Properties(val container: Container, val tracks: List<TrackInfo>, val usesMasterTrackOrder: Boolean,
        val noteGraph: GpNoteSourceGraph? = null) {
        fun track(index: Int): TrackInfo? = tracks.getOrNull(index)
        val hasGpif: Boolean get() = container != Container.LegacyOrOther
    }

    private const val MAX_SOURCE = 128 * 1024 * 1024
    private const val MAX_CONTAINER = 256 * 1024 * 1024
    private const val MAX_XML = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 65536
    private const val MAX_TRACKS = 4096
    private const val SECTOR = 4096
    private val registered = WeakHashMap<Score, Properties>()
    private val failures = WeakHashMap<Score, String>()
    // Values contain no Note/Score references, so weak keys can be collected.
    private val noteProperties = WeakHashMap<Note, GpNoteSourceGraph.NoteInfo>()
    private val originalNotes = WeakHashMap<Note, WeakReference<Note>>()

    @Synchronized fun bind(score: Score, properties: Properties) {
        val notes = properties.noteGraph?.bind(score).orEmpty()
        clearNoteProperties(score)
        notes.forEach { (note, source, original) ->
            noteProperties[note] = source; originalNotes[note] = WeakReference(original)
        }
        registered[score] = properties; failures.remove(score)
    }
    @Synchronized fun bindFailure(score: Score, reason: String) {
        clearNoteProperties(score); registered.remove(score); failures[score] = reason
    }
    private fun clearNoteProperties(score: Score) {
        for (track in score.tracks) for (staff in track.staves) for (bar in staff.bars)
            for (voice in bar.voices) for (beat in voice.beats) for (note in beat.notes) {
                noteProperties.remove(note); originalNotes.remove(note)
            }
    }
    @Synchronized fun get(score: Score): Properties? = registered[score]
    @Synchronized fun failure(score: Score): String? = failures[score]
    @Synchronized fun note(note: Note): GpNoteSourceGraph.NoteInfo? = noteProperties[note]
    @Synchronized fun original(note: Note): Note = originalNotes[note]?.get() ?: note
    @Synchronized fun transfer(from: Score, to: Score) {
        // Only used by our own model snapshot/restore path. Map the copied
        // graph directly; source fret checks would reject an intentional edit.
        val copied = ArrayList<Pair<Note,GpNoteSourceGraph.NoteInfo>>()
        val copies = IdentityHashMap<Note,Note>()
        fun same(a: Double, b: Double) {
            if (a != b) throw GpRsePropertiesException("Copied score note graph differs")
        }
        same(from.tracks.length,to.tracks.length)
        for (t in 0 until from.tracks.length.toInt()) {
            val a=from.tracks[t]; val b=to.tracks[t]; same(a.staves.length,b.staves.length)
            for (s in 0 until a.staves.length.toInt()) {
                val c=a.staves[s]; val d=b.staves[s]; same(c.bars.length,d.bars.length)
                for (bar in 0 until c.bars.length.toInt()) {
                    val e=c.bars[bar]; val f=d.bars[bar]; same(e.voices.length,f.voices.length)
                    for (v in 0 until e.voices.length.toInt()) {
                        val g=e.voices[v]; val h=f.voices[v]; same(g.beats.length,h.beats.length)
                        for (beat in 0 until g.beats.length.toInt()) {
                            val i=g.beats[beat]; val j=h.beats[beat]; same(i.notes.length,j.notes.length)
                            for (n in 0 until i.notes.length.toInt()) {
                                copies[i.notes[n]]=j.notes[n]
                                noteProperties[i.notes[n]]?.let { copied.add(j.notes[n] to it) }
                            }
                        }
                    }
                }
            }
        }
        val originals = copies.entries.mapNotNull { (old,new) ->
            originalNotes[old]?.get()?.let { source -> copies[source]?.let { new to it } }
        }
        clearNoteProperties(to)
        copied.forEach { (note,source) -> noteProperties[note]=source }
        originals.forEach { (note,source) -> originalNotes[note]=WeakReference(source) }
        val value = registered[from]
        if (value == null) registered.remove(to) else registered[to] = value
        val failure = failures[from]
        if (failure == null) failures.remove(to) else failures[to] = failure
    }

    fun playingStyleCore(raw: String): Int? = when (raw.uppercase(Locale.ROOT)) {
        "DEFAULT" -> 0; "STRINGEDPICK" -> 1; "STRINGEDFINGER" -> 2
        "STRINGEDFINGERPICKING" -> 3; "BASSSLAP" -> 4; "DRUMKITBRUSH" -> 5
        "DRUMKITHOTROD", "DRUMKITSTICK" -> 6
        else -> null
    }

    /** Native parser is case insensitive and returns Default for unknown text.
     * The nullable inspection value above still distinguishes unknown authored text. */
    fun nativePlayingStyleCore(raw: String?): Int = raw?.let(::playingStyleCore) ?: 0

    fun read(bytes: ByteArray): Properties {
        check(bytes.size <= MAX_SOURCE, "GP source exceeds 128 MB")
        try {
            val header = if (bytes.size >= 4) String(bytes, 0, 4, Charsets.US_ASCII) else ""
            val container: Container
            val xml: ByteArray
            if (bytes.size >= 2 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()) {
                container = Container.Zip; xml = fromZip(bytes)
            } else if (header == "BCFS" || header == "BCFZ") {
                container = Container.Gpx; xml = fromGpx(bytes, header == "BCFZ")
            } else return Properties(Container.LegacyOrOther, emptyList(), false)
            return parse(xml, container)
        } catch (e: GpRsePropertiesException) { throw e }
        catch (e: Exception) { throw GpRsePropertiesException("Cannot read GP RSE source properties", e) }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw GpRsePropertiesException(message)
    }

    private fun safeName(raw: String): String {
        check(raw.isNotEmpty() && raw.length <= 4096 && '\u0000' !in raw, "Invalid member name")
        val normalized = raw.replace('\\', '/')
        check(!normalized.startsWith('/') && ':' !in normalized, "Unsafe member name")
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        check(parts.isNotEmpty() && parts.none { it == "." || it == ".." }, "Unsafe member path")
        return parts.joinToString("/")
    }

    private fun fromZip(bytes: ByteArray): ByteArray {
        var xml: ByteArray? = null; var count = 0; var total = 0L
        val names = HashSet<String>(); val buffer = ByteArray(8192)
        ZipInputStream(ByteArrayInputStream(compatibleZipDescriptors(bytes))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                check(++count <= MAX_ENTRIES, "Too many ZIP members")
                val name = safeName(entry.name)
                check(names.add(name), "Duplicate ZIP member")
                if (entry.isDirectory) { input.closeEntry(); continue }
                val score = name.substringAfterLast('/') == "score.gpif"
                if (score) check(xml == null, "Ambiguous score.gpif members")
                check(entry.size <= (if (score) MAX_XML else MAX_CONTAINER).toLong(), "Oversized ZIP member")
                val target = if (score) ByteArrayOutputStream() else null
                var memberBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    memberBytes += read; total += read
                    check(total <= MAX_CONTAINER && (!score || memberBytes <= MAX_XML), "ZIP expanded data exceeds limits")
                    target?.write(buffer, 0, read)
                }
                if (score) xml = target!!.toByteArray()
                input.closeEntry()
            }
        }
        return xml ?: throw GpRsePropertiesException("No score.gpif in GP container")
    }

    /** Some native GP archives write expanded/compressed sizes in reverse order
     * in their streaming descriptors, while their central directory is correct.
     * Correct only that exact, identifiable layout in a private memory copy.
     * ZipInputStream still verifies actual sizes and CRCs for every member;
     * source files, CRC fields and arbitrary inconsistent sizes are not changed.
     */
    private fun compatibleZipDescriptors(bytes: ByteArray): ByteArray {
        fun short(at: Int) = (bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8)
        var end = bytes.size - 22
        val minimum = maxOf(0, bytes.size - 22 - 65535)
        while (end >= minimum) {
            if (little(bytes,end) == 0x06054b50 && end + 22 + short(end+20) == bytes.size) break
            end--
        }
        if (end < minimum) return bytes
        val count = short(end+10)
        // Leave unsupported/multipart/ZIP64 layouts to the original ZIP reader.
        if (short(end+4) != 0 || short(end+6) != 0 || short(end+8) != count || count == 65535) return bytes
        val start = little(bytes,end+16); val size = little(bytes,end+12)
        if (start < 0 || size < 0 || start.toLong()+size > end) return bytes
        val limit = start+size
        var at = start
        val swaps = ArrayList<Int>()
        repeat(count) {
            if (at > limit-46 || little(bytes,at) != 0x02014b50) return bytes
            val flags=short(at+8); val method=short(at+10); val crc=little(bytes,at+16)
            val compressed=little(bytes,at+20); val expanded=little(bytes,at+24)
            val next=at.toLong()+46+short(at+28)+short(at+30)+short(at+32)
            val local=little(bytes,at+42)
            if (next > limit || compressed < 0 || expanded < 0 || local < 0 || local > start-30) return bytes
            if (little(bytes,local) != 0x04034b50) return bytes
            val data=local.toLong()+30+short(local+26)+short(local+28)
            val after=data+compressed
            if (after > start || after < data) return bytes
            if (flags and 8 != 0 && flags and 1 == 0 && method == 8 && compressed != expanded) {
                var descriptor=after.toInt()
                if (descriptor > start-12) return bytes
                // Support both unsigned descriptors and descriptors with the
                // optional signature. CRC equality disambiguates the two.
                if (little(bytes,descriptor) != crc && little(bytes,descriptor) == 0x08074b50) descriptor+=4
                if (descriptor <= start-12 && little(bytes,descriptor) == crc &&
                    little(bytes,descriptor+4) == expanded && little(bytes,descriptor+8) == compressed) {
                    swaps.add(descriptor+4)
                }
            }
            at=next.toInt()
        }
        if (at != limit || swaps.isEmpty()) return bytes
        return bytes.copyOf().also { result ->
            for (offset in swaps) repeat(4) { index ->
                result[offset+index]=bytes[offset+4+index]
                result[offset+4+index]=bytes[offset+index]
            }
        }
    }

    private class Bits(private val data: ByteArray, private var position: Int) {
        private var bit = 0
        fun take(count: Int, reversed: Boolean = false): Int {
            require(count in 0..16)
            var value = 0
            repeat(count) { index ->
                check(position < data.size, "Truncated GPX compressed block")
                val next = (data[position].toInt() ushr (7 - bit)) and 1
                value = value or (next shl if (reversed) index else count - index - 1)
                if (++bit == 8) { bit = 0; position++ }
            }
            return value
        }
    }

    private fun little(data: ByteArray, offset: Int): Int {
        check(offset >= 0 && offset <= data.size - 4, "GPX field outside block")
        return (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8) or
            ((data[offset + 2].toInt() and 255) shl 16) or (data[offset + 3].toInt() shl 24)
    }

    private fun fromGpx(source: ByteArray, compressed: Boolean): ByteArray {
        val data = if (compressed) {
            val size = little(source, 4)
            check(size in 4..MAX_CONTAINER, "Invalid GPX expanded size")
            val result = ByteArray(size); val bits = Bits(source, 8)
            var used = 0; var tokens = 0
            while (used < size) {
                check(++tokens <= 32 * 1024 * 1024, "GPX compressed work exceeds limits")
                if (bits.take(1) == 0) {
                    val amount = bits.take(2, true)
                    check(amount <= size - used, "GPX literal exceeds declared size")
                    repeat(amount) { result[used++] = bits.take(8).toByte() }
                } else {
                    val width = bits.take(4)
                    val distance = bits.take(width, true); val length = bits.take(width, true)
                    val amount = minOf(distance, length)
                    check(distance <= used && amount <= size - used, "Invalid GPX back reference")
                    if (amount > 0) {
                        result.copyInto(result, used, used - distance, used - distance + amount)
                        used += amount
                    }
                }
            }
            check(String(result, 0, 4, Charsets.US_ASCII) == "BCFS", "GPX expanded block is not BCFS")
            result
        } else source
        // Sector offsets are relative to the data after the four-byte BCFS header.
        val length = data.size - 4
        check(length <= MAX_CONTAINER && length >= SECTOR, "Invalid BCFS data block")
        var cursor = SECTOR; var entries = 0; var xml: ByteArray? = null
        val names = HashSet<String>()
        while (cursor <= length - 4) {
            val offset = cursor + 4
            if (little(data, offset) == 2) {
                check(++entries <= MAX_ENTRIES, "Too many BCFS members")
                check(offset <= data.size - 148, "Truncated BCFS member")
                var end = offset + 4
                while (end < offset + 131 && data[end] != 0.toByte()) end++
                val name = safeName(String(data, offset + 4, end - offset - 4, Charsets.ISO_8859_1))
                check(names.add(name), "Duplicate BCFS member")
                val size = little(data, offset + 140)
                val score = name.substringAfterLast('/') == "score.gpif"
                check(size >= 0 && size <= if (score) MAX_XML else MAX_CONTAINER, "Invalid BCFS member size")
                if (score) check(xml == null, "Ambiguous BCFS score.gpif members")
                val target = if (score) ByteArray(size) else null
                var copied = 0; var table = offset + 148; var refs = 0; var lastSector = cursor
                while (true) {
                    val sector = little(data, table); table += 4
                    if (sector == 0) break
                    check(++refs <= MAX_CONTAINER / SECTOR, "Too many BCFS sector references")
                    check(sector > 0 && sector.toLong() * SECTOR <= length.toLong() - SECTOR, "BCFS sector outside block")
                    val start = sector * SECTOR
                    val amount = minOf(SECTOR, size - copied)
                    if (amount > 0) {
                        target?.let { data.copyInto(it, copied, start + 4, start + 4 + amount) }
                        copied += amount
                    }
                    lastSector = maxOf(lastSector, start)
                }
                check(copied == size, "Truncated BCFS member content")
                if (score) xml = target
                cursor = lastSector
            }
            cursor += SECTOR
        }
        return xml ?: throw GpRsePropertiesException("No score.gpif in GPX container")
    }

    private fun parse(xml: ByteArray, container: Container): Properties {
        check(xml.isNotEmpty() && xml.size <= MAX_XML, "Invalid GPIF size")
        val handler = PropertiesHandler()
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }.newSAXParser().xmlReader
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
            try { reader.setFeature(feature, false) } catch (_: SAXException) { }
        }
        try { reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: SAXException) { }
        // startDTD also rejects internal entities on parsers lacking the feature.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler; reader.errorHandler = handler; reader.entityResolver = handler
        reader.parse(InputSource(ByteArrayInputStream(xml)))
        return handler.result(container)
    }

    private class PropertiesHandler : DefaultHandler2() {
        private val noteGraph = GpNoteSourceGraph.Builder()
        private data class Frame(val tag: String, val text: StringBuilder?)
        private data class Art(var id: String?, val element: Int, val index: Int, var sound: String? = null, var midi: Int? = null)
        private data class Track(val id: String, var type: String? = null, var reference: String? = null,
            var style: String? = null, var styleSeen: Boolean = false, val arts: MutableList<ArticulationInfo> = ArrayList(),
            var element: Int = -1, var articulation: Int = 0)
        private val stack = ArrayList<Frame>()
        private val paths = HashMap<String, List<String>>()
        private val tracks = LinkedHashMap<String, TrackInfo>()
        private var track: Track? = null; private var art: Art? = null
        private var masterIds: List<String>? = null; private var events = 0; private var root = false; private var articulations = 0
        private fun path(value: String): Boolean {
            val tags = paths.getOrPut(value) { value.split('/') }
            return tags.size == stack.size && tags.indices.all { stack[it].tag == tags[it] }
        }
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("GPIF DTD/entity declarations are forbidden") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("GPIF external entities are forbidden") }
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { throw SAXException("GPIF external entities are forbidden") }
        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            check(++events <= 2000000 && stack.size < 128, "GPIF structure exceeds limits")
            noteGraph.start(qName, attrs)
            if (stack.isEmpty()) { check(!root && qName == "GPIF", "Expected GPIF root"); root = true }
            val capture = qName in setOf("PlayingStyle", "OutputRSESound", "OutputMidiNumber", "Type", "Tracks")
            stack.add(Frame(qName, if (capture) StringBuilder() else null))
            when {
                path("GPIF/Tracks/Track") -> {
                    check(track == null && tracks.size < MAX_TRACKS, "Invalid GPIF track nesting/count")
                    val id = attrs.getValue("id") ?: throw GpRsePropertiesException("Missing GPIF track id")
                    check(id.isNotBlank() && id.length <= 4096 && id !in tracks, "Duplicate/invalid GPIF track id")
                    track = Track(id)
                }
                path("GPIF/Tracks/Track/Instrument") -> track?.reference = attrs.getValue("ref")
                path("GPIF/Tracks/Track/InstrumentSet/Elements/Element") -> track?.let { it.element++; it.articulation = 0 }
                path("GPIF/Tracks/Track/InstrumentSet/Elements/Element/Articulations/Articulation") -> track?.let {
                    check(++articulations <= 65536 && it.element < 1024, "Too many GPIF articulations")
                    art = Art(attrs.getValue("id"), it.element, it.articulation++)
                }
            }
        }
        override fun characters(chars: CharArray, start: Int, length: Int) {
            noteGraph.text(chars,start,length)
            stack.lastOrNull()?.text?.let { check(it.length + length <= 65536, "GPIF property text exceeds limits"); it.append(chars, start, length) }
        }
        override fun comment(chars: CharArray, start: Int, length: Int) { noteGraph.boundary() }
        override fun processingInstruction(target: String?, data: String?) { noteGraph.boundary() }
        override fun startCDATA() { noteGraph.startCdata() }
        override fun endCDATA() { noteGraph.endCdata() }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            noteGraph.end(qName)
            check(stack.lastOrNull()?.tag == qName, "Unbalanced GPIF")
            val text = stack.last().text?.toString()
            when {
                path("GPIF/MasterTrack/Tracks") -> {
                    check(masterIds == null, "Duplicate master-track ordering")
                    masterIds = tokens(text.orEmpty())
                    check(masterIds!!.size <= MAX_TRACKS && masterIds!!.distinct().size == masterIds!!.size, "Invalid master-track ordering")
                }
                path("GPIF/Tracks/Track/PlayingStyle") -> track?.let {
                    check(!it.styleSeen, "Duplicate PlayingStyle"); it.styleSeen = true; it.style = text.orEmpty().trim()
                }
                path("GPIF/Tracks/Track/InstrumentSet/Type") -> track?.let { check(it.type == null, "Duplicate instrument type"); it.type = text.orEmpty().trim() }
                path("GPIF/Tracks/Track/InstrumentSet/Elements/Element/Articulations/Articulation/OutputRSESound") -> art?.let {
                    check(it.sound == null, "Duplicate OutputRSESound"); it.sound = text.orEmpty()
                }
                path("GPIF/Tracks/Track/InstrumentSet/Elements/Element/Articulations/Articulation/OutputMidiNumber") -> art?.midi = text?.trim()?.toIntOrNull()
                path("GPIF/Tracks/Track/InstrumentSet/Elements/Element/Articulations/Articulation") -> art?.let {
                    track!!.arts.add(ArticulationInfo(it.element,it.index,it.id,it.sound,tokens(it.sound.orEmpty()),it.midi)); art = null
                }
                path("GPIF/Tracks/Track") -> track?.let {
                    tracks[it.id] = TrackInfo(it.id,it.type,it.reference,it.style,
                        if (it.styleSeen) playingStyleCore(it.style.orEmpty()) else 0,it.arts.toList())
                    track = null
                }
            }
            stack.removeAt(stack.lastIndex)
        }
        fun result(container: Container): Properties {
            check(root && stack.isEmpty() && track == null, "Incomplete GPIF document")
            val master = masterIds?.takeIf { it.isNotEmpty() }
            val ordered = if (master != null) master.map { tracks[it] ?: throw GpRsePropertiesException("MasterTrack references missing track $it") } else tracks.values.toList()
            return Properties(container,ordered,master != null,noteGraph.finish())
        }
        private fun tokens(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}
