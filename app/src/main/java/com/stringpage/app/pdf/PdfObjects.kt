package com.stringpage.app.pdf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

internal class PdfConversionException(message: String) : IllegalArgumentException(message)

internal fun pdfRequire(condition: Boolean, message: String = "这份 PDF 的结构超出当前支持范围。") {
    if (!condition) throw PdfConversionException(message)
}

internal fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
}

internal data class Name(val value: String)
internal data class Ref(val id: Int, val generation: Int)
internal data class Hex(val value: String)
internal data class Literal(val value: String)
internal data class Dictionary(val values: Map<String, Any>) {
    operator fun get(key: String) = values[key]
}

/** Resolves PDF indirect objects through the file's cross-reference sections. */
internal class PdfObjects(private val data: ByteArray) {
    companion object {
        const val MAX_STREAM_BYTES = 8 * 1024 * 1024
        private const val MAX_OBJECTS = 20_000
        private const val MAX_SECTIONS = 32
        private const val MAX_INDEX_ENTRIES = MAX_OBJECTS * 4
        private const val MAX_OBJECT_STREAM_BYTES = MAX_STREAM_BYTES * 4
    }

    private sealed interface Address {
        data object Free : Address
        data class Direct(val start: Int, val generation: Int) : Address
        data class Compressed(val stream: Int, val index: Int) : Address
    }

    private data class Section(val trailer: Dictionary, val entries: Map<Int, Address>)
    private data class Entry(val value: Any, val streamStart: Int = -1)
    private val source: String
    private val addresses = HashMap<Int, Address>()
    private val objects = HashMap<Ref, Entry>()
    private val loadingStreams = HashSet<Int>()
    private val loadedStreams = HashSet<Int>()
    private var indexEntries = 0
    private var objectStreamBytes = 0
    val root: Ref
    private val info: Any?

    init {
        pdfRequire(data.size <= VectorPdf.MAX_FILE_BYTES, "PDF 超过 16 MB，请先拆分文件。")
        source = data.toString(Charsets.ISO_8859_1)
        pdfRequire(source.startsWith("%PDF-"), "请选择有效的 PDF 文件。")
        val marker = source.lastIndexOf("startxref")
        pdfRequire(marker >= 0)
        var start: Int? = PdfTokens(source, marker + "startxref".length).integer()
        var catalog: Ref? = null
        var information: Any? = null
        val visited = HashSet<Int>()
        while (start != null) {
            checkCancelled()
            pdfRequire(start in source.indices && visited.size < MAX_SECTIONS && visited.add(start))
            val reader = PdfTokens(source, start)
            val section = if (reader.word() == "xref") readTable(reader) else readIndexStream(start)
            val trailer = section.trailer
            pdfRequire(trailer["Encrypt"] == null, "暂不支持加密 PDF。")
            val supplement = trailer["XRefStm"]?.let { offset ->
                val position = integer(offset)
                pdfRequire(visited.size < MAX_SECTIONS && visited.add(position))
                readIndexStream(position).also {
                    pdfRequire(it.trailer["Encrypt"] == null, "暂不支持加密 PDF。")
                }
            }
            if (catalog == null) {
                val candidate = trailer["Root"] ?: supplement?.trailer?.get("Root")
                if (candidate != null) {
                    catalog = candidate as? Ref ?: throw PdfConversionException("PDF 目录引用无效。")
                }
            }
            if (information == null) information = trailer["Info"] ?: supplement?.trailer?.get("Info")
            // A hybrid stream can supply an entry marked free in the compatibility table.
            for ((id, address) in section.entries) {
                if (address is Address.Direct) addresses.putIfAbsent(id, address)
            }
            supplement?.entries?.forEach { (id, address) -> addresses.putIfAbsent(id, address) }
            section.entries.forEach { (id, address) -> addresses.putIfAbsent(id, address) }
            start = trailer["Prev"]?.let(::integer)
        }
        root = catalog ?: throw PdfConversionException("PDF 缺少文件目录。")
        info = information
    }

