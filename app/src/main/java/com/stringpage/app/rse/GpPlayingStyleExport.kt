@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app.rse

import alphaTab.model.Score
import com.stringpage.app.GeneratedGpZip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Restores authored PlayingStyle into our own generated ZIP. Other native
 * instrument presets/FX and percussion definition round trips are not implied.
 * Only the trusted exporter output reaches this writer, never arbitrary input. */
object GpPlayingStyleExport {
    fun restore(score: Score, generated: ByteArray): ByteArray {
        val properties = GpRseProperties.get(score)
        require(properties == null || !properties.hasGpif || properties.tracks.size == score.tracks.length.toInt()) {
            "原谱音源设置与当前声部数量不一致，无法保留演奏风格。"
        }
        val output = ByteArrayOutputStream()
        var updated = false
        ZipOutputStream(output).use { target ->
            GeneratedGpZip.members(generated) { name, input ->
                    target.putNextEntry(ZipEntry(name))
                    if (name.substringAfterLast('/') == "score.gpif" && properties != null) {
                        require(!updated); updated = true
                        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                            .parse(ByteArrayInputStream(input.readBytes()))
                        val root = document.documentElement
                        val tracks = root.child("Tracks")?.children("Track").orEmpty().associateBy { it.getAttribute("id") }
                        val order = root.child("MasterTrack")?.child("Tracks")?.textContent.orEmpty()
                            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val ordered = if (order.isEmpty()) tracks.values.toList() else order.map { tracks.getValue(it) }
                        require(ordered.size == score.tracks.length.toInt())
                        for ((index, track) in ordered.withIndex()) {
                            val style = track.child("PlayingStyle") ?: document.createElement("PlayingStyle").also { track.appendChild(it) }
                            // Missing authored style is native Default, even for a guitar.
                            // The underlying exporter otherwise invents StringedPick from GM.
                            style.textContent = properties.track(index)?.rawPlayingStyle ?: "Default"
                        }
                        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(target))
                    } else input.copyTo(target)
                    if (name.substringAfterLast('/') == "score.gpif") updated = true
                    target.closeEntry()
            }
        }
        require(updated) { "导出文件缺少 GPIF。" }
        return output.toByteArray()
    }

    private fun Element.children(name: String): List<Element> = buildList {
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element && child.tagName == name) add(child)
            child = child.nextSibling
        }
    }
    private fun Element.child(name: String): Element? = children(name).firstOrNull()
}
