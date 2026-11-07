@file:OptIn(kotlin.contracts.ExperimentalContracts::class, ExperimentalUnsignedTypes::class)

package com.stringpage.app

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.exporter.Gp7Exporter
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.EndOfTrackEvent
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileFormat
import alphaTab.midi.MidiFileGenerator
import alphaTab.midi.MidiTrack
import alphaTab.midi.TempoChangeEvent
import alphaTab.midi.TimeSignatureEvent
import alphaTab.model.BackingTrack
import alphaTab.model.JsonConverter
import alphaTab.model.Score
import java.io.OutputStream

/**
 * Serializes the score model, independently of player speed, loop range, visible tracks and
 * temporary player transposition. The caller owns document selection, threading and stream lifetime.
 *
 * GP output is alphaTab's GP7/8 representation, not a lossless copy of the original desktop file.
 * MIDI is SMF type 1: model volume/pan/program, notes and tempo are used; model-muted tracks are
 * omitted. Solo is a monitoring state and is not used to infer additional track exclusion.
 */
object ScoreExport {
    enum class Format(val extension: String, val mimeType: String) {
        GUITAR_PRO("gp", "application/octet-stream"),
        MIDI("mid", "audio/midi")
    }

    fun encode(score: Score, format: Format): ByteArray = when (format) {
        Format.GUITAR_PRO -> guitarProBytes(score)
        Format.MIDI -> midiBytes(score)
    }

    /** Keeps embedded recording bytes and sync points represented by the imported score model. */
    fun guitarProBytes(score: Score): ByteArray = com.stringpage.app.rse.GpPlayingStyleExport.restore(
        score, Gp7Exporter().export(snapshot(score, true)).bytes())

    /** Exports the full score at its authored tempo, leaving practice settings out of the file. */
    fun midiBytes(score: Score): ByteArray {
        val copy = snapshot(score, false)
        val generated = MidiFile().apply { format = MidiFileFormat.MultiTrack }
        val handler = AlphaSynthMidiFileHandler(generated, true)
        MidiFileGenerator(copy, Settings(), handler).generate()

        // A dedicated conductor track retains timing even when the first instrument is muted.
        // Filter tracks after generation: channel-volume automations must not unmute an omitted part.
        val result = MidiFile().apply {
            format = MidiFileFormat.MultiTrack
            division = generated.division
            tickShift = generated.tickShift
        }
        val conductor = MidiTrack()
        result.tracks.push(conductor)
        var endTick = 0.0
        for ((index, track) in generated.tracks.withIndex()) {
            val audible = index < copy.tracks.length && !copy.tracks[index].playbackInfo.isMute
            val destination = if (audible) MidiTrack() else null
            val destinationIndex = result.tracks.length
            var trackEndTick = 0.0
            for (event in track.events) {
                endTick = maxOf(endTick, event.tick)
                trackEndTick = maxOf(trackEndTick, event.tick)
                if (event is TempoChangeEvent || event is TimeSignatureEvent) {
                    event.track = 0.0
                    conductor.addEvent(event)
                } else if (destination != null && event !is EndOfTrackEvent) {
                    event.track = destinationIndex
                    destination.addEvent(event)
                }
            }
            if (destination != null) {
                destination.addEvent(EndOfTrackEvent(destinationIndex, trackEndTick))
                result.tracks.push(destination)
            }
        }
        conductor.addEvent(EndOfTrackEvent(0.0, endTick))
        return result.toBinary().bytes()
    }

    /** Writes to a CreateDocument/contentResolver stream without closing or flushing that stream. */
    fun writeTo(score: Score, format: Format, output: OutputStream) {
        output.write(encode(score, format))
    }

    fun limitations(format: Format): List<String> = when (format) {
        Format.GUITAR_PRO -> listOf(
            "重新生成 GP7/8 文件，仅能保存当前模型支持的数据；不是原文件的无损另存。",
            "内嵌录音字节与同步点保留；原厂 RSE、效果器、Audio Track 混音/滤波参数和部分排版信息不能完整保留。"
        )
        Format.MIDI -> listOf(
            "MIDI 保存演奏事件，不包含内嵌录音、RSE 音色、效果链或谱面排版。",
            "采用谱面原有速度与模型音符、音量、声像及音色；不应用练习速度、循环、可见轨道或临时播放器移调。",
            "模型中静音的轨道不导出；Solo 状态不额外排除其他轨道。",
            "独立音符弯音转为传统 MIDI 通道弯音，外部音源对技法的解释可能不同。"
        )
    }

    private fun snapshot(score: Score, includeAudio: Boolean): Score {
        require(score.masterBars.length > 0 && score.tracks.length > 0) { "当前没有可导出的乐谱。" }
        require(score.tracks.all { it.staves.length > 0 }) { "乐谱包含没有谱表的轨道。" }
        val copy = JsonConverter.jsObjectToScore(JsonConverter.scoreToJsObject(score), Settings())
        // The JSON serializer deliberately omits rawAudioFile. Copy it explicitly; do not lose audio.
        val raw = if (includeAudio) score.backingTrack?.rawAudioFile else null
        copy.backingTrack = if (raw != null) BackingTrack().apply {
            rawAudioFile = Uint8Array(raw.bytes().asUByteArray())
        } else null
        return copy
    }

    private fun Uint8Array.bytes(): ByteArray = buffer.asByteArray()
        .copyOfRange(byteOffset.toInt(), (byteOffset + length).toInt())
}