    fun title(): String? {
        val title = resolve((resolve(info) as? Dictionary)?.get("Title"))
        val bytes = when (title) {
            is Literal -> title.value.toByteArray(Charsets.ISO_8859_1)
            is Hex -> title.value.take(4096).chunked(2).map { it.padEnd(2, '0').toIntOrNull(16)?.toByte() ?: return null }.toByteArray()
            else -> return null
        }
        return if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
            bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
        } else bytes.toString(Charsets.ISO_8859_1)
    }

    private fun readTable(reader: PdfTokens): Section {
        val entries = HashMap<Int, Address>()
        while (true) {
            val next = reader.word()
            if (next == "trailer") break
            val start = next.toIntOrNull() ?: throw PdfConversionException("PDF 文件索引损坏。")
            val count = reader.integer()
            checkRange(start, count, MAX_OBJECTS)
            for (id in start until start + count) {
                val position = reader.integer()
                val generation = reader.integer()
                val address = when (reader.word()) {
                    "n" -> direct(position, generation)
                    "f" -> Address.Free
                    else -> throw PdfConversionException("PDF 文件索引损坏。")
                }
                pdfRequire(entries.put(id, address) == null)
            }
        }
        val trailer = reader.value() as? Dictionary ?: throw PdfConversionException("PDF 缺少文件目录。")
        return Section(trailer, entries)
    }

    private fun readIndexStream(start: Int): Section {
        pdfRequire(start in source.indices)
        val reader = PdfTokens(source, start)
        pdfRequire(reader.integer() in 1 until MAX_OBJECTS && reader.integer() == 0 && reader.word() == "obj")
        val entry = readEntry(reader)
        val trailer = entry.value as? Dictionary ?: throw PdfConversionException("PDF 索引流无效。")
        pdfRequire((trailer["Type"] as? Name)?.value == "XRef", "PDF 索引流无效。")
        val size = integer(trailer["Size"])
        pdfRequire(size in 1..MAX_OBJECTS)
        val widths = integers(trailer["W"])
        pdfRequire(widths.size == 3 && widths.all { it in 0..8 } && widths.sum() > 0)
        val ranges = trailer["Index"]?.let(::integers) ?: listOf(0, size)
        pdfRequire(ranges.size % 2 == 0 && ranges.isNotEmpty())
        val bytes = decode(entry, trailer, directDictionary = true)
        var at = 0
        fun field(width: Int): Int {
            pdfRequire(width <= bytes.size - at)
            var value = 0L
            repeat(width) {
                value = (value shl 8) + (bytes[at++].toInt() and 255)
                pdfRequire(value <= Int.MAX_VALUE)
            }
            return value.toInt()
        }
        val entries = HashMap<Int, Address>()
        for (i in ranges.indices step 2) {
            val first = ranges[i]
            val count = ranges[i + 1]
            checkRange(first, count, size)
            for (id in first until first + count) {
                val type = if (widths[0] == 0) 1 else field(widths[0])
                val location = field(widths[1])
                val generationOrIndex = field(widths[2])
                val address = when (type) {
                    1 -> direct(location, generationOrIndex)
                    2 -> {
                        pdfRequire(location in 1 until MAX_OBJECTS && generationOrIndex in 0 until MAX_OBJECTS)
                        Address.Compressed(location, generationOrIndex)
                    }
                    else -> Address.Free
                }
                pdfRequire(entries.put(id, address) == null)
            }
        }
        pdfRequire(at == bytes.size, "PDF 索引流长度无效。")
        return Section(trailer, entries)
    }

    private fun checkRange(start: Int, count: Int, limit: Int) {
        checkCancelled()
        pdfRequire(count in 0..limit && start in 0..(limit - count) && count <= MAX_INDEX_ENTRIES - indexEntries)
        indexEntries += count
    }

    private fun direct(start: Int, generation: Int): Address.Direct {
        pdfRequire(start in source.indices && generation in 0..65535)
        return Address.Direct(start, generation)
    }

    private fun readEntry(reader: PdfTokens): Entry {
        val value = reader.value()
        if (value !is Dictionary || reader.wordOrNull() != "stream") return Entry(value)
        var start = reader.position
        if (source.getOrNull(start) == '\r') start++
        if (source.getOrNull(start) == '\n') start++
        pdfRequire(start > reader.position)
        return Entry(value, start)
    }

    private fun entry(ref: Ref): Entry {
        objects[ref]?.let { return it }
        return when (val address = addresses[ref.id]) {
            is Address.Direct -> {
                pdfRequire(address.generation == ref.generation)
                val reader = PdfTokens(source, address.start)
                pdfRequire(reader.integer() == ref.id && reader.integer() == ref.generation && reader.word() == "obj")
                readEntry(reader).also { objects[ref] = it }
            }
            is Address.Compressed -> {
                pdfRequire(ref.generation == 0)
                if (address.stream !in loadedStreams) {
                    pdfRequire(loadingStreams.add(address.stream), "PDF 对象流包含循环引用。")
                    try {
                        readObjectStream(address.stream)
                    } finally {
                        loadingStreams.remove(address.stream)
                    }
                }
                objects[ref] ?: throw PdfConversionException("PDF 的压缩对象索引无效。")
            }
            else -> throw PdfConversionException("PDF 缺少被引用的内容。")
        }
    }

    private fun readObjectStream(id: Int) {
        checkCancelled()
        pdfRequire(loadingStreams.size <= 24)
        val address = addresses[id] as? Address.Direct ?: throw PdfConversionException("PDF 对象流的引用无效。")
        pdfRequire(address.generation == 0)
        val entry = entry(Ref(id, 0))
        val dictionary = entry.value as? Dictionary ?: throw PdfConversionException("PDF 对象流无效。")
        pdfRequire((dictionary["Type"] as? Name)?.value == "ObjStm")
        val count = integer(dictionary["N"])
        val first = integer(dictionary["First"])
        val decoded = decode(entry, dictionary)
        pdfRequire(count in 1 until MAX_OBJECTS && first in 1 until decoded.size)
        pdfRequire(decoded.size <= MAX_OBJECT_STREAM_BYTES - objectStreamBytes, "PDF 对象流超过安全处理上限。")
        objectStreamBytes += decoded.size
        val text = decoded.toString(Charsets.ISO_8859_1)
        val header = PdfTokens(text.substring(0, first))
        val objectsInStream = List(count) { header.integer() to header.integer() }
        pdfRequire(header.wordOrNull() == null && objectsInStream.first().second == 0)
        for ((index, item) in objectsInStream.withIndex()) {
            val (objectId, offset) = item
            val next = objectsInStream.getOrNull(index + 1)?.second ?: (text.length - first)
            pdfRequire(objectId in 1 until MAX_OBJECTS && offset in 0 until next && next <= text.length - first)
            val target = addresses[objectId] as? Address.Compressed
            if (target?.stream == id && target.index == index) {
                val reader = PdfTokens(text.substring(first + offset, first + next))
                val value = reader.value()
                pdfRequire(value !is String && value !is Ref && reader.wordOrNull() == null)
                objects[Ref(objectId, 0)] = Entry(value)
            }
        }
        loadedStreams.add(id)
    }

    fun resolve(value: Any?): Any? = if (value is Ref) entry(value).value else value

    fun stream(ref: Ref): ByteArray {
        val entry = entry(ref)
        val dictionary = entry.value as? Dictionary ?: throw PdfConversionException("PDF 数据流无效。")
        return decode(entry, dictionary)
    }

    private fun decode(entry: Entry, dictionary: Dictionary, directDictionary: Boolean = false): ByteArray {
        fun parameter(name: String): Any? = dictionary[name].let {
            if (directDictionary) it else resolve(it)
        }
        val length = integer(parameter("Length"))
        val start = entry.streamStart
        pdfRequire(start >= 0 && length in 0..MAX_STREAM_BYTES && length <= data.size - start)
        val filter = parameter("Filter")
        val parameters = parameter("DecodeParms")
        val compression = (if (filter is List<*>) filter.singleOrNull() else filter) as? Name
        if (filter == null || filter == PdfNull) return data.copyOfRange(start, start + length)
        pdfRequire(compression?.value == "FlateDecode", "PDF 使用了暂不支持的数据压缩方式。")
        val options = if (parameters is List<*>) {
            pdfRequire(parameters.size == 1)
            parameters.single()
        } else parameters
        pdfRequire(options == null || options == PdfNull || options is Dictionary)
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(data, start, length)).use { input ->
            val buffer = ByteArray(16_384)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                pdfRequire(out.size() + count <= MAX_STREAM_BYTES, "PDF 页面数据超过安全处理上限。")
                out.write(buffer, 0, count)
            }
        }
        return undoPredictor(out.toByteArray(), options as? Dictionary)
    }

    private fun undoPredictor(bytes: ByteArray, options: Dictionary?): ByteArray {
        fun option(key: String, default: Int) = options?.get(key)?.let(::integer) ?: default
        val predictor = option("Predictor", 1)
        if (predictor == 1) return bytes
        val columns = option("Columns", 1)
        val colors = option("Colors", 1)
        val bits = option("BitsPerComponent", 8)
        pdfRequire(columns in 1..MAX_STREAM_BYTES && colors in 1..32 && bits in listOf(1, 2, 4, 8, 16))
        val pixelSize = (colors * bits + 7) / 8
        val rowSize = (columns.toLong() * colors * bits + 7) / 8
        pdfRequire(rowSize in 1L..MAX_STREAM_BYTES.toLong())
        val row = rowSize.toInt()
        pdfRequire(predictor in 10..15 && bytes.size % (row + 1) == 0, "PDF 使用了暂不支持的压缩预测格式。")
        val rows = bytes.size / (row + 1)
        val result = ByteArray(rows * row)
        for (y in 0 until rows) {
            if (y % 1024 == 0) checkCancelled()
            val input = y * (row + 1)
            val output = y * row
            val method = bytes[input].toInt()
            pdfRequire(method in 0..4)
            for (x in 0 until row) {
                val left = if (x >= pixelSize) result[output + x - pixelSize].toInt() and 255 else 0
                val up = if (y > 0) result[output + x - row].toInt() and 255 else 0
                val upperLeft = if (x >= pixelSize && y > 0) result[output + x - row - pixelSize].toInt() and 255 else 0
                val predicted = when (method) {
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upperLeft)
                    else -> 0
                }
                result[output + x] = (bytes[input + 1 + x].toInt() + predicted).toByte()
            }
        }
        return result
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val toLeft = kotlin.math.abs(up - upperLeft)
        val toUp = kotlin.math.abs(left - upperLeft)
        val toUpperLeft = kotlin.math.abs(left + up - 2 * upperLeft)
        return when {
            toLeft <= toUp && toLeft <= toUpperLeft -> left
            toUp <= toUpperLeft -> up
            else -> upperLeft
        }
    }

    private fun integer(value: Any?): Int {
        val number = value as? Double ?: throw PdfConversionException("PDF 整数无效。")
        val integer = number.toInt()
        pdfRequire(number == integer.toDouble() && integer >= 0, "PDF 整数无效。")
        return integer
    }

    private fun integers(value: Any?): List<Int> {
        val values = value as? List<*> ?: throw PdfConversionException("PDF 列表无效。")
        return values.map(::integer)
    }
}

