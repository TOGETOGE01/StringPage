package com.stringpage.app.pdf

import kotlin.math.abs

/** Reads the page tree, fonts and page graphics needed by the vector TAB recognizer. */
internal class VectorPdf(bytes: ByteArray) {
    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private const val MAX_PAGES = 200
    }

    private data class Page(val resources: Dictionary, val contents: Any?, val bounds: PdfBox)
    private val pdf = PdfObjects(bytes)
    private val pages = ArrayList<Page>()
    private val fontCache = HashMap<Ref, PdfFont?>()
    val pageCount get() = pages.size
    fun pageWidth(index: Int) = pages[index].bounds.width

    init {
        val catalog = dictionary(pdf.root)
        collectPages(reference(catalog["Pages"]), null, null, null, null, 0, HashSet())
        pdfRequire(pages.isNotEmpty(), "PDF 中没有可读取的页面。")
    }

    private fun collectPages(
        ref: Ref, resources: Dictionary?, mediaBox: PdfBox?, cropBox: PdfBox?, rotation: Int?,
        depth: Int, seen: MutableSet<Ref>
    ) {
        checkCancelled()
        pdfRequire(depth <= 24 && seen.add(ref))
        val node = dictionary(ref)
        val pageResources = node["Resources"]?.let { dictionary(it) } ?: resources
        val media = node["MediaBox"]?.let(::box) ?: mediaBox
        val crop = node["CropBox"]?.let(::box) ?: cropBox
        val pageRotation = node["Rotate"]?.let { number(resolve(it)).toInt() } ?: rotation
        when ((node["Type"] as? Name)?.value) {
            "Pages" -> for (child in list(node["Kids"])) {
                collectPages(reference(child), pageResources, media, crop, pageRotation, depth + 1, seen)
            }
            "Page" -> {
                pdfRequire(pages.size < MAX_PAGES, "PDF 超过 200 页，请先拆分文件。")
                pdfRequire(media != null && pageResources != null)
                pdfRequire((pageRotation ?: 0) % 360 == 0, "请先将 PDF 页面转回正向。")
                val bounds = if (crop == null) media!! else crop.intersect(media!!)
                    ?: throw PdfConversionException("PDF 页面裁切范围无效。")
                pages.add(Page(pageResources!!, node["Contents"], bounds))
            }
            else -> throw PdfConversionException("PDF 的页目录无效。")
        }
    }

    fun geometry(index: Int): PdfGeometry {
        val page = pages[index]
        val content = page.contents ?: throw PdfConversionException("这一页没有谱面内容。")
        val refs = if (resolve(content) is List<*>) list(content).map(::reference) else listOf(reference(content))
        pdfRequire(refs.size in 1..24)
        val output = StringBuilder()
        for (ref in refs) {
            val decoded = pdf.stream(ref)
            pdfRequire(output.length + decoded.size <= PdfObjects.MAX_STREAM_BYTES, "页面过于复杂，请缩小识别范围。")
            output.append(decoded.toString(Charsets.ISO_8859_1)).append('\n')
        }
        val bounds = page.bounds
        val matrix = Matrix(d = -1.0, x = -bounds.left, y = bounds.bottom)
        val clip = PdfBox(0.0, 0.0, bounds.width, bounds.height)
        val drawing = PdfDrawingPage()
        PdfDrawingReader(drawingResources(page.resources), drawing, PdfGraphics(matrix, PdfClip(listOf(clip)))).read(output.toString())
        return drawing.geometry
    }

    private fun box(value: Any?): PdfBox {
        val numbers = list(value).map(::number)
        pdfRequire(numbers.size == 4)
        val (x1, y1, x2, y2) = numbers
        val box = PdfBox(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        pdfRequire(box.width > 0 && box.height > 0)
        return box
    }

    private fun drawingResources(resources: Dictionary): PdfDrawingResources {
        val fonts = resources["Font"]?.let(::dictionary)
        val objects = resources["XObject"]?.let(::dictionary)
        return PdfDrawingResources(
            font = { name ->
                (fonts?.get(name) as? Ref)?.let { ref -> fontCache.getOrPut(ref) { readFont(ref) } }
            },
            form = { name -> (objects?.get(name) as? Ref)?.let(::readForm) }
        )
    }

    private fun readForm(ref: Ref): PdfForm? {
        val form = dictionary(ref)
        if ((form["Subtype"] as? Name)?.value != "Form" || form["Ref"] != null ||
            form["OC"] != null || form["OPI"] != null) return null
        val matrix = form["Matrix"]?.let { value ->
            val items = list(value).map(::number)
            pdfRequire(items.size == 6)
            Matrix(items[0], items[1], items[2], items[3], items[4], items[5])
        } ?: Matrix()
        return PdfForm(ref, matrix, box(form["BBox"]), form["Resources"]?.let { drawingResources(dictionary(it)) }) {
            pdf.stream(ref).toString(Charsets.ISO_8859_1)
        }
    }

    private fun reference(value: Any?): Ref =
        value as? Ref ?: throw PdfConversionException("PDF 文件引用损坏。")

    private fun resolve(value: Any?): Any? = pdf.resolve(value)

    private fun dictionary(value: Any?): Dictionary =
        resolve(value) as? Dictionary ?: throw PdfConversionException("PDF 字典无效。")

    private fun list(value: Any?): List<*> =
        resolve(value) as? List<*> ?: throw PdfConversionException("PDF 列表无效。")

    private fun number(value: Any?): Double =
        value as? Double ?: throw PdfConversionException("PDF 数值无效。")

    private fun readFont(ref: Ref): PdfFont? {
        val font = dictionary(ref)
        if ((font["Encoding"] as? Name)?.value != "Identity-H") return null
        val name = (font["BaseFont"] as? Name)?.value?.substringAfter('+') ?: return null
        val mapping = font["ToUnicode"] as? Ref ?: return null
        val cmap = UnicodeMap.read(pdf.stream(mapping).toString(Charsets.ISO_8859_1))
        val descendant = dictionary(list(font["DescendantFonts"]).firstOrNull())
        val sizes = descendant["W"]?.let { list(it) }.orEmpty()
        val widths = HashMap<Int, Double>()
        var at = 0
        while (at < sizes.size) {
            val start = number(sizes[at++]).toInt()
            val next = sizes.getOrNull(at++)
            if (next is List<*>) {
                pdfRequire(start in 0..65535 && next.size <= 65536 - start)
                for ((i, width) in next.withIndex()) widths[start + i] = number(width)
            } else {
                val end = number(next).toInt()
                val width = number(sizes.getOrNull(at++))
                pdfRequire(start in 0..65535 && end in start..65535)
                for (cid in start..end) widths[cid] = width
            }
        }
        return PdfFont(name, cmap, widths, (descendant["DW"] as? Double) ?: 1000.0)
    }
}

