#pragma once
#include <cstdint>

// The fixed points of the engine. Everything else is derived from these.
namespace acidulous {

constexpr int32_t kSampleRate = 48000; // Oboe resamples if the device disagrees
constexpr int32_t kBlockFrames = 64;   // one render block; the sequencer's event resolution
constexpr int32_t kRackCount = 16;     // one per MIDI channel; a 4x4 picker
constexpr int32_t kPPQN = 240;         // ticks per quarter note
constexpr int32_t kEffectSlots = 2;    // per rack, for now
/**
 * Effects on the way *in*, before anything hears the input.
 *
 * Two, like a rack's. The difference between these and a track's inserts is
 * the whole reason they exist: **what is on the input is printed into the
 * recording**, and what is on the track is applied on playback and can be
 * changed afterwards. An amp you want on the take goes here; an amp you want
 * to keep deciding about goes there.
 */
constexpr int32_t kInputSlots = 2;
// Three, one per modifier: the keyboard strip gives chord, scale and arp a
// control each, so all three have to be able to run at once.
constexpr int32_t kInputModSlots = 3;
constexpr int32_t kMaxParams = 256;    // per unit (Forage: 14 per pad x 13 pads, plus globals)

} // namespace acidulous
