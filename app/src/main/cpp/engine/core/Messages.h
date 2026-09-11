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

struct MidiMessage {
    uint8_t status = 0; // channel in the low nibble == rack
    uint8_t data1 = 0;
    uint8_t data2 = 0;
};

// The ordinal crosses the queue and the JNI boundary but is never written
// to a file - lanes are keyed by unit *name* - so inserting here is safe,
// as long as Recorder.UNITS on the Kotlin side is kept in the same order.
enum class Unit : uint8_t { Machine, Effect1, Effect2, Eventor1, Eventor2, Eventor3, Channel, Master };

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

} // namespace acidulous
