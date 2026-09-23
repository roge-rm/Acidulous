#pragma once
#include <cstdint>
#include <engine/core/RtQueue.h>

// What the UI thread sends the audio thread, besides objects to mount.
namespace acidulous {

/**
 * A MIDI byte on its way *out*, stamped with the frame it belongs on.
 *
 * A frame, not a tick and not a sample offset, because it is the only
 * quantity that survives the trip to Java: there it becomes a wall-clock
 * nanosecond through the audio stream's own presentation anchor, and Android
 * schedules the send. Which means the sender has to be *early*, not fast.
 */
struct MidiOutEvent {
    int64_t frame = 0;
    uint8_t status = 0;
    uint8_t data1 = 0;
    uint8_t data2 = 0;
    uint8_t rack = 0xff; // 0xff: the transport's own, belonging to no track
};

/** A realtime byte arriving from outside, on the frame it was heard. */
struct MidiInEvent {
    int64_t frame = 0;
    uint8_t status = 0;
    uint8_t data1 = 0;
    uint8_t data2 = 0;
};

struct MidiMessage {
    uint8_t status = 0; // channel in the low nibble == rack
    uint8_t data1 = 0;
    uint8_t data2 = 0;
    /**
     * The channel it actually arrived on, 0-15, or [kNoChannel].
     *
     * The status nibble is spoken for - it carries the rack - and MPE is
     * entirely about which channel a message came in on, so the channel
     * needs a byte of its own. Everything the app generates itself has no
     * channel and says so.
     */
    uint8_t channel = 0xff;
};

constexpr uint8_t kNoChannel = 0xff;

// The ordinal crosses the queue and the JNI boundary but is never written
// to a file - lanes are keyed by unit *name* - so inserting here is safe,
// as long as Recorder.UNITS on the Kotlin side is kept in the same order.
// Appended, never inserted: the ordinal crosses the queue and the JNI
// boundary, and Recorder.UNITS on the Kotlin side is this list by position.
// Automation lanes are keyed by unit *name* in the document, so a new unit
// costs nothing to songs already written.
enum class Unit : uint8_t {
    Machine, Effect1, Effect2, Mod1, Mod2, Mod3, Channel, Master,
    /**
     * The two send buses' effects.
     *
     * Addressed like an insert rather than like the master, because that is
     * now what they are: a slot holding any effect, with that effect's own
     * parameter table. They belong to the song rather than to a rack, so the
     * rack on the message is ignored.
     */
    Send1, Send2,
    /**
     * The two effects on the way *in*.
     *
     * The same shape as a send's - a slot holding any effect, with that
     * effect's own table, belonging to the song rather than to a rack - and
     * the same reason the rack on the message is ignored. What makes them
     * different is where they run: before the input is published, so what they
     * do is **printed into a recording** rather than applied to a playback.
     */
    Input1, Input2,
    /**
     * The performance strip: mod and pressure.
     *
     * Not a unit with parameters of its own - a pseudo-unit, so that the
     * mod wheel and aftertouch can be recorded into a lane and played back
     * by the same machinery every knob already uses. What comes out the
     * other end is MIDI again, which is how they arrived.
     */
    Performance,
    /**
     * The master's two inserts: after the sends come back, before the fader
     * and the limiter. The same slot shape as a send; the rack is ignored.
     */
    MasterFx1, MasterFx2,
    /** The four mixer groups' two inserts each, group-major. The rack is ignored. */
    Group1Fx1, Group1Fx2, Group2Fx1, Group2Fx2, Group3Fx1, Group3Fx2, Group4Fx1, Group4Fx2,
    /**
     * The held effects on the master: repeat, tape stop and the pad. Carried
     * on a rack only so that a press can be recorded into that track's clip
     * and played back from it; what it moves is the master's.
     */
    Perform,
};

/** Indices within Unit::Performance. */
constexpr int32_t kPerfMod = 0;      // CC 1
constexpr int32_t kPerfPressure = 1; // channel aftertouch

struct ParamMessage {
    int32_t rack = 0;
    Unit unit = Unit::Machine;
    int32_t index = 0; // into the unit's ParamDef table
    float value = 0.0f; // normalised 0..1
    bool record = false; // a user gesture: may be recorded into a lane and wins over its lane this pass
};

/** Sixteen tracks of notes plus a clock pulse every ten ticks; 1024 is
 *  minutes of headroom if the sender is ever late. */
using MidiOutQueue = RtQueue<MidiOutEvent, 1024>;
using MidiClockQueue = RtQueue<MidiInEvent, 256>;

} // namespace acidulous
