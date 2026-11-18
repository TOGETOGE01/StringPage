package com.stringpage.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Direct convolution for the first taps, with FFT partitions for the remainder. */
internal class CabinetConvolution(impulse: DoubleArray) {
    private val head = impulse.copyOf(minOf(impulse.size, PARTITION))
    private val history = DoubleArray(head.size)
    private var cursor = 0
    private val tail = if (impulse.size > PARTITION) PartitionedTail(impulse) else null

    fun process(sample: Double): Double {
        history[cursor] = sample
        var sum = 0.0
        for (tap in 0..cursor) sum += head[tap] * history[cursor - tap]
        for (tap in cursor + 1 until head.size) sum += head[tap] * history[head.size + cursor - tap]
        if (++cursor == head.size) cursor = 0
        // The direct head supplies the current block while the FFT prepares the next one.
        return sum + (tail?.process(sample) ?: 0.0)
    }

    fun reset() {
        history.fill(0.0)
        cursor = 0
        tail?.reset()
    }

    private class PartitionedTail(impulse: DoubleArray) {
        private val fft = Fft(PARTITION * 2)
        private val kernels = Array((impulse.size - 1) / PARTITION) { part ->
            val offset = (part + 1) * PARTITION
            Spectrum(fft.size).apply {
                impulse.copyInto(real, 0, offset, minOf(offset + PARTITION, impulse.size))
                fft.transform(this)
            }
        }
        private val input = Array(kernels.size) { Spectrum(fft.size) }
        private val sum = Spectrum(fft.size)
        private val output = DoubleArray(PARTITION)
        private val overlap = DoubleArray(PARTITION)
        private var partition = 0
        private var offset = 0

        fun process(sample: Double): Double {
            val result = output[offset]
            input[partition].real[offset++] = sample
            if (offset == PARTITION) {
                val block = input[partition]
                block.real.fill(0.0, PARTITION)
                block.imag.fill(0.0)
                fft.transform(block)
                sum.real.fill(0.0)
                sum.imag.fill(0.0)
                var previous = partition
                for (kernel in kernels) {
                    val recorded = input[previous]
                    for (bin in 0 until fft.size) {
                        sum.real[bin] += recorded.real[bin] * kernel.real[bin] - recorded.imag[bin] * kernel.imag[bin]
                        sum.imag[bin] += recorded.real[bin] * kernel.imag[bin] + recorded.imag[bin] * kernel.real[bin]
                    }
                    if (--previous < 0) previous = input.lastIndex
                }
                fft.transform(sum, inverse = true)
                for (frame in 0 until PARTITION) {
                    output[frame] = sum.real[frame] / fft.size + overlap[frame]
                    overlap[frame] = sum.real[frame + PARTITION] / fft.size
                }
                if (++partition == input.size) partition = 0
                offset = 0
            }
            return result
        }

        fun reset() {
            for (block in input) {
                block.real.fill(0.0)
                block.imag.fill(0.0)
            }
            output.fill(0.0)
            overlap.fill(0.0)
            partition = 0
            offset = 0
        }
    }

    private class Spectrum(size: Int) {
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
    }

    private class Fft(val size: Int) {
        private val bits = Integer.numberOfTrailingZeros(size)
        private val reverse = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
        private val cosine = DoubleArray(size / 2) { cos(2 * PI * it / size) }
        private val sine = DoubleArray(size / 2) { sin(2 * PI * it / size) }

        fun transform(data: Spectrum, inverse: Boolean = false) {
            val real = data.real
            val imag = data.imag
            for (index in 0 until size) {
                val target = reverse[index]
                if (target <= index) continue
                val realValue = real[index]
                val imagValue = imag[index]
                real[index] = real[target]
                imag[index] = imag[target]
                real[target] = realValue
                imag[target] = imagValue
            }
            var length = 2
            while (length <= size) {
                val half = length / 2
                val step = size / length
                for (start in 0 until size step length) {
                    for (offset in 0 until half) {
                        val low = start + offset
                        val high = low + half
                        val c = cosine[offset * step]
                        val s = sine[offset * step] * if (inverse) 1 else -1
                        val rightReal = real[high] * c - imag[high] * s
                        val rightImag = real[high] * s + imag[high] * c
                        real[high] = real[low] - rightReal
                        imag[high] = imag[low] - rightImag
                        real[low] += rightReal
                        imag[low] += rightImag
                    }
                }
                length *= 2
            }
        }
    }

    companion object { private const val PARTITION = 128 }
}