internal data class PdfFont(
    val name: String, val unicode: Map<Int, String>, val widths: Map<Int, Double>, val defaultWidth: Double
)

private object UnicodeMap {
    fun read(input: String): Map<Int, String> {
        val reader = PdfTokens(input)
        val result = HashMap<Int, String>()
        var count = 0
        while (true) {
            checkCancelled()
            val term = reader.wordOrNull() ?: break
            when (term) {
                "beginbfchar" -> repeat(count) {
                    val cid = cid(reader.value())
                    result[cid] = unicode(reader.value())
                }
                "beginbfrange" -> repeat(count) {
                    val start = cid(reader.value())
                    val end = cid(reader.value())
                    pdfRequire(end in start..65535 && end - start < 16384)
                    when (val target = reader.value()) {
                        is List<*> -> {
                            pdfRequire(target.size == end - start + 1)
                            for (code in start..end) result[code] = unicode(target[code - start])
                        }
                        is Hex -> {
                            val initial = cid(target)
                            pdfRequire(initial + end - start <= 65535)
                            for (code in start..end) result[code] = (initial + code - start).toChar().toString()
                        }
                        else -> throw PdfConversionException("PDF 的字符编码不受支持。")
                    }
                }
            }
            count = term.toIntOrNull()?.coerceIn(0, 65536) ?: 0
        }
        return result
    }

