package com.stringpage.app.rse

/** Independent AMAudio 8.1.3.67 VolumePan block processor.
 *
 * Supported native domain: matched mono or stereo buffers, normalized finite
 * controls, nonnegative frame counts, disjoint buffers or exact in-place aliasing.
 * Mono uses the native left gain. No extra pan law, clipping or normalization is
 * added here. >2 active channels would read beyond the native two-gain temporary
 * array and are deliberately outside this port's contract.
 *
 * The observed transition is a block-length ramp in gain, evaluated independently
 * at each sample index. Reset snaps the next active block; bypass copies samples
 * and preserves the previous active-block state.
 */
class NativeVolumePanProcessor(volume: Float = 0.8f, pan: Float = 0.5f) {
    var volume: Float = volume
        set(value) { validate(value); field = value }
    var pan: Float = pan
        set(value) { validate(value); field = value }
    var bypassed: Boolean = false

    private var previousVolume = 0.8f
    private var previousPan = 0.5f
    private var first = true

    init { validate(volume); validate(pan) }

    fun reset() { first = true }

    /** Planar input/output, matching the native pointer-array buffer layout. */
    fun process(
        input: Array<FloatArray>, output: Array<FloatArray>, frames: Int,
        inputOffset: Int = 0, outputOffset: Int = 0
    ) {
        require(input.size in 1..2 && output.size == input.size)
        require(frames >= 0 && inputOffset >= 0 && outputOffset >= 0)
        for (channel in input.indices) {
            require(inputOffset <= input[channel].size && frames <= input[channel].size - inputOffset)
            require(outputOffset <= output[channel].size && frames <= output[channel].size - outputOffset)
            for (other in output.indices) {
                if (input[channel] === output[other]) {
                    require(channel == other && inputOffset == outputOffset) { "Only exact in-place aliasing is supported" }
                }
            }
        }
        if (output.size == 2) require(output[0] !== output[1])
        if (bypassed) {
            for (channel in input.indices) input[channel].copyInto(output[channel], outputOffset, inputOffset, inputOffset + frames)
            return
        }
        val gains = prepare()
        for (channel in input.indices) {
            val start = if (channel == 0) gains.start.left else gains.start.right
            val target = if (channel == 0) gains.target.left else gains.target.right
            val step = if (gains.changed && frames > 0) (target - start) / frames.toFloat() else 0f
            for (index in 0 until frames) {
                val gain = if (gains.changed) start + index.toFloat() * step else target
                output[channel][outputOffset + index] = input[channel][inputOffset + index] * gain
            }
        }
    }

    /** Layout adapter for the interleaved stereo mixer, sharing exactly the same state/ramp. */
    fun processStereo(samples: FloatArray, frames: Int, offsetFrames: Int = 0) {
        require(frames >= 0 && offsetFrames >= 0 && offsetFrames <= samples.size / 2 && frames <= samples.size / 2 - offsetFrames)
        if (bypassed) return
        val gains = prepare()
        val leftStep = if (gains.changed && frames > 0) (gains.target.left - gains.start.left) / frames.toFloat() else 0f
        val rightStep = if (gains.changed && frames > 0) (gains.target.right - gains.start.right) / frames.toFloat() else 0f
        for (index in 0 until frames) {
            val left = if (gains.changed) gains.start.left + index.toFloat() * leftStep else gains.target.left
            val right = if (gains.changed) gains.start.right + index.toFloat() * rightStep else gains.target.right
            val position = (offsetFrames + index) * 2
            samples[position] *= left; samples[position + 1] *= right
        }
    }

    private data class BlockGains(
        val start: NativeVolumePanCurve.Gains,
        val target: NativeVolumePanCurve.Gains,
        val changed: Boolean
    )

    private fun prepare(): BlockGains {
        if (first) { previousVolume = volume; previousPan = pan; first = false }
        val changed = previousVolume != volume || previousPan != pan
        val target = NativeVolumePanCurve.gains(volume, pan)
        val start = if (changed) NativeVolumePanCurve.gains(previousVolume, previousPan) else target
        // Native locks the target even for a zero-frame active block.
        if (changed) { previousVolume = volume; previousPan = pan }
        return BlockGains(start, target, changed)
    }

    private fun validate(value: Float) { require(value.isFinite() && value in 0f..1f) }
}
