package com.stringpage.app.rse

/** Cue/cursor rules observed in AMAudio 8.1.3.67, independently of note scheduling.
 *
 * The decoder selects the first two accepted cue records by position,
 * regardless of their labels. GP7SamplePlayer then clamps the selected frame to
 * [0, AudioStream.loopStartFrame]. That boundary remains zero on an Ogg without
 * sampler loops; it must not be replaced with the sample's total frame count.
 *
 * A null boundary means the player has no AudioStream. The native cue setter
 * then does nothing, while the explicit-frame setter only clamps to zero.
 * NativeNoteTechnique selects TAP/LEGATO in the observed interpreter order.
 * initialFrame also applies SampleAccessor's default 4000-output-frame fallback;
 * a user-modified desktop debug setting is outside this playback policy.
 * Raw cues exposed by Gp7Bank's permissive metadata inspector must not be passed
 * as native decoder cues automatically: the original decoder ignores a "cue "
 * comment key with a trailing space, but accepts "cue". Actual banks contain the
 * former. This module models the getter/state rules after native parsing.
 */
object NativeSampleStart {
    const val DEFAULT_USER_CUE_FRAME = 4000L
    data class Cursor(val startingFrame: Long = 0, val currentFrame: Long = 0)

    /** OggVorbisDecoder::cuePoint; native source offsets are signed 32-bit. */
    fun decoderCuePoint(cueOffsets: List<Long>, cuePoint: Int): Long {
        require(cueOffsets.all { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() })
        return if (cueOffsets.size >= 2 && cuePoint in 0..1) cueOffsets[cuePoint] else 0L
    }

    /** AudioStream stores integer-keyed cue frames. A missing key yields zero. */
    fun setStartingCuePoint(
        cursor: Cursor, cuePoint: Int, effectiveLoopStartFrame: Long?,
        streamCuePoints: Map<Int, Long>
    ): Cursor {
        if (effectiveLoopStartFrame == null) return cursor
        val streamKey = when (cuePoint) { 0 -> 0; 1 -> 1; else -> 2 }
        return setStartingFrame(cursor, streamCuePoints[streamKey] ?: 0L, effectiveLoopStartFrame)
    }

    /** An unchanged starting frame preserves playback position instead of retriggering. */
    fun setStartingFrame(cursor: Cursor, requested: Long, effectiveLoopStartFrame: Long?): Cursor {
        require(effectiveLoopStartFrame == null || effectiveLoopStartFrame >= 0)
        val nonnegative = requested.coerceAtLeast(0)
        val frame = effectiveLoopStartFrame?.let { minOf(nonnegative, it) } ?: nonnegative
        return if (frame == cursor.startingFrame) cursor else Cursor(frame, frame)
    }

    fun reset(cursor: Cursor): Cursor = cursor.copy(currentFrame = cursor.startingFrame)

    /** Actual AudioStream constructor in AMAudio 8.1.3.67 inserts key 1,
     * sorts once, then appends key 0 without clearing its sorted flag. Its
     * ascending lower-bound lookup therefore misses both entries. Retain this
     * observed constructor behavior separately from the general getter above.
     * Cue frames are stored directly, without source-rate conversion.
     */
    fun decoderStreamCuePoints(decoderOffsets: List<Long>): Map<Int,Long> {
        val entries = listOf(1 to decoderCuePoint(decoderOffsets,1),0 to decoderCuePoint(decoderOffsets,0))
        return (0..2).associateWith { key ->
            var low = 0; var high = entries.size
            while (low < high) { val middle = (low+high) ushr 1
                if (entries[middle].first < key) low = middle+1 else high = middle }
            entries.getOrNull(low)?.takeIf { it.first == key }?.second ?: 0L
        }
    }

    /** SampleAccessor setter/finalize fallback. The loop-start cap is the
     * native AudioStream coordinate and survives disabled sustain looping. */
    fun initialFrame(cuePoint: Int, streamCuePoints: Map<Int,Long>, loopStart: Long, userDefinedFrame: Long = 0): Long {
        require(cuePoint in 0..3 && loopStart >= 0)
        val selected = (if(cuePoint == 3) setStartingFrame(Cursor(),userDefinedFrame,loopStart)
            else setStartingCuePoint(Cursor(),cuePoint,loopStart,streamCuePoints)).startingFrame
        return if (cuePoint != 2 && selected == 0L)
            setStartingFrame(Cursor(),DEFAULT_USER_CUE_FRAME,loopStart).startingFrame else selected
    }
}
