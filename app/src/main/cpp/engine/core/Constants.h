#pragma once
#include <cstdint>

// The fixed points of the engine. Everything else is derived from these.
namespace acidulous {

constexpr int32_t kSampleRate = 48000; // Oboe resamples if the device disagrees
constexpr int32_t kBlockFrames = 64;   // one render block; the sequencer's event resolution
constexpr int32_t kRackCount = 16;     // one per MIDI channel; a 4x4 picker
constexpr int32_t kPPQN = 240;         // ticks per quarter note
constexpr int32_t kEffectSlots = 2;    // per rack, for now
// Three, one per eventor: the keyboard strip gives chord, scale and arp a
// control each, so all three have to be able to run at once.
constexpr int32_t kEventorSlots = 3;
constexpr int32_t kMaxParams = 256;    // per unit (Forage: 14 per pad x 13 pads, plus globals)

} // namespace acidulous
