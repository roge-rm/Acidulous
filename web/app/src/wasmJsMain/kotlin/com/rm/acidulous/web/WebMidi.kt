package com.rm.acidulous.web

import com.rm.acidulous.midi.MidiBluetooth
import com.rm.acidulous.midi.MidiDeviceDesc
import com.rm.acidulous.midi.MidiOpenDevice
import com.rm.acidulous.midi.MidiSendPort
import com.rm.acidulous.midi.MidiSystem
import com.rm.acidulous.midi.MidiWorker
import com.rm.acidulous.util.Log
import com.rm.acidulous.util.Runnable

private const val TAG = "Acidulous.MIDI"

// The browser's Web MIDI, kept by the page in globalThis.acidMidi. Access is
// the browser's to grant: asked for at once where it was granted before, and
// otherwise at the first touch or key, as sound is - not with a prompt over a
// page nobody has looked at yet. SysEx is asked for with it; a Launchpad is
// put in Programmer mode by one.

private fun midiStart(changed: () -> Unit): Unit = js(
    """(() => {
        if (!navigator.requestMIDIAccess) return;
        const open = () => navigator.requestMIDIAccess({ sysex: true }).then((access) => {
            globalThis.acidMidi = access;
            access.onstatechange = () => changed();
            changed();
        }).catch((e) => console.warn('W/Acidulous.MIDI: no MIDI access', e));
        const onGesture = () => { removeEventListener('pointerdown', onGesture, true); removeEventListener('keydown', onGesture, true); open(); };
        const later = () => { addEventListener('pointerdown', onGesture, true); addEventListener('keydown', onGesture, true); };
        if (navigator.permissions) {
            navigator.permissions.query({ name: 'midi', sysex: true })
                .then((p) => { if (p.state === 'granted') open(); else if (p.state === 'prompt') later(); })
                .catch(later);
        } else later();
    })()""",
)

private fun midiHas(): Boolean = js("!!navigator.requestMIDIAccess")

/** Every port: kind (i or o), id, name and maker, a field apart; ports a line apart. */
private fun midiPorts(): String = js(
    """(() => {
        const a = globalThis.acidMidi;
        if (!a) return '';
        const out = [];
        a.inputs.forEach((p) => { if (p.state === 'connected') out.push(['i', p.id, p.name || '', p.manufacturer || ''].join('\u0001')); });
        a.outputs.forEach((p) => { if (p.state === 'connected') out.push(['o', p.id, p.name || '', p.manufacturer || ''].join('\u0001')); });
        return out.join('\u0000');
    })()""",
)

private fun midiListen(id: String, heard: (String, Double) -> Unit): Unit = js(
    """(() => {
        const p = globalThis.acidMidi && globalThis.acidMidi.inputs.get(id);
        if (!p) return;
        p.onmidimessage = (e) => heard(String.fromCharCode.apply(null, e.data), e.timeStamp);
    })()""",
)

private fun midiUnlisten(id: String): Unit = js(
    "(() => { const p = globalThis.acidMidi && globalThis.acidMidi.inputs.get(id); if (p) p.onmidimessage = null; })()",
)

private fun midiSend(id: String, bytes: String, atMs: Double): Unit = js(
    """(() => {
        const p = globalThis.acidMidi && globalThis.acidMidi.outputs.get(id);
        if (!p) return;
        const a = new Uint8Array(bytes.length);
        for (let i = 0; i < bytes.length; i++) a[i] = bytes.charCodeAt(i);
        try { p.send(a, atMs); } catch (e) { console.warn('W/Acidulous.MIDI: not sent', e); }
    })()""",
)

/** Where the page's performance.now() starts, in ms from 1970. */
private fun pageOrigin(): Double = js("performance.timeOrigin")

private fun setTimer(task: () -> Unit, ms: Double): Int = js("setTimeout(task, ms)")
private fun clearTimer(id: Int): Unit = js("clearTimeout(id)")

/** A port as Web MIDI lists it. */
private class WebPort(val output: Boolean, val id: String, val name: String, val maker: String)

/** Ports of one name, as the hub's device: those it hears from and those it sends to. */
private class WebDevice(val desc: MidiDeviceDesc, val sources: List<String>, val destinations: List<String>)

/**
 * MIDI in a browser. Web MIDI lists ports, not devices; a port the device
 * sends from and one it takes, under one name, are one device here, as a
 * phone would show it. Everything happens on the page's one thread, which is
 * the hub's worker too.
 */
class WebMidi : MidiSystem {
    override val supported: Boolean = midiHas()
    override val bluetooth: MidiBluetooth? = null