internal object PdfNull

internal class PdfTokens(private val text: String, start: Int = 0) {
    var position = start
        private set
    private var operations = 0

    private fun skipSpace() {
        while (position < text.length) {
            when (text[position]) {
                '%' -> while (position < text.length && text[position] !in "\r\n") position++
                '\u0000', '\t', '\n', '\u000c', '\r', ' ' -> position++
                else -> return
            }
        }
    }

    private fun boundary(c: Char) = c <= ' ' || c in "()<>[]{}/%"

    fun wordOrNull(): String? {
        if (++operations % 1024 == 0) checkCancelled()
        skipSpace()
        if (position == text.length) return null
        val first = position++
        val c = text[first]
        if (c in "<>" && text.getOrNull(position) == c) position++
        else if (c == '/') {
            while (position < text.length && !boundary(text[position])) position++
        } else if (!boundary(c)) {
            while (position < text.length && !boundary(text[position])) position++
        }
        pdfRequire(position - first <= 1024)
        return text.substring(first, position)
    }

    fun word(): String = wordOrNull() ?: throw PdfConversionException("PDF 数据提前结束。")

    fun integer(): Int = word().toIntOrNull() ?: throw PdfConversionException("PDF 整数无效。")

    private fun charsUntil(end: Char, maxLength: Int): String {
        val start = position
        val stop = text.indexOf(end, start)
        pdfRequire(stop >= start && stop - start <= maxLength)
        position = stop + 1
        return text.substring(start, stop)
    }