    private fun cid(value: Any?): Int {
        val hex = (value as? Hex)?.value ?: throw PdfConversionException("PDF 中的字符映射无效。")
        return hex.toIntOrNull(16)?.takeIf { it in 0..65535 }
            ?: throw PdfConversionException("PDF 的字符编码不受支持。")
    }

    private fun unicode(value: Any?): String {
        val hex = (value as? Hex)?.value ?: throw PdfConversionException("PDF 中的字符映射无效。")
        pdfRequire(hex.length in 4..16 && hex.length % 4 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_16BE)
    }
}

internal data class PdfPoint(val x: Double, val y: Double)
internal data class PdfLine(val from: PdfPoint, val to: PdfPoint)
internal data class PdfCurve(val from: PdfPoint, val control1: PdfPoint, val control2: PdfPoint, val to: PdfPoint)
internal data class PdfBox(val left: Double, val top: Double, val right: Double, val bottom: Double)
internal data class PdfText(
    val x: Double, val y: Double, val right: Double, val size: Double, val font: String, val text: String,
    val formScale: Double = 1.0
)
internal data class PdfBeam(val left: Double, val right: Double, val leftTop: Double, val rightTop: Double, val thickness: Double) {
    fun centerAt(x: Double) = leftTop + (rightTop - leftTop) * ((x - left) / (right - left)).coerceIn(0.0, 1.0) + thickness / 2
}
internal class PdfGeometry {
    val lines = ArrayList<PdfLine>()
    val dashes = ArrayList<PdfLine>()
    val curves = ArrayList<PdfCurve>()
    val filledArcs = ArrayList<PdfCurve>()
    val closedCurves = ArrayList<PdfBox>()
    val fills = ArrayList<PdfBox>()
    val beams = ArrayList<PdfBeam>()
    val text = ArrayList<PdfText>()
    var containsExternalDrawing = false

    /** Keep recognition tolerances in the original notation units after a uniform page resize. */
    fun normalizeFormScale(): Double {
        val scales = text.filter { it.text in TAB_CLEFS }.map { it.formScale }
        val scale = scales.firstOrNull() ?: return 1.0
        if (abs(scale - 1.0) < 1e-6 || scales.any { abs(it - scale) > scale * 1e-5 }) return 1.0
        fun PdfPoint.normalized() = PdfPoint(x / scale, y / scale)
        fun PdfBox.normalized() = PdfBox(left / scale, top / scale, right / scale, bottom / scale)
        fun PdfLine.normalized() = PdfLine(from.normalized(), to.normalized())
        fun PdfCurve.normalized() = PdfCurve(from.normalized(), control1.normalized(), control2.normalized(), to.normalized())
        lines.replaceAll { it.normalized() }
        dashes.replaceAll { it.normalized() }
        curves.replaceAll { it.normalized() }
        filledArcs.replaceAll { it.normalized() }
        closedCurves.replaceAll { it.normalized() }
        fills.replaceAll { it.normalized() }
        beams.replaceAll {
            PdfBeam(it.left / scale, it.right / scale, it.leftTop / scale, it.rightTop / scale, it.thickness / scale)
        }
        text.replaceAll { it.copy(x = it.x / scale, y = it.y / scale, right = it.right / scale,
            size = it.size / scale, formScale = 1.0) }
        return scale
    }

    companion object {
        val TAB_CLEFS = setOf("\ue06d", "\ue06e")
    }
}

private data class Matrix(
    val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
    val d: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0
) {
    fun point(px: Double, py: Double) = PdfPoint(a * px + c * py + x, b * px + d * py + y)
    fun after(m: Matrix): Matrix {
        val p = point(m.x, m.y)
        return Matrix(a*m.a + c*m.b, b*m.a + d*m.b, a*m.c + c*m.d, b*m.c + d*m.d, p.x, p.y)
    }

    fun axisAligned() = (abs(b) < 1e-7 && abs(c) < 1e-7) || (abs(a) < 1e-7 && abs(d) < 1e-7)

    fun uniformScale(): Double {
        val width = abs(a) + abs(c)
        val height = abs(b) + abs(d)
        return width.takeIf { it in 0.1..10.0 && abs(it - height) <= it * 1e-5 } ?: 1.0
    }

    fun bounds(box: PdfBox) = listOf(point(box.left, box.top), point(box.right, box.top),
        point(box.left, box.bottom), point(box.right, box.bottom)).bounds()
}

