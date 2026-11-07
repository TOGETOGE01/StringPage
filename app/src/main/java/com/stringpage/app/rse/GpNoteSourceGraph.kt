@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
package com.stringpage.app.rse

import alphaTab.model.Beat
import alphaTab.model.GraceType
import alphaTab.model.Note
import alphaTab.model.Score
import org.xml.sax.Attributes

/** Raw GPIF note identity follows explicit MasterBar -> Bar -> Voice -> Beat ->
 * Note references, never alphaTab's process-global generated Note.id counter.
 * No model objects are retained by this source graph.
 */
class GpNoteSourceGraph internal constructor(
    private val masterBars: List<List<String>>,
    private val bars: Map<String, List<String>>,
    private val voices: Map<String, List<String>>,
    private val beats: Map<String, List<String>>,
    private val notes: Map<String, NoteInfo>
) {
    data class NoteInfo(
        val sourceId: String, val accentFlags: Int?, val rawAntiAccent: String?,
        val tapped: Boolean, val leftHandTapped: Boolean,
        val hopoOrigin: Boolean, val hopoDestination: Boolean,
        val sourceString: Int?, val sourceFret: Int?, val tieDestination: Boolean,
        val slideFlags: Int = 0
    ) {
        val antiAccent: Int get() = antiAccentFromString(rawAntiAccent)
    }

    companion object {
        /** GPIF's signed, decimal C long conversion before the uint property
         * write. Windows long is 32-bit; overflow saturates before the cast.
         * Only the ASCII decimal prefix is consumed, with C whitespace/sign.
         */
        fun slideFlagsFromString(raw: String): Int {
            var at = 0
            while (at < raw.length && raw[at] in " \t\r\n\u000b\u000c") at++
            val negative = at < raw.length && raw[at] == '-'
            if (at < raw.length && raw[at] in "+-") at++
            val limit = if (negative) 2147483648L else 2147483647L
            var value = 0L
            while (at < raw.length && raw[at] in '0'..'9') {
                val digit = raw[at++]-'0'
                if (value > (limit-digit)/10L) { value = limit; break }
                value = value*10L+digit
            }
            return (if (negative) -value else value).toInt()
        }
        /** GPCore antiAccentFromString, including case and unknown fallback.
         * Normal is enum 2; Soft is 1; Strong is 3. Whitespace is significant.
         */
        fun antiAccentFromString(raw: String?): Int = when (raw?.map { if (it in 'a'..'z') it - 32 else it }?.joinToString("")) {
            "SOFT" -> 1; "NORMAL" -> 2; "STRONG" -> 3; else -> 0
        }
        private fun requireSource(value: Boolean, message: String) {
            if (!value) throw GpRsePropertiesException(message)
        }
    }

    internal data class Binding(val note: Note, val source: NoteInfo, val original: Note)
    internal fun bind(score: Score): List<Binding> {
        if (masterBars.isEmpty()) {
            requireSource(notes.isEmpty(), "GPIF notes have no MasterBars graph")
            return emptyList()
        }
        requireSource(masterBars.size <= score.masterBars.length.toInt(), "GPIF master-bar count differs from score")
        val mapped = ArrayList<Binding>()
        val nextBars = score.tracks.toList().map { IntArray(it.staves.length.toInt()) }
        val usedBars = HashSet<String>()
        for (barIds in masterBars) {
            var trackIndex = 0; var staffIndex = 0
            for (barId in barIds) {
                requireSource(trackIndex < score.tracks.length.toInt(), "Too many GPIF staff bars")
                // Mirrors the pinned importer's invalid-bar sentinel behavior.
                if (barId == "-1") { trackIndex++; continue }
                val track = score.tracks[trackIndex]
                requireSource(staffIndex < track.staves.length.toInt(), "Invalid GPIF staff position")
                val staff = track.staves[staffIndex]
                val index = nextBars[trackIndex][staffIndex]++
                requireSource(index < staff.bars.length.toInt(), "Missing score bar for GPIF $barId")
                requireSource(usedBars.add(barId), "GPIF Bar is shared by multiple positions: $barId")
                val model = staff.bars[index]
                val sourceVoices = bars[barId] ?: throw GpRsePropertiesException("Missing GPIF bar $barId")
                requireSource(sourceVoices.size <= model.voices.length.toInt(), "GPIF voice count differs at bar $barId")
                for ((voiceIndex, voiceId) in sourceVoices.withIndex()) {
                    val voice = model.voices[voiceIndex]
                    val sourceBeats = if (voiceId == "-1") emptyList() else
                        voices[voiceId] ?: throw GpRsePropertiesException("Missing GPIF voice $voiceId")
                    var beatIndex = 0
                    for (beatId in sourceBeats.filter { it != "-1" }) {
                        requireSource(beatIndex < voice.beats.length.toInt(), "Missing score beat for GPIF $beatId")
                        val beat = voice.beats[beatIndex++]
                        val sourceNotes = beats[beatId] ?: throw GpRsePropertiesException("Missing GPIF beat $beatId")
                        val infos = sourceNotes.filter { it != "-1" }.map {
                            notes[it] ?: throw GpRsePropertiesException("Missing GPIF note $it")
                        }
                        bindBeat(beat, infos, mapped)
                        // SongBook mode inserts one tied continuation after the
                        // original bending beat. Its grace group points back to
                        // that exact original object, independent of counters.
                        if (beatIndex < voice.beats.length.toInt()) {
                            val helper = voice.beats[beatIndex]
                            val group = helper.graceGroup
                            if (beat.graceType == GraceType.BendGrace && group != null &&
                                group.beats.length == 1.0 && group.beats[0] === beat && helper !== beat) {
                                requireSource(helper.notes.length == infos.size.toDouble(), "Bend continuation note count differs")
                                infos.forEachIndexed { at, info -> mapped.add(Binding(helper.notes[at], info, beat.notes[at])) }
                                beatIndex++
                            }
                        }
                    }
                    while (beatIndex < voice.beats.length.toInt()) {
                        requireSource(voice.beats[beatIndex++].notes.length == 0.0,
                            "Unmatched score notes after GPIF voice $voiceId")
                    }
                }
                for (at in sourceVoices.size until model.voices.length.toInt())
                    requireSource(model.voices[at].beats.all { it.notes.length == 0.0 }, "Unmatched generated voice notes")
                if (staffIndex == track.staves.length.toInt() - 1) { trackIndex++; staffIndex = 0 } else staffIndex++
            }
        }
        for ((t, track) in score.tracks.toList().withIndex()) for ((s, staff) in track.staves.toList().withIndex())
            for (b in nextBars[t][s] until staff.bars.length.toInt())
                requireSource(staff.bars[b].voices.all { v -> v.beats.all { it.notes.length == 0.0 } }, "Unmatched score bar notes")
        return mapped
    }

    private fun bindBeat(beat: Beat, source: List<NoteInfo>, result: MutableList<Binding>) {
        requireSource(beat.notes.length.toInt() == source.size, "GPIF note count differs at score beat ${beat.index}")
        for ((index, info) in source.withIndex()) {
            val note = beat.notes[index]
            if (!beat.voice.bar.staff.isPercussion) {
                info.sourceString?.let { requireSource(note.string.toInt() == it + 1, "GPIF string differs for note ${info.sourceId}") }
                if (!info.tieDestination) info.sourceFret?.let {
                    requireSource(note.fret.toInt() == it, "GPIF fret differs for note ${info.sourceId}")
                }
            }
            result.add(Binding(note,info,note))
        }
    }

    /** Shares the existing hardened SAX reader; captures only structural refs
     * and the few Note fields the public score model discards.
     */
    internal class Builder {
        private data class Frame(val tag: String, val name: String?, val text: StringBuilder?,
            val booleanFlag: String? = null, val slideFlag: Boolean = false, var firstChildSeen: Boolean = false,
            var hasData: Boolean = false, var dataFinished: Boolean = false)
        private data class Draft(val id: String, var accent: Int? = null, var anti: String? = null,
            val flags: MutableSet<String> = HashSet(), var string: Int? = null, var fret: Int? = null,
            var tied: Boolean = false, var slideFlags: Int = 0)
        private val stack = ArrayList<Frame>()
        private val masters = ArrayList<List<String>>()
        private val bars = LinkedHashMap<String, List<String>>()
        private val voices = LinkedHashMap<String, List<String>>()
        private val beats = LinkedHashMap<String, List<String>>()
        private val notes = LinkedHashMap<String, NoteInfo>()
        private var draft: Draft? = null
        private var barId: String? = null; private var voiceId: String? = null; private var beatId: String? = null
        private var barRefs: List<String>? = null; private var voiceRefs: List<String>? = null; private var beatRefs: List<String>? = null
        private var masterRefs: List<String>? = null
        private var references = 0
        private var cdata = false
        private val booleanFlags = setOf("Tapped","LeftHandTapped","HopoOrigin","HopoDestination")
        private val leaves = setOf("GPIF/MasterBars/MasterBar/Bars", "GPIF/Bars/Bar/Voices",
            "GPIF/Voices/Voice/Beats", "GPIF/Beats/Beat/Notes", "GPIF/Notes/Note/Accent", "GPIF/Notes/Note/AntiAccent",
            "GPIF/Notes/Note/Properties/Property/String", "GPIF/Notes/Note/Properties/Property/Fret")
        private fun path() = stack.joinToString("/") { it.tag }
        private fun id(attrs: Attributes, table: Map<String, *>): String {
            val id = attrs.getValue("id") ?: throw GpRsePropertiesException("Missing GPIF graph id")
            requireSource(id.isNotBlank() && id.length <= 4096 && id !in table, "Duplicate/invalid GPIF graph id $id")
            requireSource(table.size < 400000, "Too many GPIF graph nodes")
            return id
        }
        fun start(tag: String, attrs: Attributes) {
            val full = if (stack.isEmpty()) tag else path() + "/" + tag
            var booleanFlag: String? = null
            var slideFlag = false
            val parent = stack.lastOrNull()
            if (path() == "GPIF/Notes/Note/Properties/Property" && parent != null && !parent.firstChildSeen) {
                if (parent.name in booleanFlags) { parent.firstChildSeen = true; booleanFlag = parent.name }
                else if (parent.name == "Slide") { parent.firstChildSeen = true; slideFlag = true }
            }
            boundary()
            stack.add(Frame(tag, attrs.getValue("name"), if (full in leaves || booleanFlag != null || slideFlag) StringBuilder() else null,booleanFlag,slideFlag))
            when (full) {
                "GPIF/MasterBars/MasterBar" -> { requireSource(masters.size < 65536, "Too many GPIF master bars"); masterRefs = null }
                "GPIF/Bars/Bar" -> { barId = id(attrs,bars); barRefs = null }
                "GPIF/Voices/Voice" -> { voiceId = id(attrs,voices); voiceRefs = null }
                "GPIF/Beats/Beat" -> { beatId = id(attrs,beats); beatRefs = null }
                "GPIF/Notes/Note" -> { draft = Draft(id(attrs,notes)) }
                "GPIF/Notes/Note/Tie" -> draft?.tied = attrs.getValue("destination").equals("true",true)
            }
        }
        // XMLElement::text returns its first direct data node, not CDATA or
        // concatenated descendant/sibling data. SAX can split one data node
        // into many character callbacks, so only lexical boundaries end it.
        fun boundary() { stack.lastOrNull()?.let { if (it.hasData) it.dataFinished = true } }
        fun startCdata() { boundary(); cdata = true }
        fun endCdata() { cdata = false }
        fun text(chars: CharArray, start: Int, length: Int) {
            if (cdata || length == 0) return
            val frame = stack.lastOrNull() ?: return
            if (frame.dataFinished) return
            frame.text?.let {
                requireSource(it.length + length <= 65536, "GPIF note graph text exceeds limits")
                it.append(chars,start,length); frame.hasData = true
            }
        }
        private fun refs(text: String): List<String> {
            val list = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            references += list.size
            requireSource(references <= 2000000, "Too many GPIF graph references")
            return list
        }
        fun end(tag: String) {
            val full = path(); val text = stack.last().text?.toString().orEmpty()
            stack.last().booleanFlag?.let { name ->
                if (text == "false") draft?.flags?.remove(name) else draft?.flags?.add(name)
            }
            if (stack.last().slideFlag) draft?.slideFlags = slideFlagsFromString(text)
            when (full) {
                "GPIF/MasterBars/MasterBar/Bars" -> { requireSource(masterRefs == null, "Duplicate master bar refs"); masterRefs = refs(text) }
                "GPIF/MasterBars/MasterBar" -> masters.add(masterRefs ?: emptyList())
                "GPIF/Bars/Bar/Voices" -> { requireSource(barRefs == null, "Duplicate voice refs"); barRefs = refs(text) }
                "GPIF/Bars/Bar" -> bars[barId!!] = barRefs ?: emptyList()
                "GPIF/Voices/Voice/Beats" -> { requireSource(voiceRefs == null, "Duplicate beat refs"); voiceRefs = refs(text) }
                "GPIF/Voices/Voice" -> voices[voiceId!!] = voiceRefs ?: emptyList()
                "GPIF/Beats/Beat/Notes" -> { requireSource(beatRefs == null, "Duplicate note refs"); beatRefs = refs(text) }
                "GPIF/Beats/Beat" -> beats[beatId!!] = beatRefs ?: emptyList()
                "GPIF/Notes/Note/Accent" -> draft?.let {
                    requireSource(it.accent == null, "Duplicate note accent"); it.accent = text.trim().toIntOrNull()
                        ?: throw GpRsePropertiesException("Invalid note accent flags")
                }
                "GPIF/Notes/Note/AntiAccent" -> draft?.let { requireSource(it.anti == null, "Duplicate anti-accent"); it.anti = text }
                "GPIF/Notes/Note/Properties/Property/String" -> if (stack[stack.lastIndex-1].name == "String") draft?.string = text.trim().toIntOrNull()
                "GPIF/Notes/Note/Properties/Property/Fret" -> if (stack[stack.lastIndex-1].name == "Fret") draft?.fret = text.trim().toIntOrNull()
                "GPIF/Notes/Note" -> draft?.let {
                    notes[it.id] = NoteInfo(it.id,it.accent,it.anti,"Tapped" in it.flags,"LeftHandTapped" in it.flags,
                        "HopoOrigin" in it.flags,"HopoDestination" in it.flags,it.string,it.fret,it.tied,it.slideFlags)
                    draft = null
                }
            }
            requireSource(stack.last().tag == tag, "Unbalanced GPIF note graph")
            stack.removeAt(stack.lastIndex)
        }
        fun finish() = GpNoteSourceGraph(masters,bars,voices,beats,notes)
    }
}
