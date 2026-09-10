#pragma once
#include <cstdint>

// What the UI thread sends the audio thread, besides objects to mount.
namespace acidulous {

struct MidiMessage {
    uint8_t status = 0; // channel in the low nibble == rack
    uint8_t data1 = 0;
    uint8_t data2 = 0;
};

enum class Unit : uint8_t { Machine, Effect1, Effect2, Eventor1, Eventor2, Channel, Master };

struct ParamMessage {
    int32_t rack = 0;
    Unit unit = Unit::Machine;
    int32_t index = 0; // into the unit's ParamDef table
    float value = 0.0f; // normalised 0..1
    bool record = false; // a user gesture: may be recorded into a lane and wins over its lane this pass
};

} // namespace acidulous
