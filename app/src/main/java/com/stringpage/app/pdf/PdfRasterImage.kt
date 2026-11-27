package com.stringpage.app.pdf

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

internal data class RasterBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val midX get() = (left + right - 1) / 2.0
    val midY get() = (top + bottom - 1) / 2.0
}

/** Grayscale ink coverage, 0 for white and 255 for black. */
internal class PdfRasterImage(val width: Int, val height: Int, private val ink: ByteArray) {
    init { require(width > 0 && height > 0 && ink.size.toLong() == width.toLong() * height) }
    operator fun get(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) ink[y * width + x].toInt() and 255 else 0

    fun filter(area: RasterBox, threshold: Int = 68, suppress: (Int, Int) -> Boolean = { _, _ -> false }): RasterPatch {
        val data = ByteArray(area.width * area.height)
        for (y in 0 until area.height) for (x in 0 until area.width) {
            val px = x + area.left
            val py = y + area.top
            val value = this[px, py]
            if (value >= threshold && !suppress(px, py)) data[y * area.width + x] = value.toByte()
        }
        return RasterPatch(area, PdfRasterImage(area.width, area.height, data))
    }

    fun detectSlope(): Double {
        val step = (width / 460).coerceAtLeast(1)
        val columns = (width + step - 1) / step
        val rows = (height + step - 1) / step
        val mini = IntArray(columns * rows)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                mini[y * columns + x] = maxOf(this[x * step, y * step], this[x * step, y * step + step / 2])
            }
        }
        fun score(slope: Double): Double {
            val energy = DoubleArray(rows)
            for (x in 0 until columns step 2) {
                val offset = ((x - columns / 2) * slope).roundToInt()
                for (y in 0 until rows) {
                    val target = y - offset
                    if (target in energy.indices) energy[target] += mini[y * columns + x] / 255.0
                }
            }
            // A deskewed staff puts most of its dark horizontal energy on a few rows.
            return energy.sumOf { it * it * it }
        }
        val upright = score(0.0)
        var best = upright
        var slope = 0.0
        for (sample in -16..16) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (sample == 0) continue
            val candidate = tan(Math.toRadians(sample * 0.25))
            val value = score(candidate)
            if (value > best) { best = value; slope = candidate }
        }
        return slope.takeIf { best > upright * 1.14 } ?: 0.0
    }

    fun deskew(slope: Double): PdfRasterImage {
        if (abs(slope) < 0.0001) return this
        val corrected = ByteArray(ink.size)
        for (x in 0 until width) {
            val offset = ((x - width / 2.0) * slope).roundToInt()
            for (y in 0 until height) corrected[y * width + x] = this[x, y + offset].toByte()
        }
        return PdfRasterImage(width, height, corrected)
    }
}

internal class RasterPatch(val box: RasterBox, val pixels: PdfRasterImage) {
    fun components(threshold: Int, foreground: Boolean = true): List<RasterBox> {
        val width = pixels.width
        val height = pixels.height
        val seen = BooleanArray(width * height)
        val pending = IntArray(seen.size)
        val result = ArrayList<RasterBox>()
        for (index in seen.indices) {
            if (seen[index] || (pixels[index % width, index / width] >= threshold) != foreground) continue
            var count = 1
            pending[0] = index
            seen[index] = true
            var left = width
            var right = -1
            var top = height
            var bottom = -1
            fun visit(position: Int) {
                if (seen[position] || (pixels[position % width, position / width] >= threshold) != foreground) return
                seen[position] = true
                pending[count++] = position
            }
            var next = 0
            while (next < count) {
                val position = pending[next++]
                val x = position % width
                val y = position / width
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                if (x > 0) visit(position - 1)
                if (x < width - 1) visit(position + 1)
                if (y > 0) visit(position - width)
                if (y < height - 1) visit(position + width)
            }
            result.add(RasterBox(box.left + left, box.top + top, box.left + right + 1, box.top + bottom + 1))
        }
        return result
    }

    fun crop(area: RasterBox): RasterPatch? {
        if (area.width <= 0 || area.height <= 0) return null
        val a = RasterBox(area.left - box.left, area.top - box.top, area.right - box.left, area.bottom - box.top)
        val clipped = pixels.filter(a, 0).pixels
        return RasterPatch(area, clipped)
    }

    fun bounds(threshold: Int = 70): RasterBox? {
        var minX = pixels.width
        var maxX = -1
        var minY = pixels.height
        var maxY = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y] >= threshold) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        return if (maxX >= 0) RasterBox(box.left + minX, box.top + minY, box.left + maxX + 1, box.top + maxY + 1) else null
    }

    fun columns(gap: Int, minInk: Int = 110): List<RasterBox> {
        val result = ArrayList<RasterBox>()
        var first = -1
        var last = -1
        for (x in 0 until pixels.width) {
            if ((0 until pixels.height).sumOf { y -> pixels[x, y] } < minInk) continue
            if (first >= 0 && x - last > gap + 1) {
                result.add(RasterBox(box.left + first, box.top, box.left + last + 1, box.bottom))
                first = -1
            }
            if (first < 0) first = x
            last = x
        }
        if (first >= 0) result.add(RasterBox(box.left + first, box.top, box.left + last + 1, box.bottom))
        return result
    }

    fun shape(): RasterShape? {
        val bounds = bounds() ?: return null
        val image = crop(bounds)?.pixels ?: return null
        val samples = FloatArray(RasterShape.COLUMNS * RasterShape.ROWS)
        for (y in 0 until RasterShape.ROWS) for (x in 0 until RasterShape.COLUMNS) {
            var ink = 0
            for (dy in 0..2) for (dx in 0..2) {
                val sx = ((x + (dx + 0.5) / 3) * image.width / RasterShape.COLUMNS).toInt()
                val sy = ((y + (dy + 0.5) / 3) * image.height / RasterShape.ROWS).toInt()
                ink += image[sx, sy]
            }
            samples[y * RasterShape.COLUMNS + x] = (ink / (9f * 205f)).coerceAtMost(1f)
        }
        return RasterShape(bounds.width.toDouble() / bounds.height, samples)
    }
}

internal data class RasterShape(val aspect: Double, val samples: FloatArray) {
    fun distance(other: RasterShape): Double {
        var error = 0.0
        var union = 0.0
        for (i in samples.indices) {
            error += abs(samples[i] - other.samples[i])
            union += maxOf(samples[i], other.samples[i])
        }
        return error / union.coerceAtLeast(1.0) + 0.24 * abs(kotlin.math.ln(aspect / other.aspect))
    }
    companion object {
        const val COLUMNS = 16
        const val ROWS = 19
    }
}
