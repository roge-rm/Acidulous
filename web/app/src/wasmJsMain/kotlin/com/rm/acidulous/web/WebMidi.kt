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
// requested straight away if it was granted before, otherwise at the first
// touch or key press, like sound, so there's no prompt on a page nobody has
// looked at yet. SysEx is requested too, since it puts a Launchpad in
// Programmer mode.

private fun midiStart(changed: () -> Unit): Unit = js(
    """(() => {
        if (!navigator.requestMIDIAccess) return;
        const open = () => navigator.requestMIDIAccess({ sysex: true }).then((access) => {
            globalThis.acidMidi = access;
            access.onstatechange = () => changed();
            const said = [];
            access.inputs.forEach((p) => said.push('in ' + p.name + ' [' + p.manufacturer + ', ' + p.state + ']'));
            access.outputs.forEach((p) => said.push('out ' + p.name + ' [' + p.manufacturer + ', ' + p.state + ']'));
            console.log('I/Acidulous.MIDI: the browser offers ' + (said.length ? said.join('; ') : 'nothing') + (access.sysexEnabled ? ' (SysEx allowed)' : ' (no SysEx)'));
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

/** Every connected port as kind (i or o), id, name and maker, separated by \u0001; ports separated by \u0000. */
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

/** The ports with one name, as one device: the ones it sends from and the ones it receives on. */
private class WebDevice(val desc: MidiDeviceDesc, val sources: List<String>, val destinations: List<String>)

/**
 * MIDI in a browser. Web MIDI lists ports, not devices, so an input and an
 * output with the same name become one device, like on a phone. Everything
 * runs on the page's single thread, which is also the hub's worker.
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
        // Windows names a device's extra ports after its first, like
        // "MIDIIN2 (LPProMK3 MIDI)" and "MIDIOUT3 (LPProMK3 MIDI)". They join
        // that device, after its own port 0, which is the one the hub uses.
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
                    // Browsers don't say how a device is attached, and it's
                    // nearly always USB.
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
            // A device whose ports changed is reported as removed and added
            // again. Chrome on Windows can announce a device's ports one at a
            // time, so a Launchpad could arrive with inputs but no output yet,
            // and the hub never set up Programmer mode when the output showed
            // up. Re-adding it gives the hub every port at once.
            for ((id, d) in before) {
                val then = now[id]
                if (then == null || then.sources != d.sources || then.destinations != d.destinations) {
                    Log.i(TAG, if (then == null) "gone ${d.desc.name}" else "ports changed: ${d.desc.name}")
                    removed(d.desc)
                }
            }
            for ((id, d) in now) {
                val was = before[id]
                if (was == null || was.sources != d.sources || was.destinations != d.destinations) {
                    Log.i(TAG, "device ${d.desc.name} (in ${d.sources.size}, out ${d.destinations.size})")
                    added(d.desc)
                }
            }
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
                // Web MIDI schedules the send itself on performance.now()'s
                // clock. System.nanoTime here counts from 1970, the engine's base.
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