    override val worker: MidiWorker = object : MidiWorker {
        private val timers = HashMap<Runnable, MutableList<Int>>()
        override fun post(task: Runnable) = postDelayed(task, 0)
        override fun postDelayed(task: Runnable, delayMs: Long) {
            var id = 0
            id = setTimer({
                timers[task]?.remove(id)
                task.run()
            }, delayMs.coerceAtLeast(0).toDouble())
            timers.getOrPut(task) { mutableListOf() } += id
        }
        override fun removeCallbacks(task: Runnable) {
            timers.remove(task)?.forEach { clearTimer(it) }
        }
    }

    private var known: List<WebDevice> = emptyList()

    private fun scan(): List<WebDevice> {
        val joined = midiPorts()
        val ports = if (joined.isEmpty()) emptyList() else joined.split('\u0000').mapNotNull { line ->
            val f = line.split('\u0001')
            if (f.size < 4) null else WebPort(f[0] == "o", f[1], f[2], f[3])
        }.filter { !it.name.startsWith("Midi Through") }
        // Windows names a device's second and third ports after its first:
        // "MIDIIN2 (LPProMK3 MIDI)", "MIDIOUT3 (LPProMK3 MIDI)". They are
        // that device's ports, so they join it - after its own, which is port
        // nought, the one the hub plays and sends to.
        val windowsPort = Regex("""^MIDI(?:IN|OUT)\d+ \((.+)\)$""")
        fun deviceOf(p: WebPort) = windowsPort.find(p.name)?.groupValues?.get(1) ?: p.name
        return ports.groupBy { deviceOf(it) }.map { (name, all) ->
            val own = all.sortedBy { it.name != name }
            val sources = own.filter { !it.output }.map { it.id }
            val destinations = own.filter { it.output }.map { it.id }
            val maker = own.first().maker.ifEmpty { null }
            WebDevice(
                MidiDeviceDesc(
                    id = name.hashCode() and 0x7fffffff,
                    name = name,
                    product = listOfNotNull(maker, name).joinToString(" "),
                    maker = maker,
                    inputPortCount = destinations.size,
                    outputPortCount = sources.size,
                    // A browser does not say how a device is attached, and a
                    // controller in a browser is a USB one as good as always.
                    usb = true,
                    bluetooth = false,
                ),
                sources, destinations,
            )
        }.also { known = it }
    }

    override val devices: List<MidiDeviceDesc> get() = scan().map { it.desc }

    override fun openDevice(device: MidiDeviceDesc, done: (MidiOpenDevice?) -> Unit) {
        worker.post {
            val found = known.firstOrNull { it.desc.id == device.id } ?: scan().firstOrNull { it.desc.id == device.id }
            done(found?.let { Opened(it) })
        }
    }

    override fun watch(added: (MidiDeviceDesc) -> Unit, removed: (MidiDeviceDesc) -> Unit) {
        var before = known.associateBy { it.desc.id }
        midiStart {
            val now = scan().associateBy { it.desc.id }
            for ((id, d) in before) if (id !in now) { Log.i(TAG, "gone ${d.desc.name}"); removed(d.desc) }
            for ((id, d) in now) if (id !in before) { Log.i(TAG, "device ${d.desc.name} (in ${d.sources.size}, out ${d.destinations.size})"); added(d.desc) }
            before = now
        }
    }

    private class Opened(private val device: WebDevice) : MidiOpenDevice {
        override val desc = device.desc
        private val heard = mutableListOf<String>()

        override fun openInputPort(index: Int): MidiSendPort? {
            val id = device.destinations.getOrNull(index) ?: return null
            return object : MidiSendPort {
                override fun send(bytes: ByteArray, offset: Int, count: Int) =
                    midiSend(id, latin1(bytes, offset, count), 0.0)
                // Web MIDI waits for itself, on performance.now()'s clock;
                // System.nanoTime here counts from 1970, the engine's base.
                override fun send(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) =
                    midiSend(id, latin1(bytes, offset, count), timestamp / 1_000_000.0 - pageOrigin())
                override fun close() {}
            }
        }

        override fun connectOutputPort(index: Int, onSend: (ByteArray, Int, Int, Long) -> Unit) {
            val id = device.sources.getOrNull(index) ?: return
            heard += id
            midiListen(id) { data, atMs ->
                val bytes = ByteArray(data.length) { data[it].code.toByte() }
                runCatching { onSend(bytes, 0, bytes.size, ((atMs + pageOrigin()) * 1_000_000.0).toLong()) }
                    .onFailure { Log.w(TAG, "a message from ${desc.name} could not be handled", it) }
            }
        }

        override fun close() {
            heard.forEach { midiUnlisten(it) }
            heard.clear()
        }
    }
}

private fun latin1(bytes: ByteArray, offset: Int, count: Int): String =
    CharArray(count) { (bytes[offset + it].toInt() and 0xff).toChar() }.concatToString()