private val PdfBox.width get() = right - left
private val PdfBox.height get() = bottom - top

private fun PdfBox.intersect(other: PdfBox): PdfBox? =
    PdfBox(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
        .takeIf { it.width > 0.0 && it.height > 0.0 }

private fun PdfBox.contains(point: PdfPoint): Boolean = point.x in (left - 0.005)..(right + 0.005) &&
    point.y in (top - 0.005)..(bottom + 0.005)

private fun List<PdfPoint>.bounds(): PdfBox =
    PdfBox(minOf { it.x }, minOf { it.y }, maxOf { it.x }, maxOf { it.y })

private data class PdfDrawingResources(
    val font: (String) -> PdfFont?,
    val form: (String) -> PdfForm?
)

private data class PdfForm(
    val ref: Ref,
    val matrix: Matrix,
    val bounds: PdfBox,
    val resources: PdfDrawingResources?,
    val content: () -> String
)

private data class PdfGraphics(
    val matrix: Matrix,
    val clip: PdfClip,
    val dashed: Boolean = false,
    val darkFill: Boolean = true,
    val font: PdfFont? = null,
    val fontSize: Double = 0.0,
    val formScale: Double = 1.0
)

private const val MAX_CLIP_RECTANGLES = 1024

private class PdfClip(val rectangles: List<PdfBox>) {
    val empty get() = rectangles.isEmpty()
    fun contains(point: PdfPoint) = rectangles.any { it.contains(point) }
    fun within(bounds: PdfBox) = PdfClip(rectangles.mapNotNull { it.intersect(bounds) })
    fun within(region: List<PdfBox>): PdfClip {
        pdfRequire(region.size <= MAX_CLIP_RECTANGLES, "PDF 裁切区域过于复杂。")
        val intersection = ArrayList<PdfBox>()
        for (old in rectangles) {
            region.mapNotNullTo(intersection, old::intersect)
            pdfRequire(intersection.size <= MAX_CLIP_RECTANGLES, "PDF 裁切区域过于复杂。")
        }
        return PdfClip(intersection)
    }
}

private class PdfDrawingPage {
    val geometry = PdfGeometry()
    val activeForms = HashSet<Ref>()
    var formCount = 0
    var decodedBytes = 0
}

private class PdfDrawingReader(
    private val resources: PdfDrawingResources,
    private val page: PdfDrawingPage,
    private var graphics: PdfGraphics
) {
    private val stack = ArrayDeque<PdfGraphics>()
    private val result get() = page.geometry
    private val segments = ArrayList<PdfLine>()
    private val curves = ArrayList<PdfCurve>()
    private val points = ArrayList<PdfPoint>()
    private val subpaths = ArrayList<Int>()
    private var currentPoint: PdfPoint? = null
    private var firstPoint: PdfPoint? = null
    private var evenOddClip: Boolean? = null
    private var textMatrix = Matrix()
    private var textLine = Matrix()
    private val textRun = StringBuilder()
    private var textStart = PdfPoint(0.0, 0.0)
    private var textRight = 0.0
    private var textSize = 0.0

    fun read(content: String) {
        pdfRequire(content.length <= PdfObjects.MAX_STREAM_BYTES - page.decodedBytes, "页面过于复杂，请缩小识别范围。")
        page.decodedBytes += content.length
        val reader = PdfTokens(content)
        val operands = ArrayList<Any>()
        while (true) {
            val next = reader.nextOperand() ?: break
            if (next is String) {
                command(next, operands)
                operands.clear()
            } else {
                pdfRequire(operands.size < 64)
                operands.add(next)
            }
            pdfRequire(result.lines.size + result.dashes.size + result.curves.size +
                result.closedCurves.size + result.filledArcs.size + result.fills.size + result.text.size + points.size < 40_000, "PDF 页面元素过多。")
        }
        flushText()
        pdfRequire(stack.isEmpty(), "PDF 页面绘制指令不完整。")
    }

    private fun command(operator: String, args: List<Any>) {
        fun n(index: Int): Double = args.getOrNull(index) as? Double
            ?: throw PdfConversionException("PDF 页面绘制参数无效。")
        fun transform() = Matrix(n(0), n(1), n(2), n(3), n(4), n(5))
        fun p(index: Int) = graphics.matrix.point(n(index), n(index + 1))
        when (operator) {
            "q" -> { pdfRequire(stack.size < 64); stack.addLast(graphics) }
            "Q" -> { flushText(); graphics = stack.removeLastOrNull() ?: throw PdfConversionException("PDF 页面绘制栈无效。") }
            "cm" -> { flushText(); graphics = graphics.copy(matrix = graphics.matrix.after(transform())) }
            "d" -> graphics = graphics.copy(dashed = (args.firstOrNull() as? List<*>)?.isNotEmpty() == true)
            "rg", "g", "sc", "scn" -> graphics = graphics.copy(darkFill = args.filterIsInstance<Double>().let {
                it.isNotEmpty() && it.all { value -> value < 0.2 }
            })
            "m" -> move(p(0))
            "l" -> line(p(0))
            "re" -> {
                val x = n(0); val y = n(1); val width = n(2); val height = n(3)
                val m = graphics.matrix
                move(m.point(x, y))
                line(m.point(x + width, y))
                line(m.point(x + width, y + height))
                line(m.point(x, y + height))
                closePath()
            }
            "h" -> closePath()
            "c" -> {
                val end = p(4)
                val start = currentPoint ?: throw PdfConversionException("PDF 路径缺少起点。")
                curves.add(PdfCurve(start, p(0), p(2), end))
                points.add(end)
                currentPoint = end
            }
            "S", "s", "B", "B*", "b", "b*" -> {
                if (operator in setOf("s", "b", "b*")) closePath()
                stroke()
                if (operator.startsWith("B", true)) fill()
                clearPath()
            }
            "f", "F", "f*" -> { fill(); clearPath() }
            "W", "W*" -> evenOddClip = operator == "W*"
            "n" -> clearPath()
            "BT" -> { flushText(); textMatrix = Matrix(); textLine = Matrix() }
            "ET" -> flushText()
            "Tf" -> {
                flushText()
                val font = (args.firstOrNull() as? Name)?.value?.let(resources.font)
                graphics = graphics.copy(font = font, fontSize = n(1))
            }
            "Tm" -> { flushText(); textMatrix = transform(); textLine = textMatrix }
            "Td", "TD" -> {
                textLine = textLine.after(Matrix(x = n(0), y = n(1)))
                textMatrix = textLine
            }
            "Tj" -> show(args.firstOrNull())
            "TJ" -> for (part in (args.firstOrNull() as? List<*>).orEmpty()) {
                if (part is Double) textMatrix = textMatrix.after(Matrix(x = -part * graphics.fontSize / 1000))
                else show(part)
            }
            "Do" -> drawForm((args.firstOrNull() as? Name)?.value)
            "BI", "sh" -> if (!graphics.clip.empty) result.containsExternalDrawing = true
        }
    }

    private fun drawForm(name: String?) {
        val parentClip = graphics.clip
        if (parentClip.empty) return
        val form = name?.let(resources.form)
        if (form == null) {
            result.containsExternalDrawing = true
            return
        }
        val matrix = graphics.matrix.after(form.matrix)
        if (!matrix.axisAligned()) {
            result.containsExternalDrawing = true
            return
        }
        val clip = parentClip.within(matrix.bounds(form.bounds))
        if (clip.empty) return
        pdfRequire(page.activeForms.size < 16 && page.activeForms.add(form.ref), "PDF 绘图包含循环引用或嵌套层级过多。")
        pdfRequire(++page.formCount <= 1024, "PDF 页面嵌套绘图过多。")
        try {
            flushText()
            val childGraphics = graphics.copy(matrix = matrix, clip = clip, formScale = matrix.uniformScale())
            PdfDrawingReader(form.resources ?: resources, page, childGraphics).read(form.content())
        } finally {
            page.activeForms.remove(form.ref)
        }
    }

    private fun show(value: Any?) {
        val selected = graphics.font ?: return
        val fontSize = graphics.fontSize
        val scale = graphics.formScale
        val clip = graphics.clip
        if (clip.empty) return
        val hex = (value as? Hex)?.value ?: return
        pdfRequire(hex.length % 4 == 0 && fontSize in 0.01..512.0)
        for (i in hex.indices step 4) {
            val code = hex.substring(i, i + 4).toIntOrNull(16) ?: throw PdfConversionException("PDF 字符编码无效。")
            val advance = (selected.widths[code] ?: selected.defaultWidth) * fontSize / 1000.0
            val matrix = graphics.matrix.after(textMatrix)
            val start = matrix.point(0.0, 0.0)
            val end = matrix.point(advance, 0.0)
            val mapped = selected.unicode[code].orEmpty()
            val size = kotlin.math.abs(matrix.a) * fontSize
            if (abs(end.y - start.y) > 0.2 * scale || size < 0.01 || !clip.contains(start) || !clip.contains(end)) {
                flushText()
            } else {
                if (textRun.isNotEmpty() && (abs(start.y - textStart.y) > 0.15 * scale ||
                        start.x - textRight > size * 0.43 || start.x < textRight - size * 0.5)) flushText()
                if (textRun.isEmpty()) {
                    textStart = start
                    textSize = size
                }
                textRun.append(mapped)
                textRight = end.x
                pdfRequire(textRun.length <= 4096)
            }
            textMatrix = textMatrix.after(Matrix(x = advance))
        }
    }

    private fun flushText() {
        val selected = graphics.font
        if (selected != null && textRun.isNotEmpty()) {
            result.text.add(PdfText(textStart.x, textStart.y, textRight, textSize, selected.name, textRun.toString(),
                graphics.formScale))
        }
        textRun.clear()
    }

    private fun move(point: PdfPoint) {
        subpaths.add(points.size)
        points.add(point)
        currentPoint = point
        firstPoint = point
    }
    private fun line(point: PdfPoint) {
        val start = currentPoint ?: throw PdfConversionException("PDF 路径缺少起点。")
        segments.add(PdfLine(start, point))
        points.add(point)
        currentPoint = point
    }
    private fun closePath() { firstPoint?.takeIf { it != currentPoint }?.let(::line) }
    private fun clearPath() {
        evenOddClip?.let(::applyClip)
        evenOddClip = null
        segments.clear()
        curves.clear()
        points.clear()
        subpaths.clear()
        currentPoint = null
        firstPoint = null
    }

    private data class ClipRectangle(val bounds: PdfBox, val winding: Int)

    private fun applyClip(evenOdd: Boolean) {
        if (graphics.clip.empty) return
        // Paired notation can cut a small opening around each printed notehead.
        if (curves.isNotEmpty() || subpaths.size > MAX_CLIP_RECTANGLES) {
            result.containsExternalDrawing = true
            return
        }
        val rectangles = ArrayList<ClipRectangle>()
        for ((index, start) in subpaths.withIndex()) {
            val path = points.subList(start, subpaths.getOrElse(index + 1) { points.size })
            val bounds = path.bounds()
            if (bounds.width == 0.0 || bounds.height == 0.0) continue
            val corners = if (path.first() == path.last()) path.dropLast(1) else path
            if (corners.size != 4 || corners.distinct().size != 4 ||
                corners.any { (it.x != bounds.left && it.x != bounds.right) || (it.y != bounds.top && it.y != bounds.bottom) } ||
                corners.indices.any { i ->
                    val a = corners[i]
                    val b = corners[(i + 1) % 4]
                    a.x != b.x && a.y != b.y
                }) {
                result.containsExternalDrawing = true
                return
            }
            val (a, b, c) = corners
            val turn = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            rectangles.add(ClipRectangle(bounds, if (turn > 0) 1 else -1))
        }
        flushText()
        graphics = graphics.copy(clip = graphics.clip.within(clipRegion(rectangles, evenOdd)))
    }

    private fun clipRegion(rectangles: List<ClipRectangle>, evenOdd: Boolean): List<PdfBox> {
        if (rectangles.size <= 1) return rectangles.map { it.bounds }
        val yEdges = rectangles.flatMap { listOf(it.bounds.top, it.bounds.bottom) }.distinct().sorted()
        val regions = ArrayList<PdfBox>()
        for ((top, bottom) in yEdges.zipWithNext()) {
            val edges = sortedMapOf<Double, Int>()
            for ((box, winding) in rectangles) {
                if (box.top > top || box.bottom < bottom) continue
                val delta = if (evenOdd) 1 else winding
                edges[box.left] = (edges[box.left] ?: 0) + delta
                edges[box.right] = (edges[box.right] ?: 0) - delta
            }
            var winding = 0
            var left = 0.0
            fun filled() = if (evenOdd) winding % 2 != 0 else winding != 0
            for ((x, delta) in edges) {
                val before = filled()
                winding += delta
                if (before && !filled()) {
                    regions.add(PdfBox(left, top, x, bottom))
                    pdfRequire(regions.size <= MAX_CLIP_RECTANGLES, "PDF 裁切区域过于复杂。")
                }
                if (!before && filled()) left = x
            }
        }
        return regions
    }

    private fun pathBounds(): PdfBox? = points.takeIf { it.size >= 4 }?.bounds()

    private fun PdfLine.visibleParts(clip: PdfClip): List<PdfLine> {
        if (clip.rectangles.any { it.contains(from) && it.contains(to) }) return listOf(this)
        val dx = to.x - from.x
        val dy = to.y - from.y
        fun interval(box: PdfBox): Pair<Double, Double>? {
            var start = 0.0
            var end = 1.0
            fun restrict(origin: Double, delta: Double, low: Double, high: Double): Boolean {
                if (delta == 0.0) return origin in low..high
                val a = (low - origin) / delta
                val b = (high - origin) / delta
                start = maxOf(start, minOf(a, b))
                end = minOf(end, maxOf(a, b))
                return start < end
            }
            return if (restrict(from.x, dx, box.left, box.right) &&
                restrict(from.y, dy, box.top, box.bottom)) start to end else null
        }
        val intervals = clip.rectangles.mapNotNull(::interval).sortedBy { it.first }
        val merged = ArrayList<Pair<Double, Double>>()
        for ((start, end) in intervals) {
            val previous = merged.lastOrNull()
            if (previous != null && start <= previous.second + 1e-8) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, end)
            } else merged.add(start to end)
        }
        return merged.map { (start, end) ->
            PdfLine(PdfPoint(from.x + dx * start, from.y + dy * start), PdfPoint(from.x + dx * end, from.y + dy * end))
        }
    }

    private fun stroke() {
        val clip = graphics.clip
        val lines = if (graphics.dashed) result.dashes else result.lines
        for (segment in segments) {
            lines.addAll(segment.visibleParts(clip))
            pdfRequire(lines.size < 40_000, "PDF 页面元素过多。")
        }
        curves.filterTo(result.curves) { clip.contains(it.from) && clip.contains(it.to) }
        if (curves.size == 4 && segments.isEmpty() && curves.first().from == curves.last().to &&
            points.all(clip::contains)) pathBounds()?.let { result.closedCurves.add(it) }
    }

    private fun fill() {
        val clip = graphics.clip
        if (!graphics.darkFill || clip.empty) return
        pathBounds()?.let { bounds -> clip.rectangles.mapNotNullTo(result.fills) { it.intersect(bounds) } }
        if (!points.all(clip::contains)) return
        if (curves.size == 2 && curves.first().from == curves.last().to &&
            curves.first().to == curves.last().from) result.filledArcs.add(curves.first())
        if (curves.isEmpty() && points.size in 4..5) {
            val corners = points.distinct().sortedBy { it.x }
            val scale = graphics.formScale
            if (corners.size == 4 && corners[2].x - corners[1].x >= 1.5 * scale &&
                corners[1].x - corners[0].x < 0.06 * scale && corners[3].x - corners[2].x < 0.06 * scale) {
                val a = corners.take(2).sortedBy { it.y }
                val b = corners.takeLast(2).sortedBy { it.y }
                val thickness = a[1].y - a[0].y
                if (thickness in (0.5 * scale)..(4.0 * scale) && abs(thickness - (b[1].y - b[0].y)) < 0.2 * scale) {
                    result.beams.add(PdfBeam(a[0].x, b[0].x, a[0].y, b[0].y, thickness))
                }
            }
        }
    }
}
