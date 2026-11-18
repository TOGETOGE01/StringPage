package com.stringpage.app

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executor

/** Owns one external MIDI port. Score changes and chord timing stay on the UI thread. */
internal class MidiInputController(
    context: Context,
    private val canCapture: () -> Boolean,
    private val write: (List<MidiAttack>) -> Unit,
    private val changed: (name: String?, error: String?) -> Unit
) : Closeable {
    data class Port(val device: MidiDeviceInfo, val number: Int, val label: String)

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MidiManager::class.java)
    private var device: MidiDevice? = null
    private var output: MidiOutputPort? = null
    private var selection: Port? = null
    private var session = 0
    private var closed = false
    private val pending = linkedMapOf<Int, Int>()
    private val chordKeys = mutableSetOf<Int>()
    private val held = mutableSetOf<Int>()
    private val commit = Runnable(::finishChord)
    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (selection?.device?.id == info.id) {
                disconnect()
                if (!closed) changed(null, "MIDI 设备已断开。")
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager?.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, Executor { handler.post(it) }, callback)
        } else registerLegacy()
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() { manager?.registerDeviceCallback(callback, handler) }

    val isSupported get() = manager != null
    val portLabel get() = selection?.takeIf { output != null }?.label

    @Suppress("DEPRECATION")
    fun ports(): List<Port> {
        val service = manager ?: return emptyList()
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else service.devices.toList()
        return devices.flatMap { info ->
            val properties = info.properties
            val title = properties.getString(MidiDeviceInfo.PROPERTY_NAME)?.takeIf { it.isNotBlank() }
                ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)?.takeIf { it.isNotBlank() }
                ?: "MIDI ${info.id}"
            info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.map { port ->
                val tail = port.name?.takeIf { it.isNotBlank() } ?: "${port.portNumber + 1} 号端口"
                Port(info, port.portNumber, "$title · $tail")
            }
        }.sortedBy { it.label }
    }

    fun connect(port: Port) {
        val service = manager ?: return
        if (closed) return
        disconnect()
        selection = port
        val token = session
        try {
            service.openDevice(port.device, { handle ->
                if (closed || token != session) {
                    runCatching { handle?.close() }
                    return@openDevice
                }
                device = handle
                val source = runCatching { handle?.openOutputPort(port.number) }.getOrNull()
                if (source == null) {
                    disconnect()
                    changed(null, "无法打开 MIDI 端口。")
                    return@openDevice
                }
                output = source
                val receiver = object : MidiReceiver() {
                    private val stream = MidiByteStream()
                    @Synchronized
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        stream.read(data, offset, count, { message ->
                            handler.post { if (session == token) accept(message) }
                        }, { channel ->
                            handler.post { if (session == token) clearChannel(channel) }
                        })
                    }
                    @Synchronized override fun onFlush() {
                        stream.reset()
                        handler.post { if (session == token) clearGesture() }
                    }
                }
                if (runCatching { source.connect(receiver) }.isFailure) {
                    disconnect()
                    changed(null, "无法接收 MIDI 端口数据。")
                    return@openDevice
                }
                changed(port.label, null)
            }, handler)
        } catch (_: RuntimeException) {
            disconnect()
            changed(null, "无法连接 MIDI 设备。")
        }
    }

    private fun accept(event: MidiByteStream.Note) {
        if (!canCapture()) { clearGesture(); return }
        val key = event.channel * 128 + event.pitch
        if (event.velocity == 0) {
            held.remove(key)
            if (pending.isNotEmpty() && chordKeys.none(held::contains)) finishChord()
            return
        }
        if (key in chordKeys) finishChord()
        if (pending.isEmpty()) handler.postDelayed(commit, 75)
        pending[event.pitch] = maxOf(pending[event.pitch] ?: 0, event.velocity)
        held.add(key)
        chordKeys.add(key)
    }

    private fun clearChannel(channel: Int) {
        if (channel < 0) { clearGesture(); return }
        held.removeAll { it / 128 == channel }
        if (pending.isNotEmpty() && chordKeys.none(held::contains)) finishChord()
    }

    private fun finishChord() {
        handler.removeCallbacks(commit)
        if (pending.isEmpty()) return
        val chord = pending.map { (pitch, velocity) -> MidiAttack(pitch, velocity) }
        pending.clear()
        chordKeys.clear()
        if (canCapture()) write(chord)
    }

    fun clearGesture() {
        handler.removeCallbacks(commit)
        pending.clear()
        chordKeys.clear()
        held.clear()
    }

    fun disconnect() {
        session++
        clearGesture()
        runCatching { output?.close() }
        runCatching { device?.close() }
        output = null
        device = null
        selection = null
    }

    override fun close() {
        closed = true
        disconnect()
        manager?.unregisterDeviceCallback(callback)
    }
}
