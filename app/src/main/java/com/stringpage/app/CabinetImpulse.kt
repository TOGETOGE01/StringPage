package com.stringpage.app

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** A prepared mono cabinet response, stored at the same rate as the guitar stems. */
internal data class CabinetImpulse(val name: String, val coefficients: List<Float>) {
    fun atRate(rate: Int): DoubleArray {
        val samples = DoubleArray(coefficients.size) { coefficients[it].toDouble() }
        if (rate == RATE) return samples
        val ratio = rate.toDouble() / RATE
        return resample(samples, ratio, ceil(samples.size * ratio).toInt()).also {
            // The coefficients represent a convolution kernel and their sum should retain its gain.
            for (index in it.indices) it[index] /= ratio
        }
    }

    companion object {
        const val RATE = 44100
        const val MAX_TAPS = 2048
        private const val MAX_WAVE_BYTES = 2 * 1024 * 1024

        fun readWave(name: String, input: InputStream): CabinetImpulse {
            val bytes = ByteArray(MAX_WAVE_BYTES + 1)
            var length = 0
            while (length < bytes.size) {
                val count = input.read(bytes, length, bytes.size - length)
                if (count < 0) break
                length += count
            }
            require(length <= MAX_WAVE_BYTES) { "箱体 IR 文件不能超过 2 MB。" }
            val wave = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(at: Int, value: String) = value.indices.all { bytes[at + it] == value[it].code.toByte() }
            require(length >= 12 && tag(0, "RIFF") && tag(8, "WAVE")) { "请选择 RIFF WAV 格式的箱体 IR。" }
            val end = wave.getInt(4).toLong() + 8L
            require(end in 12..length.toLong()) { "WAV 文件不完整。" }
            var format = -1
            var formatLength = 0
            var data = -1
            var dataLength = 0
            var offset = 12
            while (offset + 8 <= end) {
                val chunkLength = wave.getInt(offset + 4)
                val start = offset + 8
                require(chunkLength >= 0 && start.toLong() + chunkLength <= end) { "WAV 数据块不完整。" }
                when {
                    format < 0 && tag(offset, "fmt ") -> { format = start; formatLength = chunkLength }
                    data < 0 && tag(offset, "data") -> { data = start; dataLength = chunkLength }
                }
                offset = start + chunkLength + (chunkLength and 1)
            }
            require(format >= 0 && formatLength >= 16 && data >= 0) { "WAV 缺少音频格式或采样数据。" }
            var encoding = wave.getShort(format).toInt() and 0xffff
            val channels = wave.getShort(format + 2).toInt()
            val rate = wave.getInt(format + 4)
            val stride = wave.getShort(format + 12).toInt()
            val bits = wave.getShort(format + 14).toInt()
            if (encoding == 0xfffe && formatLength >= 40) {
                val guidTail = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
                if (guidTail.indices.all { wave.get(format + 28 + it) == guidTail[it] }) encoding = wave.getInt(format + 24)
            }
            val pcm = encoding == 1 && bits in setOf(16, 24, 32)
            val floating = encoding == 3 && bits == 32
            require((pcm || floating) && channels in 1..2 && rate in 8000..192000 && stride == channels * (bits / 8)) {
                "支持单声道或双声道 WAV，采样率 8–192 kHz，16／24／32 位 PCM 或 32 位浮点。"
            }
            require(dataLength >= stride && dataLength % stride == 0) { "WAV 的采样数据不完整。" }
            // A stereo IR uses its left microphone channel. Summing can cancel out-of-phase taps.
            val source = DoubleArray(dataLength / stride) { frame ->
                val at = data + frame * stride
                when {
                    floating -> wave.getFloat(at).toDouble()
                    bits == 16 -> wave.getShort(at) / 32768.0
                    bits == 24 -> ((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                        (bytes[at + 2].toInt() shl 16)) / 8388608.0
                    else -> wave.getInt(at) / 2147483648.0
                }
            }
            require(source.all { it.isFinite() && abs(it) <= 8.0 }) { "WAV 含有无效采样。" }
            val peak = source.maxOf { abs(it) }
            require(peak >= 1e-5) { "WAV 没有可用的箱体响应。" }
            val onset = source.indexOfFirst { abs(it) >= peak * 0.008 }
            val aligned = source.copyOfRange(onset, source.size)
            val ratio = RATE.toDouble() / rate
            val available = ceil(aligned.size * ratio).toInt()
            val samples = resample(aligned, ratio, minOf(available, MAX_TAPS))
            if (available > MAX_TAPS) {
                val fadeFrames = RATE / 250
                for (index in 0 until fadeFrames) {
                    samples[samples.size - fadeFrames + index] *= (1 + cos(PI * index / (fadeFrames - 1))) * 0.5
                }
            }
            val peakBand = listOf(160, 250, 400, 630, 1000, 1600, 2500, 4000).maxOf { frequency ->
                var real = 0.0
                var imag = 0.0
                for (index in samples.indices) {
                    val angle = 2 * PI * frequency * index / RATE
                    real += samples[index] * cos(angle)
                    imag += samples[index] * sin(angle)
                }
                sqrt(real * real + imag * imag)
            }
            require(peakBand >= 1e-7) { "WAV 在吉他频段内没有可用响应。" }
            val normalization = 0.85 / maxOf(peakBand, samples.maxOf { abs(it) })
            return CabinetImpulse(name.take(100).ifBlank { "自定义箱体 IR" }, samples.map { (it * normalization).toFloat() })
        }

        private fun resample(input: DoubleArray, ratio: Double, count: Int): DoubleArray {
            if (ratio == 1.0) return input.copyOf(count)
            val cutoff = minOf(ratio, 1.0)
            val radius = 16
            return DoubleArray(count) { index ->
                val position = index / ratio
                val middle = floor(position).toInt()
                var sum = 0.0
                for (tap in maxOf(0, middle - radius + 1)..minOf(input.lastIndex, middle + radius)) {
                    val distance = position - tap
                    val phase = PI * distance * cutoff
                    val sinc = if (abs(phase) < 1e-9) 1.0 else sin(phase) / phase
                    sum += input[tap] * sinc * cutoff * (0.5 + 0.5 * cos(PI * distance / radius))
                }
                sum
            }
        }
    }
}