    private fun literal(): Literal {
        val out = StringBuilder()
        var depth = 1
        while (position < text.length && out.length <= 4096) {
            val c = text[position++]
            when (c) {
                '\\' -> out.append(text.getOrNull(position++) ?: break)
                '(' -> { depth++; out.append(c) }
                ')' -> {
                    if (--depth == 0) return Literal(out.toString())
                    out.append(c)
                }
                else -> out.append(c)
            }
        }
        throw PdfConversionException("PDF 文字数据无效。")
    }

    fun value(depth: Int = 0, references: Boolean = true): Any =
        readValue(word(), depth, references)

    fun nextOperand(): Any? = wordOrNull()?.let { readValue(it, 0, false) }

    private fun readValue(token: String, depth: Int, references: Boolean): Any {
        pdfRequire(depth < 32)
        return when {
            token == "<<" -> {
                val values = LinkedHashMap<String, Any>()
                while (true) {
                    val key = word()
                    if (key == ">>") break
                    pdfRequire(key.startsWith('/') && values.size < 4096)
                    values[key.substring(1)] = value(depth + 1, references)
                }
                Dictionary(values)
            }
            token == "[" -> {
                val values = ArrayList<Any>()
                while (true) {
                    val term = word()
                    if (term == "]") break
                    pdfRequire(values.size < 65536)
                    values.add(readValue(term, depth + 1, references))
                }
                values
            }
            token == "<" -> Hex(charsUntil('>', 131072).filterNot(Char::isWhitespace))
            token == "(" -> literal()
            token.startsWith('/') -> Name(token.substring(1))
            token == "true" || token == "false" -> token == "true"
            token == "null" -> PdfNull
            else -> {
                val number = token.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return token
                if (references && token.toIntOrNull() != null && number >= 0) {
                    val saved = position
                    val generation = wordOrNull()?.toIntOrNull()
                    if (generation != null && generation >= 0 && wordOrNull() == "R") {
                        return Ref(number.toInt(), generation)
                    }
                    position = saved
                }
                number
            }
        }
    }
}
